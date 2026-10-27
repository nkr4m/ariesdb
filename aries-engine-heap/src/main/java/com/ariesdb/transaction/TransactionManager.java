package com.ariesdb.transaction;

import com.ariesdb.common.utils.RID;
import com.ariesdb.storage.data.DiskDataStore;
import com.ariesdb.storage.index.DiskBMinusTreeStore;
import com.ariesdb.wal.LogManager;
import com.ariesdb.wal.LogRecord;
import com.ariesdb.wal.LogType;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class TransactionManager {
    
    private final LogManager logManager;
    private final DiskDataStore dataStore;
    private final DiskBMinusTreeStore btreeStore;
    private final AtomicLong nextTxnId = new AtomicLong(1);

    public TransactionManager(LogManager logManager, DiskDataStore dataStore, DiskBMinusTreeStore btreeStore) {
        this.logManager = logManager;
        this.dataStore = dataStore;
        this.btreeStore = btreeStore;
    }

    public Transaction begin() {
        Transaction txn = new Transaction(nextTxnId.getAndIncrement());
        txn.setState(TransactionState.ACTIVE);
        return txn;
    }

    public void commit(Transaction txn) throws Exception {
        if (txn.getState() != TransactionState.ACTIVE) return;
        
        logManager.appendLog(txn.getTxnId(), LogType.COMMIT, -1, -1, -1, null);
        txn.setState(TransactionState.COMMITTED);
    }

    public void abort(Transaction txn) throws Exception {
        if (txn.getState() != TransactionState.ACTIVE) return;

        List<LogRecord> logs = txn.getLogRecords();
        
        // In-Memory LIFO Reversal for Rollback
        for (int i = logs.size() - 1; i >= 0; i--) {
            LogRecord record = logs.get(i);
            
            if (record.getType() == LogType.INSERT) {
                dataStore.undoInsert(record.getPageId(), record.getSlotId());
            } 
            else if (record.getType() == LogType.DELETE || record.getType() == LogType.UPDATE) {
                // Re-insert the old data
                Transaction dummyTxn = new Transaction(txn.getTxnId());
                RID newRid = dataStore.insertRecord(dummyTxn, record.getKey(), record.getData());
                btreeStore.insert(record.getKey(), newRid);
            }
        }
        
        logManager.appendLog(txn.getTxnId(), LogType.ABORT, -1, -1, -1, null);
        txn.setState(TransactionState.ABORTED);
    }
}