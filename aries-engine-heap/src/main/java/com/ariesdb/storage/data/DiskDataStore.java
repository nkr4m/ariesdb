package com.ariesdb.storage.data;

import com.ariesdb.buffer.BufferPoolManager;
import com.ariesdb.common.utils.RID;
import com.ariesdb.storage.page.Page;
import com.ariesdb.storage.page.SlottedPage;
import com.ariesdb.wal.LogManager;
import com.ariesdb.wal.LogType;

import java.nio.ByteBuffer;

public class DiskDataStore {
    private final BufferPoolManager bpm;
    private final LogManager logManager;
    private int currentHeapPageId;

    public DiskDataStore(BufferPoolManager bpm, LogManager logManager, int startingPageId) throws Exception {
        this.bpm = bpm;
        this.logManager = logManager;
        this.currentHeapPageId = startingPageId;

        if (startingPageId == 0) {
            Page page0 = bpm.fetchPage(0);
            try {
                SlottedPage slottedPage = new SlottedPage(page0);
                slottedPage.init();
            } finally {
                bpm.unpinPage(0, true);
            }
        }
    }

    public RID insertRecord(int key, byte[] data) throws Exception {
        ByteBuffer recordBuffer = ByteBuffer.allocate(4 + data.length);
        recordBuffer.putInt(key); 
        recordBuffer.put(data);
        byte[] payload = recordBuffer.array();

        int targetPageId = currentHeapPageId;
        int slotId = -1;
        long lsn = -1;

        Page page = bpm.fetchPage(targetPageId);
        try {
            SlottedPage slottedPage = new SlottedPage(page);
            slotId = slottedPage.insertRecord(payload);
            if (slotId != -1) {
                lsn = logManager.appendLog(LogType.INSERT, targetPageId, slotId, key, data);
                slottedPage.setPageLsn(lsn);
            }
        } finally {
            bpm.unpinPage(targetPageId, slotId != -1);
        }

        if (slotId == -1) {
            targetPageId++;
            currentHeapPageId = targetPageId; 
            
            page = bpm.fetchPage(targetPageId);
            try {
                SlottedPage slottedPage = new SlottedPage(page);
                slottedPage.init(); 
                slotId = slottedPage.insertRecord(payload);
                lsn = logManager.appendLog(LogType.INSERT, targetPageId, slotId, key, data);
                slottedPage.setPageLsn(lsn);
            } finally {
                bpm.unpinPage(targetPageId, true);
            }
        }
        return new RID(targetPageId, slotId);
    }

    public byte[] getRecord(RID rid) {
        Page page = bpm.fetchPage(rid.getPageId());
        try {
            SlottedPage slottedPage = new SlottedPage(page);
            byte[] rawBytes = slottedPage.getRecord(rid.getSlotId());
            if (rawBytes == null) return null;
            
            byte[] userData = new byte[rawBytes.length - 4];
            System.arraycopy(rawBytes, 4, userData, 0, userData.length);
            return userData;
        } finally {
            bpm.unpinPage(rid.getPageId(), false);
        }
    }

    public void deleteRecord(int key, RID rid) throws Exception {
        Page page = bpm.fetchPage(rid.getPageId());
        try {
            SlottedPage slottedPage = new SlottedPage(page);
            slottedPage.deleteRecord(rid.getSlotId());
            long lsn = logManager.appendLog(LogType.DELETE, rid.getPageId(), rid.getSlotId(), key, null);
            slottedPage.setPageLsn(lsn);
        } finally {
            bpm.unpinPage(rid.getPageId(), true);
        }
    }
}