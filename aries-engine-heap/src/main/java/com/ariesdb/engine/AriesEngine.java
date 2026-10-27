package com.ariesdb.engine;

import com.ariesdb.buffer.BufferPoolManager;
import com.ariesdb.buffer.replacer.ClockReplacer;
import com.ariesdb.common.utils.RID;
import com.ariesdb.storage.data.DiskDataStore;
import com.ariesdb.storage.disk.DiskManager;
import com.ariesdb.storage.index.DiskBMinusTreeStore;
import com.ariesdb.storage.page.Page;
import com.ariesdb.storage.page.SlottedPage;
import com.ariesdb.transaction.Transaction;
import com.ariesdb.transaction.TransactionManager;
import com.ariesdb.wal.CheckpointManager;
import com.ariesdb.wal.LogManager;
import com.ariesdb.wal.LogType;
import com.ariesdb.wal.LogRecord;

import java.nio.ByteBuffer;
import java.util.List;

public class AriesEngine<K extends Comparable<K>> implements AutoCloseable {
    
    // --- STORAGE MANAGERS ---
    private final DiskManager heapDiskManager;
    private final DiskManager indexDiskManager;
    private final BufferPoolManager heapBufferPool;
    private final BufferPoolManager indexBufferPool;
    
    // --- HIGH LEVEL STORES ---
    private final DiskDataStore dataStore;
    private final DiskBMinusTreeStore btreeStore;

    // --- ARIES RECOVERY & TXN ---
    private final LogManager logManager;
    private final CheckpointManager checkpointManager;
    private final TransactionManager txnManager;

    public AriesEngine(DiskManager heapDiskManager, DiskManager indexDiskManager, String dbFilename, int btreeDegree) throws Exception {
        this.heapDiskManager = heapDiskManager;
        this.indexDiskManager = indexDiskManager;
        
        // Separate buffer pools to prevent Page ID collisions
        this.heapBufferPool = new BufferPoolManager(50, heapDiskManager, new ClockReplacer(50));
        this.indexBufferPool = new BufferPoolManager(50, indexDiskManager, new ClockReplacer(50));
        
        this.logManager = new LogManager(dbFilename);
        this.checkpointManager = new CheckpointManager(dbFilename);
        
        // B-Tree Root is permanently Page 0 in the .idx file
        this.btreeStore = new DiskBMinusTreeStore(indexBufferPool, 0); 

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }));

        int heapPages = heapDiskManager.getTotalPages();
        int indexPages = indexDiskManager.getTotalPages();
        
        if (heapPages == 0 || indexPages == 0) {
            // BRAND NEW DATABASE
            btreeStore.createNewTree(); 
            this.dataStore = new DiskDataStore(heapBufferPool, logManager, 0);
        } else {
            // EXISTING DATABASE - Instant Startup
            this.dataStore = new DiskDataStore(heapBufferPool, logManager, heapPages - 1);
            recoverFromWAL();
        }

        // Initialize TransactionManager after stores are successfully created
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
            
            // Track transactions that were active when the server crashed
            java.util.Set<Long> loserTxns = new java.util.HashSet<>();

            // ==========================================
            // PHASE 1: REDO (Scan Forward)
            // ==========================================
            for (LogRecord record : pendingLogs) {
                
                // Keep track of which transactions finished
                if (record.getType() == LogType.COMMIT || record.getType() == LogType.ABORT) {
                    loserTxns.remove(record.getTxnId());
                    continue;
                } else {
                    loserTxns.add(record.getTxnId());
                }

                int keyVal = record.getKey();
                RID rid = new RID(record.getPageId(), record.getSlotId());

                // Heap recovery: Physically apply changes to the SlottedPage
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

                // Index recovery: Re-insert into the B-Tree
                if (record.getType() == LogType.INSERT || record.getType() == LogType.UPDATE) {
                    btreeStore.insert(keyVal, rid);
                    replayedCount++;
                } 
            }
            
            // ==========================================
            // PHASE 2: UNDO (Scan Backward LIFO)
            // ==========================================
            if (!loserTxns.isEmpty()) {
                System.out.println("Found " + loserTxns.size() + " crashed active transaction(s). Starting UNDO phase...");
                
                // Iterate backward through the logs
                for (int i = pendingLogs.size() - 1; i >= 0; i--) {
                    LogRecord record = pendingLogs.get(i);
                    
                    // Only undo operations belonging to Loser Transactions
                    if (loserTxns.contains(record.getTxnId())) {
                        
                        if (record.getType() == LogType.INSERT) {
                            dataStore.undoInsert(record.getPageId(), record.getSlotId());
                            undoneCount++;
                            
                        } else if (record.getType() == LogType.DELETE || record.getType() == LogType.UPDATE) {
                            // Create a dummy transaction just to log the undo operations
                            Transaction dummyTxn = new Transaction(record.getTxnId());
                            RID newRid = dataStore.insertRecord(dummyTxn, record.getKey(), record.getData());
                            btreeStore.insert(record.getKey(), newRid);
                            undoneCount++;
                        }
                    }
                }
                
                // Write final ABORT logs so we never undo these again on future reboots
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

    // --- TRANSACTION API ---

    public Transaction begin() {
        return txnManager.begin();
    }

    public void commit(Transaction txn) throws Exception {
        txnManager.commit(txn);
    }

    public void rollback(Transaction txn) throws Exception {
        txnManager.abort(txn);
    }

    // --- CRUD API ---

    public void insert(Transaction txn, K key, byte[] data) throws Exception {
        int intKey = (Integer) key;
        
        // 1. Store the raw bytes in the heap
        RID rid = dataStore.insertRecord(txn, intKey, data);
        
        // 2. Map the Key to the RID in the B-Tree
        btreeStore.insert(intKey, rid);
    }
    
    public boolean update(Transaction txn, K key, byte[] data) throws Exception {
        int intKey = (Integer) key;
        RID existingRid = btreeStore.search(intKey);
        if (existingRid == null) return false; 
        
        // Out-of-place update logs as a DELETE of old, INSERT of new internally
        dataStore.deleteRecord(txn, intKey, existingRid);
        insert(txn, key, data);
        return true;
    }

    public boolean delete(Transaction txn, K key) throws Exception {
        int intKey = (Integer) key;
        RID existingRid = btreeStore.search(intKey);
        if (existingRid == null) return false;

        // Mark the slot in the heap as deleted (Tombstoned)
        dataStore.deleteRecord(txn, intKey, existingRid);
        return true;
    }

    public byte[] read(K key) { // Read doesn't need txn yet until we add 2PL locks
        // 1. Find the RID in the B-Tree
        RID rid = btreeStore.search((Integer) key);
        if (rid == null) return null; 
        
        // 2. Fetch the raw bytes from the heap
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