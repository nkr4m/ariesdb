package com.ariesdb.engine;

import com.ariesdb.catalog.Table;
import com.ariesdb.common.utils.RID;
import com.ariesdb.storage.page.Page;
import com.ariesdb.storage.page.SlottedPage;
import com.ariesdb.transaction.LockManager;
import com.ariesdb.transaction.Transaction;
import com.ariesdb.transaction.TransactionManager;
import com.ariesdb.wal.CheckpointManager;
import com.ariesdb.wal.LogManager;
import com.ariesdb.wal.LogType;
import com.ariesdb.wal.LogRecord;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

public class AriesEngine<K extends Comparable<K>> implements AutoCloseable {
    
    private final String dbDir;
    private final LogManager logManager;
    private final CheckpointManager checkpointManager;
    private final TransactionManager txnManager;
    private final LockManager lockManager;
    
    // THE POSTGRES UPGRADE: We now hold a Map of isolated tables!
    private final Map<String, Table> tables;

    public AriesEngine(String dbDir, int btreeDegree) throws Exception {
        this.dbDir = dbDir;
        File dir = new File(dbDir);
        if (!dir.exists()) dir.mkdirs();
        
        this.tables = new ConcurrentHashMap<>();
        this.logManager = new LogManager(dbDir);
        this.checkpointManager = new CheckpointManager(dbDir);
        this.lockManager = new LockManager();
        
        // TransactionManager takes 'this' so it can route aborts dynamically
        this.txnManager = new TransactionManager(logManager, this);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { close(); } catch (Exception e) { e.printStackTrace(); }
        }));

        // 1. Mount the core system tables and a default table
        createTable("sys_tables");
        createTable("sys_columns");
        createTable("default");

        // 2. Perform Crash Recovery across ALL tables
        recoverFromWAL();
    }

    public void createTable(String tableName) throws Exception {
        if (!tables.containsKey(tableName)) {
            Table table = new Table(dbDir, tableName, logManager);
            tables.put(tableName, table);
        }
    }

    public Table getTable(String tableName) {
        Table table = tables.get(tableName);
        if (table == null) throw new IllegalArgumentException("Table does not exist: " + tableName);
        return table;
    }

    private void recoverFromWAL() {
        long startOffset = checkpointManager.readCheckpoint();
        System.out.println("Starting WAL Crash Recovery from offset " + startOffset + "...");
        
        int replayedCount = 0, physicalRedoCount = 0, undoneCount = 0;

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

                // ROUTE REDO: Find the specific table for this log
                String tableName = record.getTableName();
                if (tableName == null || tableName.isEmpty()) tableName = "default";
                createTable(tableName); 
                Table table = getTable(tableName);

                int keyVal = record.getKey();
                RID rid = new RID(record.getPageId(), record.getSlotId());
                Page page = table.getHeapBufferPool().fetchPage(record.getPageId());
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
                    table.getHeapBufferPool().unpinPage(record.getPageId(), isStale);
                }

                if (record.getType() == LogType.INSERT || record.getType() == LogType.UPDATE) {
                    table.getBtreeStore().insert(keyVal, rid);
                    replayedCount++;
                } 
            }
            
            // ROUTE UNDO
            if (!loserTxns.isEmpty()) {
                System.out.println("Found " + loserTxns.size() + " crashed active transaction(s). Starting UNDO phase...");
                for (int i = pendingLogs.size() - 1; i >= 0; i--) {
                    LogRecord record = pendingLogs.get(i);
                    if (loserTxns.contains(record.getTxnId())) {
                        
                        String tableName = record.getTableName() != null ? record.getTableName() : "default";
                        Table table = getTable(tableName);

                        if (record.getType() == LogType.INSERT) {
                            table.getDataStore().undoInsert(record.getPageId(), record.getSlotId());
                            undoneCount++;
                        } else if (record.getType() == LogType.DELETE || record.getType() == LogType.UPDATE) {
                            Transaction dummyTxn = new Transaction(record.getTxnId());
                            RID newRid = table.getDataStore().insertRecord(dummyTxn, record.getKey(), record.getData());
                            table.getBtreeStore().insert(record.getKey(), newRid);
                            undoneCount++;
                        }
                    }
                }
                
                for (Long txnId : loserTxns) {
                    logManager.appendLog(txnId, LogType.ABORT, "", -1, -1, -1, null);
                }
            }
            System.out.println("WAL Recovery Complete! REDO: " + replayedCount + ", UNDO: " + undoneCount);
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to recover from WAL", e);
        }
    }

    public void performCheckpoint() throws Exception {
        // FLUSH ALL TABLES!
        for (Table table : tables.values()) {
            table.getHeapBufferPool().flushAllPages();
            table.getIndexBufferPool().flushAllPages();
        }
        long currentOffset = logManager.getCurrentOffset();
        checkpointManager.writeCheckpoint(currentOffset);
    }

    public Transaction begin() { return txnManager.begin(); }
    
    public void commit(Transaction txn) throws Exception {
        txnManager.commit(txn);
        lockManager.releaseAllLocks(txn);
    }
    
    public void rollback(Transaction txn) throws Exception {
        txnManager.abort(txn);
        lockManager.releaseAllLocks(txn);
    }

    // =====================================================================================
    // TABLE-AWARE CRUD OPERATIONS (String TableName + Integer Key)
    // =====================================================================================
    
    public void insert(Transaction txn, String tableName, K key, byte[] data) throws Exception {
        int intKey = (Integer) key;
        
        // COMPOSITE LOCK KEY!
        String lockKey = tableName + ":" + intKey;
        if (!lockManager.acquireExclusiveLock(txn, lockKey)) throw new RuntimeException("Deadlock: " + lockKey);
        
        Table table = getTable(tableName);
        RID rid = table.getDataStore().insertRecord(txn, intKey, data);
        table.getBtreeStore().insert(intKey, rid);
    }
    
    public byte[] read(Transaction txn, String tableName, K key) { 
        int intKey = (Integer) key;
        
        String lockKey = tableName + ":" + intKey;
        if (!lockManager.acquireSharedLock(txn, lockKey)) throw new RuntimeException("Deadlock: " + lockKey);
        
        Table table = getTable(tableName);
        RID rid = table.getBtreeStore().search(intKey);
        if (rid == null) return null; 
        return table.getDataStore().getRecord(rid);
    }

    public boolean update(Transaction txn, String tableName, K key, byte[] data) throws Exception {
        int intKey = (Integer) key;
        
        String lockKey = tableName + ":" + intKey;
        if (!lockManager.acquireExclusiveLock(txn, lockKey)) throw new RuntimeException("Deadlock: " + lockKey);
        
        Table table = getTable(tableName);
        RID existingRid = table.getBtreeStore().search(intKey);
        if (existingRid == null) return false; 
        
        table.getDataStore().deleteRecord(txn, intKey, existingRid);
        insert(txn, tableName, key, data);
        return true;
    }

    public boolean delete(Transaction txn, String tableName, K key) throws Exception {
        int intKey = (Integer) key;
        
        String lockKey = tableName + ":" + intKey;
        if (!lockManager.acquireExclusiveLock(txn, lockKey)) throw new RuntimeException("Deadlock: " + lockKey);
        
        Table table = getTable(tableName);
        RID existingRid = table.getBtreeStore().search(intKey);
        if (existingRid == null) return false;

        table.getDataStore().deleteRecord(txn, intKey, existingRid);
        return true;
    }

    // =====================================================================================
    // OVERLOADED FALLBACKS (Routes old CLI tests to "default" table)
    // =====================================================================================
    public void insert(Transaction txn, K key, byte[] data) throws Exception { insert(txn, "default", key, data); }
    public byte[] read(Transaction txn, K key) { return read(txn, "default", key); }
    public boolean update(Transaction txn, K key, byte[] data) throws Exception { return update(txn, "default", key, data); }
    public boolean delete(Transaction txn, K key) throws Exception { return delete(txn, "default", key); }

    @Override
    public void close() throws Exception {
        for (Table table : tables.values()) table.close();
        if (logManager != null) logManager.close();
    }
}