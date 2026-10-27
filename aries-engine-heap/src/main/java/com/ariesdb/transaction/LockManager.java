package com.ariesdb.transaction;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class LockManager {
    
    private final ConcurrentHashMap<Integer, ReentrantReadWriteLock> lockTable = new ConcurrentHashMap<>();

    public boolean acquireSharedLock(Transaction txn, int key) {
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

    public boolean acquireExclusiveLock(Transaction txn, int key) {
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
        for (Integer key : txn.getExclusiveLocks()) {
            ReentrantReadWriteLock rwLock = lockTable.get(key);
            if (rwLock != null) rwLock.writeLock().unlock();
        }
        txn.getExclusiveLocks().clear();

        for (Integer key : txn.getSharedLocks()) {
            ReentrantReadWriteLock rwLock = lockTable.get(key);
            if (rwLock != null) rwLock.readLock().unlock();
        }
        txn.getSharedLocks().clear();
    }
}