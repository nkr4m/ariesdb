package com.ariesdb.storage.data;

import com.ariesdb.buffer.BufferPoolManager;
import com.ariesdb.common.utils.RID;
import com.ariesdb.storage.page.Page;
import com.ariesdb.storage.page.SlottedPage;
import com.ariesdb.transaction.Transaction;
import com.ariesdb.wal.LogManager;
import com.ariesdb.wal.LogRecord;
import com.ariesdb.wal.LogType;

import java.nio.ByteBuffer;

public class DiskDataStore {
    private final BufferPoolManager bpm;
    private final LogManager logManager;
    private final String tableName; // NEW: Tracks which table this store belongs to
    private int currentHeapPageId;

    // CHANGED: Constructor now requires the tableName String as the first argument
    public DiskDataStore(String tableName, BufferPoolManager bpm, LogManager logManager, int startingPageId) throws Exception {
        this.tableName = tableName;
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

    public RID insertRecord(Transaction txn, int key, byte[] data) throws Exception {
        ByteBuffer recordBuffer = ByteBuffer.allocate(4 + data.length);
        recordBuffer.putInt(key); 
        recordBuffer.put(data);
        byte[] payload = recordBuffer.array();

        int targetPageId = currentHeapPageId;
        int slotId = -1;

        Page page = bpm.fetchPage(targetPageId);
        try {
            SlottedPage slottedPage = new SlottedPage(page);
            slotId = slottedPage.insertRecord(payload);
            if (slotId != -1) {
                // CHANGED: Pass tableName to LogManager
                LogRecord record = logManager.appendLog(txn.getTxnId(), LogType.INSERT, tableName, targetPageId, slotId, key, data);
                slottedPage.setPageLsn(record.getLsn());
                txn.addLogRecord(record);
                txn.addModifiedPage(targetPageId);
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
                
                // CHANGED: Pass tableName to LogManager
                LogRecord record = logManager.appendLog(txn.getTxnId(), LogType.INSERT, tableName, targetPageId, slotId, key, data);
                slottedPage.setPageLsn(record.getLsn());
                txn.addLogRecord(record);
                txn.addModifiedPage(targetPageId);
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

    public void deleteRecord(Transaction txn, int key, RID rid) throws Exception {
        Page page = bpm.fetchPage(rid.getPageId());
        try {
            SlottedPage slottedPage = new SlottedPage(page);
            
            byte[] rawBytes = slottedPage.getRecord(rid.getSlotId());
            byte[] oldData = null;
            if (rawBytes != null) {
                oldData = new byte[rawBytes.length - 4];
                System.arraycopy(rawBytes, 4, oldData, 0, oldData.length);
            }
            
            slottedPage.deleteRecord(rid.getSlotId());
            
            // CHANGED: Pass tableName to LogManager
            LogRecord record = logManager.appendLog(txn.getTxnId(), LogType.DELETE, tableName, rid.getPageId(), rid.getSlotId(), key, oldData);
            slottedPage.setPageLsn(record.getLsn());
            
            txn.addLogRecord(record);
            txn.addModifiedPage(rid.getPageId());
        } finally {
            bpm.unpinPage(rid.getPageId(), true);
        }
    }

    public void undoInsert(int pageId, int slotId) throws Exception {
        Page page = bpm.fetchPage(pageId);
        try {
            SlottedPage slottedPage = new SlottedPage(page);
            slottedPage.deleteRecord(slotId); 
        } finally {
            bpm.unpinPage(pageId, true);
        }
    }
}