// src/main/java/com/ariesdb/engine/AriesEngine.java
package com.ariesdb.engine;

import com.ariesdb.buffer.BufferPoolManager;
import com.ariesdb.buffer.replacer.ClockReplacer;
import com.ariesdb.common.utils.RID;
import com.ariesdb.index.bminustree.BMinusTree;
import com.ariesdb.storage.disk.DiskManager;
import com.ariesdb.storage.page.Page;
import com.ariesdb.storage.page.SlottedPage;

public class AriesEngine<K extends Comparable<K>> implements AutoCloseable {
    private final DiskManager diskManager;
    private final BufferPoolManager bufferPoolManager;
    private final BMinusTree<K> btree;

    // Tracks the current logical page being used to append new heap records
    private int currentHeapPageId;

    public AriesEngine(DiskManager diskManager, int btreeDegree) throws Exception {
        this.diskManager = diskManager;
        
        // Initialize 50 frames of RAM (200KB total cache)
        int poolSize = 50; 
        this.bufferPoolManager = new BufferPoolManager(poolSize, diskManager, new ClockReplacer(poolSize));
        
        // Initialize the logical B-Minus Tree index
        this.btree = new BMinusTree<>(btreeDegree);

        // Prepare the first data page for inserting records
        this.currentHeapPageId = 0;
        if (diskManager.getTotalPages() == 0) {
            // Brand new database: format the very first page
            Page page0 = bufferPoolManager.fetchPage(currentHeapPageId);
            try {
                SlottedPage slottedPage = new SlottedPage(page0);
                slottedPage.init();
            } finally {
                // Always unpin in a finally block to prevent memory leaks
                bufferPoolManager.unpinPage(currentHeapPageId, true);
            }
        }
    }

    public void insert(K key, byte[] data) {
        int targetPageId = currentHeapPageId;
        int slotId = -1;

        Page page = bufferPoolManager.fetchPage(targetPageId);
        try {
            SlottedPage slottedPage = new SlottedPage(page);
            slotId = slottedPage.insertRecord(data);
        } finally {
            bufferPoolManager.unpinPage(targetPageId, slotId != -1);
        }

        if (slotId == -1) {
            targetPageId++;
            currentHeapPageId = targetPageId; 
            
            page = bufferPoolManager.fetchPage(targetPageId);
            try {
                SlottedPage slottedPage = new SlottedPage(page);
                slottedPage.init(); 
                slotId = slottedPage.insertRecord(data);
                
                if (slotId == -1) {
                    throw new RuntimeException("Record is too large to fit in a 4KB page.");
                }
            } finally {
                bufferPoolManager.unpinPage(targetPageId, true);
            }
        }

        btree.insert(key, new RID(targetPageId, slotId));
    }

    public byte[] read(K key) {
        RID rid = btree.search(key);
        if (rid == null) {
            return null; 
        }

        Page page = bufferPoolManager.fetchPage(rid.getPageId());
        try {
            SlottedPage slottedPage = new SlottedPage(page);
            return slottedPage.getRecord(rid.getSlotId());
        } finally {
            bufferPoolManager.unpinPage(rid.getPageId(), false);
        }
    }

    public boolean update(K key, byte[] data) {
        RID existingRid = btree.search(key);
        if (existingRid == null) {
            return false; 
        }
        insert(key, data);
        return true;
    }

    public boolean delete(K key) {
        RID existingRid = btree.search(key);
        if (existingRid == null) {
            return false;
        }

        btree.delete(key);
        return true;
    }

    @Override
    public void close() throws Exception {
        if (bufferPoolManager != null) {
            bufferPoolManager.flushAllPages();
        }
    }
}