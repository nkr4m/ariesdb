package com.ariesdb.storage.index;

import com.ariesdb.buffer.BufferPoolManager;
import com.ariesdb.common.utils.RID;
import com.ariesdb.storage.page.BMinusTreePage;
import com.ariesdb.storage.page.Page;

public class DiskBMinusTreeStore {
    private final BufferPoolManager bpm;
    private int rootPageId;
    private int nextAvailablePageId; 

    public DiskBMinusTreeStore(BufferPoolManager bpm, int rootPageId) {
        this.bpm = bpm;
        this.rootPageId = rootPageId;
        // Assuming page 0 is root, the next new node will be page 1.
        // In a fully persistent engine, this would be read from a metadata header.
        this.nextAvailablePageId = 1; 
    }

    public void createNewTree() {
        Page rootPage = bpm.fetchPage(rootPageId);
        try {
            BMinusTreePage bTreePage = new BMinusTreePage(rootPage);
            bTreePage.init(true, -1); 
        } finally {
            bpm.unpinPage(rootPageId, true); 
        }
    }

    public RID search(int key) {
        int currPageId = rootPageId;
        while (currPageId != -1) {
            Page page = bpm.fetchPage(currPageId);
            try {
                BMinusTreePage bTreePage = new BMinusTreePage(page);
                int count = bTreePage.getKeyCount();
                int i = 0;
                while (i < count && key > bTreePage.getKey(i)) i++;

                if (i < count && bTreePage.getKey(i) == key) {
                    return bTreePage.getValue(i); 
                }
                if (bTreePage.isLeaf()) {
                    return null; 
                }
                currPageId = bTreePage.getChildPageId(i);
            } finally {
                bpm.unpinPage(currPageId, false); 
            }
        }
        return null;
    }

    /**
     * Entry point for insertion. Handles the special case where the Root itself splits.
     */
    public void insert(int key, RID value) {
        Page rootPage = bpm.fetchPage(rootPageId);
        boolean rootNeedsSplit = false;
        
        try {
            BMinusTreePage rootBTreePage = new BMinusTreePage(rootPage);
            if (rootBTreePage.getKeyCount() == BMinusTreePage.MAX_KEYS) {
                rootNeedsSplit = true;
            }
        } finally {
            bpm.unpinPage(rootPageId, false);
        }

        if (rootNeedsSplit) {
            // Allocate a new page to become the completely new root
            int newRootId = allocateNewPageId();
            Page newRootPage = bpm.fetchPage(newRootId);
            
            try {
                BMinusTreePage newRoot = new BMinusTreePage(newRootPage);
                newRoot.init(false, -1);
                newRoot.setChildPageId(0, rootPageId);
                
                // Split the old root
                splitChild(newRoot, 0, rootPageId);
                
                // Update the system root pointer
                rootPageId = newRootId;
            } finally {
                bpm.unpinPage(newRootId, true);
            }
        }

        // Now that the root is guaranteed not to be full, recursively insert
        insertNonFull(rootPageId, key, value);
    }

    /**
     * Recursively traverses down, splitting full children along the way.
     */
    private void insertNonFull(int pageId, int key, RID value) {
        Page page = bpm.fetchPage(pageId);
        try {
            BMinusTreePage node = new BMinusTreePage(page);
            int count = node.getKeyCount();
            int i = count - 1;

            if (node.isLeaf()) {
                // Base case: Found the leaf, insert exactly here
                while (i >= 0 && key < node.getKey(i)) {
                    i--;
                }
                node.insertAt(i + 1, key, value);
            } else {
                // Internal node: find which child to traverse into
                while (i >= 0 && key < node.getKey(i)) {
                    i--;
                }
                i++;
                
                int childPageId = node.getChildPageId(i);
                Page childPage = bpm.fetchPage(childPageId);
                boolean childIsFull = false;
                
                try {
                    BMinusTreePage childNode = new BMinusTreePage(childPage);
                    if (childNode.getKeyCount() == BMinusTreePage.MAX_KEYS) {
                        childIsFull = true;
                    }
                } finally {
                    bpm.unpinPage(childPageId, false);
                }

                // Proactive split: If the child is full, split it BEFORE stepping into it
                if (childIsFull) {
                    splitChild(node, i, childPageId);
                    // After split, the middle key was pushed up. Determine which half to traverse.
                    if (key > node.getKey(i)) {
                        i++;
                    }
                }
                
                // Recursively insert into the correct, non-full child
                insertNonFull(node.getChildPageId(i), key, value);
            }
        } finally {
            bpm.unpinPage(pageId, true); // Always dirtied during insert/split traversal
        }
    }

    /**
     * Splits a full child node, pushing the middle key up to the parent.
     */
    private void splitChild(BMinusTreePage parentNode, int childIndex, int fullChildPageId) {
        int newChildPageId = allocateNewPageId();
        Page newChildPage = bpm.fetchPage(newChildPageId);
        Page fullChildPage = bpm.fetchPage(fullChildPageId);
        
        try {
            BMinusTreePage newChild = new BMinusTreePage(newChildPage);
            BMinusTreePage fullChild = new BMinusTreePage(fullChildPage);
            
            int midIndex = BMinusTreePage.MAX_KEYS / 2;
            newChild.init(fullChild.isLeaf(), parentNode.getParentPageId()); // Inherit leaf status
            
            // 1. Move upper half of keys and values to the new right child
            int keysToMove = BMinusTreePage.MAX_KEYS - midIndex - 1;
            for (int j = 0; j < keysToMove; j++) {
                newChild.insertAt(j, fullChild.getKey(midIndex + 1 + j), fullChild.getValue(midIndex + 1 + j));
            }
            
            // 2. Move upper half of children pointers if it's an internal node
            if (!fullChild.isLeaf()) {
                for (int j = 0; j <= keysToMove; j++) {
                    newChild.setChildPageId(j, fullChild.getChildPageId(midIndex + 1 + j));
                }
            }
            
            // 3. Update key counts
            fullChild.setKeyCount(midIndex);
            
            // 4. Push the middle key/value UP to the parent node
            parentNode.insertAt(childIndex, fullChild.getKey(midIndex), fullChild.getValue(midIndex));
            
            // 5. Link the new right child in the parent
            // insertAt shifts keys/values, but we must manually shift child pointers for the new gap
            for (int j = parentNode.getKeyCount(); j > childIndex + 1; j--) {
                parentNode.setChildPageId(j, parentNode.getChildPageId(j - 1));
            }
            parentNode.setChildPageId(childIndex + 1, newChildPageId);

        } finally {
            bpm.unpinPage(newChildPageId, true);
            bpm.unpinPage(fullChildPageId, true);
        }
    }

    private int allocateNewPageId() {
        // Returns a monotonically increasing Page ID for the index file.
        return nextAvailablePageId++;
    }
}