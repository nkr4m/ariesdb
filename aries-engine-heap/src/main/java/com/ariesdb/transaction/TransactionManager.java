package com.ariesdb.transaction;

import com.ariesdb.common.utils.RID;
import com.ariesdb.storage.data.DiskDataStore;
import com.ariesdb.storage.index.DiskBMinusTreeStore;
import com.ariesdb.wal.LogManager;
import com.ariesdb.wal.LogType;
import com.ariesdb.wal.LogRecord;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class TransactionManager {
    private final AtomicLong nextTxnId;
    private final ConcurrentHashMap<Long, Transaction> activeTransactions;
    private final LogManager logManager;
    private final DiskDataStore dataStore;
    private final DiskBMinusTreeStore btreeStore;

    public TransactionManager(LogManager logManager, DiskDataStore dataStore, DiskBMinusTreeStore btreeStore) {
        this.logManager = logManager;
        this.dataStore = dataStore;
        this.btreeStore = btreeStore;
        this.nextTxnId = new AtomicLong(1);
        this.activeTransactions = new ConcurrentHashMap<>();
    }

    public Transaction begin() {
        long txnId = nextTxnId.getAndIncrement();
        Transaction txn = new Transaction(txnId);
        activeTransactions.put(txnId, txn);
        return txn;
    }

    public void commit(Transaction txn) throws Exception {
        txn.setState(TransactionState.COMMITTED);
        logManager.appendLog(txn.getTxnId(), LogType.COMMIT, -1, -1, -1, null);
        activeTransactions.remove(txn.getTxnId());
    }

    public void abort(Transaction txn) throws Exception {
        txn.setState(TransactionState.ABORTED);
        
        // Physically UNDO the operations in reverse order
        undoTransaction(txn); 
        
        logManager.appendLog(txn.getTxnId(), LogType.ABORT, -1, -1, -1, null);
        activeTransactions.remove(txn.getTxnId());
    }

    private void undoTransaction(Transaction txn) throws Exception {
        List<LogRecord> records = txn.getLogRecords();
        
        // Iterate backward (LIFO)
        for (int i = records.size() - 1; i >= 0; i--) {
            LogRecord record = records.get(i);
            
            if (record.getType() == LogType.INSERT) {
                System.out.println("UNDO: Removing inserted key " + record.getKey());
                dataStore.undoInsert(record.getPageId(), record.getSlotId());
                
            } else if (record.getType() == LogType.DELETE || record.getType() == LogType.UPDATE) {
                System.out.println("UNDO: Restoring key " + record.getKey());
                // Re-insert the old data physically and update the B-Tree pointer!
                RID newRid = dataStore.insertRecord(txn, record.getKey(), record.getData());
                btreeStore.insert(record.getKey(), newRid);
            }
        }
    }
}