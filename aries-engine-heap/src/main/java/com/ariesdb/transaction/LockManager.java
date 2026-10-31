package com.ariesdb.transaction;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class LockManager {
    
    // CHANGED: Map key is now a String
    private final ConcurrentHashMap<String, ReentrantReadWriteLock> lockTable = new ConcurrentHashMap<>();

    // CHANGED: Accept String key
    public boolean acquireSharedLock(Transaction txn, String key) {
        if (txn.getSharedLocks().contains(key) || txn.getExclusiveLocks().contains(key)) return true;

        ReentrantReadWriteLock rwLock = lockTable.computeIfAbsent(key, k -> new ReentrantReadWriteLock());
        try {
            if (rwLock.readLock().tryLock(2000, TimeUnit.MILLISECONDS)) {
                txn.addSharedLock(key);
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    // CHANGED: Accept String key
    public boolean acquireExclusiveLock(Transaction txn, String key) {
        if (txn.getExclusiveLocks().contains(key)) return true; 

        ReentrantReadWriteLock rwLock = lockTable.computeIfAbsent(key, k -> new ReentrantReadWriteLock());
        try {
            if (rwLock.writeLock().tryLock(2000, TimeUnit.MILLISECONDS)) {
                txn.addExclusiveLock(key);
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false; 
    }

    public void releaseAllLocks(Transaction txn) {
        // CHANGED: Iterate over String keys
        for (String key : txn.getExclusiveLocks()) {
            ReentrantReadWriteLock rwLock = lockTable.get(key);
            if (rwLock != null) rwLock.writeLock().unlock();
        }
        txn.getExclusiveLocks().clear();

        // CHANGED: Iterate over String keys
        for (String key : txn.getSharedLocks()) {
            ReentrantReadWriteLock rwLock = lockTable.get(key);
            if (rwLock != null) rwLock.readLock().unlock();
        }
        txn.getSharedLocks().clear();
    }
}