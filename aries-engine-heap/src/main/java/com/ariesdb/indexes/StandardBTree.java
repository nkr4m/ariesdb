package com.ariesdb.indexes;


import java.util.ArrayList;
import java.util.List;

import com.ariesdb.storage.RID;

/**
 * Concrete implementation of a Standard B-Tree.
 * Supports proactive splitting on insertion and lazy deletion (tombstoning).
 */
public class StandardBTree<K extends Comparable<K>> extends BTreeIndex<K> {

    // Inner class representing the specific node structure for a standard B-Tree
    private static class StandardNode<K extends Comparable<K>> extends BTreeNode<K> {
        List<RID> rids;
        List<Boolean> isTombstone;
        List<StandardNode<K>> children;

        public StandardNode(boolean isLeaf) {
            super(isLeaf);
            this.rids = new ArrayList<>();
            this.isTombstone = new ArrayList<>();
            this.children = new ArrayList<>();
        }
    }

    public StandardBTree(int degree) {
        super(degree);
        this.root = new StandardNode<>(true);
    }

    // ==========================================
    // 1. SEARCH LOGIC
    // ==========================================
    
    @Override
    public RID search(K key) {
        return searchNode((StandardNode<K>) root, key);
    }

    private RID searchNode(StandardNode<K> node, K key) {
        int i = 0;
        
        while (i < node.keys.size() && key.compareTo(node.keys.get(i)) > 0) {
            i++;
        }

        if (i < node.keys.size() && key.compareTo(node.keys.get(i)) == 0) {
            if (node.isTombstone.get(i)) {
                return null; // Pretend it doesn't exist
            }
            return node.rids.get(i);
        }

        if (node.isLeaf) {
            return null;
        }

        return searchNode(node.children.get(i), key);
    }

    // ==========================================
    // 2. INSERTION & SPLITTING LOGIC
    // ==========================================

    @Override
    public void insert(K key, RID rid) {
        StandardNode<K> r = (StandardNode<K>) root;
        
        // Check if root is full (contains 2t - 1 keys)
        if (r.keys.size() == 2 * degree - 1) {
            StandardNode<K> newRoot = new StandardNode<>(false);
            this.root = newRoot;
            newRoot.children.add(r);
            splitChild(newRoot, 0, r);
            insertNonFull(newRoot, key, rid);
        } else {
            insertNonFull(r, key, rid);
        }
    }

    private void insertNonFull(StandardNode<K> node, K key, RID rid) {
        // 1. Revive/Update: Check if key already exists in this node
        for (int j = 0; j < node.keys.size(); j++) {
            if (key.compareTo(node.keys.get(j)) == 0) {
                node.rids.set(j, rid);
                node.isTombstone.set(j, false); // Remove tombstone if it was deleted
                return;
            }
        }

        int i = node.keys.size() - 1;

        if (node.isLeaf) {
            // 2. Leaf Node: Make room and insert in sorted order
            node.keys.add(null);
            node.rids.add(null);
            node.isTombstone.add(false);

            while (i >= 0 && key.compareTo(node.keys.get(i)) < 0) {
                node.keys.set(i + 1, node.keys.get(i));
                node.rids.set(i + 1, node.rids.get(i));
                node.isTombstone.set(i + 1, node.isTombstone.get(i));
                i--;
            }
            
            node.keys.set(i + 1, key);
            node.rids.set(i + 1, rid);
            node.isTombstone.set(i + 1, false);
        } else {
            // 3. Internal Node: Find the child to descend into
            while (i >= 0 && key.compareTo(node.keys.get(i)) < 0) {
                i--;
            }
            i++;

            StandardNode<K> child = node.children.get(i);
            
            // 4. Proactive split if the chosen child is full
            if (child.keys.size() == 2 * degree - 1) {
                splitChild(node, i, child);
                
                if (key.compareTo(node.keys.get(i)) > 0) {
                    i++;
                } else if (key.compareTo(node.keys.get(i)) == 0) {
                    // Handled if the inserted key was just promoted
                    node.rids.set(i, rid);
                    node.isTombstone.set(i, false);
                    return;
                }
            }
            
            insertNonFull(node.children.get(i), key, rid);
        }
    }

    private void splitChild(StandardNode<K> parent, int childIndex, StandardNode<K> fullChild) {
        StandardNode<K> newChild = new StandardNode<>(fullChild.isLeaf);
        
        // Move the right half of keys, RIDs, and Tombstones to newChild
        for (int j = 0; j < degree - 1; j++) {
            newChild.keys.add(fullChild.keys.get(j + degree));
            newChild.rids.add(fullChild.rids.get(j + degree));
            newChild.isTombstone.add(fullChild.isTombstone.get(j + degree));
        }

        // Move the right half of children if it's an internal node
        if (!fullChild.isLeaf) {
            for (int j = 0; j < degree; j++) {
                newChild.children.add(fullChild.children.get(j + degree));
            }
        }

        // Link newChild into the parent
        parent.children.add(childIndex + 1, newChild);

        // Push the median key up into the parent
        parent.keys.add(childIndex, fullChild.keys.get(degree - 1));
        parent.rids.add(childIndex, fullChild.rids.get(degree - 1));
        parent.isTombstone.add(childIndex, fullChild.isTombstone.get(degree - 1));

        // Truncate the fullChild to only keep its left half
        fullChild.keys.subList(degree - 1, fullChild.keys.size()).clear();
        fullChild.rids.subList(degree - 1, fullChild.rids.size()).clear();
        fullChild.isTombstone.subList(degree - 1, fullChild.isTombstone.size()).clear();
        if (!fullChild.isLeaf) {
            fullChild.children.subList(degree, fullChild.children.size()).clear();
        }
    }

    // ==========================================
    // 3. DELETION LOGIC (LAZY / TOMBSTONE)
    // ==========================================

    @Override
    public void delete(K key) {
        markTombstone((StandardNode<K>) root, key);
    }

    private boolean markTombstone(StandardNode<K> node, K key) {
        int i = 0;
        
        while (i < node.keys.size() && key.compareTo(node.keys.get(i)) > 0) {
            i++;
        }

        if (i < node.keys.size() && key.compareTo(node.keys.get(i)) == 0) {
            if (!node.isTombstone.get(i)) {
                node.isTombstone.set(i, true);
                return true;
            }
            return false;
        }

        if (node.isLeaf) {
            return false;
        }

        return markTombstone(node.children.get(i), key);
    }
}