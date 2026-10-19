package com.ariesdb.engine;

import com.ariesdb.common.utils.RID;
import com.ariesdb.index.StandardBTree;
import com.ariesdb.index.StorageIndex;
import com.ariesdb.storage.disk.DiskManager;
import com.ariesdb.storage.page.SlottedPage;

/**
 * The main facade for the Aries Storage Engine.
 * It bridges the page-level DiskManager and the record-level B-Tree Index.
 */
public class AriesEngine<K extends Comparable<K>> {
    
    private final StorageIndex<K> index;
    private final DiskManager diskManager;

    public AriesEngine(DiskManager diskManager, int btreeDegree) {
        this.diskManager = diskManager;
        this.index = new StandardBTree<>(btreeDegree);
    }

    /**
     * Inserts a record. Finds a page with free space, inserts the bytes, 
     * writes the page back to disk, and indexes the resulting RID.
     */
    public void insert(K key, byte[] recordData) throws Exception {
        int totalPages = diskManager.getTotalPages();
        // If the file is empty, we start at page 0
        int targetPageId = totalPages > 0 ? totalPages - 1 : 0;
        
        SlottedPage page = diskManager.readPage(targetPageId);
        
        // Attempt to insert into the current page
        int slotId = page.insert(recordData); 
        
        if (slotId == -1) { 
            // The current page is full. Create a brand new page.
            targetPageId++; 
            page = new SlottedPage(targetPageId); 
            slotId = page.insert(recordData);
            
            if (slotId == -1) {
                throw new RuntimeException("Record is too large to fit in a 4KB page.");
            }
        }
        
        // Persist the page to disk
        diskManager.writePage(targetPageId, page);
        
        // Create the physical pointer and store it in the B-Tree
        RID rid = new RID(targetPageId, slotId);
        index.insert(key, rid);
    }

    /**
     * Reads a record by its exact key.
     */
    public byte[] read(K key) throws Exception {
        // Query the B-Tree for the physical location
        RID rid = index.search(key);
        
        if (rid == null) {
            return null; // Key does not exist or was deleted
        }
        
        // Fetch the specific page from disk
        SlottedPage page = diskManager.readPage(rid.getPageId());
        
        // Extract the exact bytes using the O(1) slot offset
        return page.read(rid.getSlotId());
    }

    /**
     * Deletes a record by marking it as a tombstone in the index.
     */
    public boolean delete(K key) {
        RID rid = index.search(key);
        if (rid == null) {
            return false; 
        }
        
        // Lazy deletion: Mark the B-Tree node as a tombstone.
        // We do not remove the bytes from the SlottedPage yet.
        index.delete(key);
        return true;
    }
    
    /**
     * Updates an existing record using an out-of-place (append-only) update strategy.
     */
    public boolean update(K key, byte[] newRecordData) throws Exception {
        RID existingRid = index.search(key);
        if (existingRid == null) {
            return false; // Cannot update a record that doesn't exist
        }
        
        // 1. Write the new payload as a brand new record
        int totalPages = diskManager.getTotalPages();
        int targetPageId = totalPages > 0 ? totalPages - 1 : 0;
        SlottedPage page = diskManager.readPage(targetPageId);
        
        int slotId = page.insert(newRecordData);
        if (slotId == -1) {
            targetPageId++;
            page = new SlottedPage(targetPageId);
            slotId = page.insert(newRecordData);
        }
        
        diskManager.writePage(targetPageId, page);
        RID newRid = new RID(targetPageId, slotId);
        
        // 2. Overwrite the old index pointer with the new physical location.
        // The old record remains in the SlottedPage as garbage until a compaction cycle.
        index.insert(key, newRid);
        
        return true;
    }
}
