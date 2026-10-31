package com.ariesdb.transaction;

import com.ariesdb.common.utils.RID;
import com.ariesdb.engine.AriesEngine;
import com.ariesdb.catalog.Table;
import com.ariesdb.wal.LogManager;
import com.ariesdb.wal.LogRecord;
import com.ariesdb.wal.LogType;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class TransactionManager {
    
    private final LogManager logManager;
    @SuppressWarnings("rawtypes")
    private final AriesEngine engine; // FIXED: Removed <Integer> generic constraint
    private final AtomicLong nextTxnId = new AtomicLong(1);

    @SuppressWarnings("rawtypes")
    public TransactionManager(LogManager logManager, AriesEngine engine) { // FIXED: Removed <Integer> generic constraint
        this.logManager = logManager;
        this.engine = engine;
    }

    public Transaction begin() {
        Transaction txn = new Transaction(nextTxnId.getAndIncrement());
        txn.setState(TransactionState.ACTIVE);
        return txn;
    }

    public void commit(Transaction txn) throws Exception {
        if (txn.getState() != TransactionState.ACTIVE) return;
        
        logManager.appendLog(txn.getTxnId(), LogType.COMMIT, "", -1, -1, -1, null);
        txn.setState(TransactionState.COMMITTED);
    }

    public void abort(Transaction txn) throws Exception {
        if (txn.getState() != TransactionState.ACTIVE) return;

        List<LogRecord> logs = txn.getLogRecords();
        
        for (int i = logs.size() - 1; i >= 0; i--) {
            LogRecord record = logs.get(i);
            
            String tableName = record.getTableName() != null ? record.getTableName() : "default";
            Table table = engine.getTable(tableName);
            
            if (record.getType() == LogType.INSERT) {
                table.getDataStore().undoInsert(record.getPageId(), record.getSlotId());
            } 
            else if (record.getType() == LogType.DELETE || record.getType() == LogType.UPDATE) {
                Transaction dummyTxn = new Transaction(txn.getTxnId());
                RID newRid = table.getDataStore().insertRecord(dummyTxn, record.getKey(), record.getData());
                table.getBtreeStore().insert(record.getKey(), newRid);
            }
        }
        
        logManager.appendLog(txn.getTxnId(), LogType.ABORT, "", -1, -1, -1, null);
        txn.setState(TransactionState.ABORTED);
    }
}