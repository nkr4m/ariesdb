package com.ariesdb.engine;

import com.ariesdb.buffer.BufferPoolManager;
import com.ariesdb.buffer.replacer.ClockReplacer;
import com.ariesdb.common.utils.RID;
import com.ariesdb.index.bminustree.BMinusTree;
import com.ariesdb.storage.disk.DiskManager;
import com.ariesdb.storage.page.Page;
import com.ariesdb.storage.page.SlottedPage;

import java.nio.ByteBuffer;

public class AriesEngine<K extends Comparable<K>> implements AutoCloseable {
    private final DiskManager diskManager;
    private final BufferPoolManager bufferPoolManager;
    private final BMinusTree<K> btree;
    private int currentHeapPageId;

    public AriesEngine(DiskManager diskManager, int btreeDegree) throws Exception {
        this.diskManager = diskManager;
        int poolSize = 50; 
        this.bufferPoolManager = new BufferPoolManager(poolSize, diskManager, new ClockReplacer(poolSize));
        this.btree = new BMinusTree<>(btreeDegree);

        // JVM Shutdown Hook to guarantee pages flush to disk even if exit/close is bypassed
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (bufferPoolManager != null) {
                    bufferPoolManager.flushAllPages();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }));

        int totalPages = diskManager.getTotalPages();
        
        if (totalPages == 0) {
            // BRAND NEW DATABASE: Format Page 0
            this.currentHeapPageId = 0;
            Page page0 = bufferPoolManager.fetchPage(currentHeapPageId);
            try {
                SlottedPage slottedPage = new SlottedPage(page0);
                slottedPage.init();
            } finally {
                bufferPoolManager.unpinPage(currentHeapPageId, true);
            }
        } else {
            // EXISTING DATABASE: Recover the B-Tree from Disk!
            this.currentHeapPageId = totalPages - 1; // Append to the last known page
            recoverIndexFromDisk(totalPages);
        }
    }

    /**
     * CRASH RECOVERY: Scans all physical pages and reconstructs the RAM index.
     */
    @SuppressWarnings("unchecked")
    private void recoverIndexFromDisk(int totalPages) {
        System.out.println("Starting Crash Recovery... Rebuilding B-Tree Index from Disk.");
        int recoveredRecords = 0;

        for (int i = 0; i < totalPages; i++) {
            Page page = bufferPoolManager.fetchPage(i);
            try {
                SlottedPage slottedPage = new SlottedPage(page);
                int slotCount = slottedPage.getSlotCount();
                
                for (int slotId = 0; slotId < slotCount; slotId++) {
                    byte[] rawBytes = slottedPage.getRecord(slotId);
                    
                    if (rawBytes != null) { // Not a tombstone
                        // Extract the 4-byte key from the payload
                        ByteBuffer buffer = ByteBuffer.wrap(rawBytes);
                        int extractedKey = buffer.getInt();
                        
                        // Re-insert into the B-Tree
                        btree.insert((K) Integer.valueOf(extractedKey), new RID(i, slotId));
                        recoveredRecords++;
                    }
                }
            } finally {
                bufferPoolManager.unpinPage(i, false); // Just reading, not dirty
            }
        }
        System.out.println("Recovery Complete! Rebuilt " + recoveredRecords + " records into the index.");
    }

    public void insert(K key, byte[] data) {
        // Pack the Key and Data together: [ 4 Bytes Integer Key ] [ N Bytes Data Payload ]
        ByteBuffer recordBuffer = ByteBuffer.allocate(4 + data.length);
        recordBuffer.putInt((Integer) key); 
        recordBuffer.put(data);
        byte[] payloadToSave = recordBuffer.array();

        int targetPageId = currentHeapPageId;
        int slotId = -1;

        Page page = bufferPoolManager.fetchPage(targetPageId);
        try {
            SlottedPage slottedPage = new SlottedPage(page);
            slotId = slottedPage.insertRecord(payloadToSave);
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
                slotId = slottedPage.insertRecord(payloadToSave);
                
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
            byte[] rawBytes = slottedPage.getRecord(rid.getSlotId());
            
            if (rawBytes == null) return null;
            
            // Strip the 4-byte key off before returning the user's raw data payload
            byte[] userData = new byte[rawBytes.length - 4];
            System.arraycopy(rawBytes, 4, userData, 0, userData.length);
            return userData;
            
        } finally {
            bufferPoolManager.unpinPage(rid.getPageId(), false);
        }
    }

    public boolean update(K key, byte[] data) {
        RID existingRid = btree.search(key);
        if (existingRid == null) return false; 
        
        insert(key, data);
        return true;
    }

    public boolean delete(K key) {
        RID existingRid = btree.search(key);
        if (existingRid == null) return false;

        Page page = bufferPoolManager.fetchPage(existingRid.getPageId());
        try {
            SlottedPage slottedPage = new SlottedPage(page);
            slottedPage.deleteRecord(existingRid.getSlotId());
        } finally {
            bufferPoolManager.unpinPage(existingRid.getPageId(), true);
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