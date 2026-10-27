package com.ariesdb.transaction;

import com.ariesdb.wal.LogRecord;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Transaction {
    private final long txnId;
    
    // --- ARIES RECOVERY TRACKING ---
    private TransactionState state;
    private final List<LogRecord> logRecords;
    private final Set<Integer> modifiedPages;
    
    // --- 2PL LOCK TRACKING ---
    private final Set<Integer> sharedLocks;
    private final Set<Integer> exclusiveLocks;

    public Transaction(long txnId) {
        this.txnId = txnId;
        this.state = TransactionState.ACTIVE;
        this.logRecords = new ArrayList<>();
        this.modifiedPages = new HashSet<>();
        this.sharedLocks = new HashSet<>();
        this.exclusiveLocks = new HashSet<>();
    }

    public long getTxnId() { return txnId; }
    
    public TransactionState getState() { return state; }
    public void setState(TransactionState state) { this.state = state; }

    public void addLogRecord(LogRecord record) { logRecords.add(record); }
    public List<LogRecord> getLogRecords() { return logRecords; }

    public void addModifiedPage(int pageId) { modifiedPages.add(pageId); }
    public Set<Integer> getModifiedPages() { return modifiedPages; }

    public void addSharedLock(int key) { sharedLocks.add(key); }
    public void addExclusiveLock(int key) { exclusiveLocks.add(key); }
    
    public Set<Integer> getSharedLocks() { return sharedLocks; }
    public Set<Integer> getExclusiveLocks() { return exclusiveLocks; }
}