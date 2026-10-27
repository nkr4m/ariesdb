package com.ariesdb.transaction;

import com.ariesdb.wal.LogRecord;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Transaction {
    private final long txnId;
    private TransactionState state;
    private final Set<Integer> modifiedPageIds;
    
    // NEW: In-memory undo list
    private final List<LogRecord> logRecords;

    public Transaction(long txnId) {
        this.txnId = txnId;
        this.state = TransactionState.ACTIVE;
        this.modifiedPageIds = new HashSet<>();
        this.logRecords = new ArrayList<>();
    }

    public long getTxnId() { return txnId; }
    public TransactionState getState() { return state; }
    public void setState(TransactionState state) { this.state = state; }

    public void addModifiedPage(int pageId) { modifiedPageIds.add(pageId); }
    public Set<Integer> getModifiedPageIds() { return modifiedPageIds; }

    public void addLogRecord(LogRecord record) {
        logRecords.add(record);
    }

    public List<LogRecord> getLogRecords() {
        return logRecords;
    }
}