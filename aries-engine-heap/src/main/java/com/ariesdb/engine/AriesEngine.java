package com.ariesdb.engine;

import com.ariesdb.buffer.BufferPoolManager;
import com.ariesdb.buffer.replacer.ClockReplacer;
import com.ariesdb.common.utils.RID;
import com.ariesdb.storage.data.DiskDataStore;
import com.ariesdb.storage.disk.DiskManager;
import com.ariesdb.storage.index.DiskBMinusTreeStore;
import com.ariesdb.storage.page.Page;
import com.ariesdb.storage.page.SlottedPage;
import com.ariesdb.transaction.LockManager;
import com.ariesdb.transaction.Transaction;
import com.ariesdb.transaction.TransactionManager;
import com.ariesdb.wal.CheckpointManager;
import com.ariesdb.wal.LogManager;
import com.ariesdb.wal.LogType;
import com.ariesdb.wal.LogRecord;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

public class AriesEngine<K extends Comparable<K>> implements AutoCloseable {
    
    private final DiskManager heapDiskManager;
    private final DiskManager indexDiskManager;
    private final BufferPoolManager heapBufferPool;
    private final BufferPoolManager indexBufferPool;
    
    private final DiskDataStore dataStore;
    private final DiskBMinusTreeStore btreeStore;

    private final LogManager logManager;
    private final CheckpointManager checkpointManager;
    private final TransactionManager txnManager;
    private final LockManager lockManager;

    public AriesEngine(DiskManager heapDiskManager, DiskManager indexDiskManager, String dbFilename, int btreeDegree) throws Exception {
        this.heapDiskManager = heapDiskManager;
        this.indexDiskManager = indexDiskManager;
        
        this.heapBufferPool = new BufferPoolManager(50, heapDiskManager, new ClockReplacer(50));
        this.indexBufferPool = new BufferPoolManager(50, indexDiskManager, new ClockReplacer(50));
        
        this.logManager = new LogManager(dbFilename);
        this.checkpointManager = new CheckpointManager(dbFilename);
        this.lockManager = new LockManager();
        
        this.btreeStore = new DiskBMinusTreeStore(indexBufferPool, 0); 

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { close(); } catch (Exception e) { e.printStackTrace(); }
        }));

        int heapPages = heapDiskManager.getTotalPages();
        int indexPages = indexDiskManager.getTotalPages();
        
        if (heapPages == 0 || indexPages == 0) {
            btreeStore.createNewTree(); 
            this.dataStore = new DiskDataStore(heapBufferPool, logManager, 0);
        } else {
            this.dataStore = new DiskDataStore(heapBufferPool, logManager, heapPages - 1);
            recoverFromWAL();
        }

        this.txnManager = new TransactionManager(logManager, dataStore, btreeStore);
    }

    private void recoverFromWAL() {
        long startOffset = checkpointManager.readCheckpoint();
        System.out.println("Starting WAL Crash Recovery from offset " + startOffset + "...");
        
        int replayedCount = 0; 
        int physicalRedoCount = 0;
        int undoneCount = 0;

        try {
            List<LogRecord> pendingLogs = logManager.readLogsFromOffset(startOffset);
            Set<Long> loserTxns = new HashSet<>();

            for (LogRecord record : pendingLogs) {
                if (record.getType() == LogType.COMMIT || record.getType() == LogType.ABORT) {
                    loserTxns.remove(record.getTxnId());
                    continue;
                } else {
                    loserTxns.add(record.getTxnId());
                }

                int keyVal = record.getKey();
                RID rid = new RID(record.getPageId(), record.getSlotId());

                Page page = heapBufferPool.fetchPage(record.getPageId());
                boolean isStale = false;
                try {
                    SlottedPage slottedPage = new SlottedPage(page);
                    if (record.getLsn() > slottedPage.getPageLsn()) {
                        isStale = true;
                        physicalRedoCount++;
                        
                        if (record.getType() == LogType.INSERT || record.getType() == LogType.UPDATE) {
                            ByteBuffer recordBuffer = ByteBuffer.allocate(4 + record.getData().length);
                            recordBuffer.putInt(keyVal); 
                            recordBuffer.put(record.getData());
                            slottedPage.insertRecord(recordBuffer.array());
                        } else if (record.getType() == LogType.DELETE) {
                            slottedPage.deleteRecord(record.getSlotId());
                        }
                        slottedPage.setPageLsn(record.getLsn());
                    }
                } finally {
                    heapBufferPool.unpinPage(record.getPageId(), isStale);
                }

                if (record.getType() == LogType.INSERT || record.getType() == LogType.UPDATE) {
                    btreeStore.insert(keyVal, rid);
                    replayedCount++;
                } 
            }
            
            if (!loserTxns.isEmpty()) {
                System.out.println("Found " + loserTxns.size() + " crashed active transaction(s). Starting UNDO phase...");
                
                for (int i = pendingLogs.size() - 1; i >= 0; i--) {
                    LogRecord record = pendingLogs.get(i);
                    if (loserTxns.contains(record.getTxnId())) {
                        if (record.getType() == LogType.INSERT) {
                            dataStore.undoInsert(record.getPageId(), record.getSlotId());
                            undoneCount++;
                        } else if (record.getType() == LogType.DELETE || record.getType() == LogType.UPDATE) {
                            Transaction dummyTxn = new Transaction(record.getTxnId());
                            RID newRid = dataStore.insertRecord(dummyTxn, record.getKey(), record.getData());
                            btreeStore.insert(record.getKey(), newRid);
                            undoneCount++;
                        }
                    }
                }
                
                for (Long txnId : loserTxns) {
                    logManager.appendLog(txnId, LogType.ABORT, -1, -1, -1, null);
                    System.out.println("Cleaned up and aborted crashed Transaction " + txnId);
                }
            }
            
            System.out.println("WAL Recovery Complete!");
            System.out.println(" -> REDO: Applied " + replayedCount + " ops (Patched " + physicalRedoCount + " stale pages).");
            System.out.println(" -> UNDO: Reversed " + undoneCount + " uncommitted ops.");
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to recover from WAL", e);
        }
    }

    public void performCheckpoint() throws Exception {
        System.out.println("Starting Checkpoint...");
        heapBufferPool.flushAllPages();
        indexBufferPool.flushAllPages();
        long currentOffset = logManager.getCurrentOffset();
        checkpointManager.writeCheckpoint(currentOffset);
        System.out.println("Checkpoint complete at log offset: " + currentOffset);
    }

    public Transaction begin() {
        return txnManager.begin();
    }

    public void commit(Transaction txn) throws Exception {
        txnManager.commit(txn);
        lockManager.releaseAllLocks(txn);
    }

    public void rollback(Transaction txn) throws Exception {
        txnManager.abort(txn);
        lockManager.releaseAllLocks(txn);
    }

    public void insert(Transaction txn, K key, byte[] data) throws Exception {
        int intKey = (Integer) key;
        if (!lockManager.acquireExclusiveLock(txn, intKey)) {
            throw new RuntimeException("Deadlock/Timeout acquiring Exclusive Lock on key: " + intKey);
        }
        RID rid = dataStore.insertRecord(txn, intKey, data);
        btreeStore.insert(intKey, rid);
    }
    
    public boolean update(Transaction txn, K key, byte[] data) throws Exception {
        int intKey = (Integer) key;
        if (!lockManager.acquireExclusiveLock(txn, intKey)) {
            throw new RuntimeException("Deadlock/Timeout acquiring Exclusive Lock on key: " + intKey);
        }
        RID existingRid = btreeStore.search(intKey);
        if (existingRid == null) return false; 
        
        dataStore.deleteRecord(txn, intKey, existingRid);
        insert(txn, key, data);
        return true;
    }

    public boolean delete(Transaction txn, K key) throws Exception {
        int intKey = (Integer) key;
        if (!lockManager.acquireExclusiveLock(txn, intKey)) {
            throw new RuntimeException("Deadlock/Timeout acquiring Exclusive Lock on key: " + intKey);
        }
        RID existingRid = btreeStore.search(intKey);
        if (existingRid == null) return false;

        dataStore.deleteRecord(txn, intKey, existingRid);
        return true;
    }

    public byte[] read(Transaction txn, K key) { 
        int intKey = (Integer) key;
        if (!lockManager.acquireSharedLock(txn, intKey)) {
            throw new RuntimeException("Deadlock/Timeout acquiring Shared Lock on key: " + intKey);
        }
        RID rid = btreeStore.search(intKey);
        if (rid == null) return null; 
        return dataStore.getRecord(rid);
    }

    @Override
    public void close() throws Exception {
        if (heapBufferPool != null) heapBufferPool.flushAllPages();
        if (indexBufferPool != null) indexBufferPool.flushAllPages();
        if (heapDiskManager != null) heapDiskManager.close();
        if (indexDiskManager != null) indexDiskManager.close();
        if (logManager != null) logManager.close();
    }
}