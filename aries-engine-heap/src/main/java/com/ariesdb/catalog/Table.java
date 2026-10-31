package com.ariesdb.catalog;

import com.ariesdb.buffer.BufferPoolManager;
import com.ariesdb.buffer.replacer.ClockReplacer;
import com.ariesdb.storage.data.DiskDataStore;
import com.ariesdb.storage.disk.DiskManager;
import com.ariesdb.storage.index.DiskBMinusTreeStore;
import com.ariesdb.wal.LogManager;

public class Table implements AutoCloseable {
    private final String tableName;
    private final DiskManager heapDiskManager;
    private final DiskManager indexDiskManager;
    private final BufferPoolManager heapBufferPool;
    private final BufferPoolManager indexBufferPool;
    
    private final DiskDataStore dataStore;
    private final DiskBMinusTreeStore btreeStore;

    public Table(String directoryPath, String tableName, LogManager logManager) throws Exception {
        this.tableName = tableName;
        
        // 1. Create unique OS files for this specific table (e.g. users.db, users.idx)
        this.heapDiskManager = new DiskManager(directoryPath + "/" + tableName + ".db");
        this.indexDiskManager = new DiskManager(directoryPath + "/" + tableName + ".idx");
        
        // 2. Give this table its own isolated Buffer Pools to prevent cross-contamination
        this.heapBufferPool = new BufferPoolManager(50, heapDiskManager, new ClockReplacer(50));
        this.indexBufferPool = new BufferPoolManager(50, indexDiskManager, new ClockReplacer(50));
        
        this.btreeStore = new DiskBMinusTreeStore(indexBufferPool, 0);

        int heapPages = heapDiskManager.getTotalPages();
        int indexPages = indexDiskManager.getTotalPages();
        
        if (heapPages == 0 || indexPages == 0) {
            btreeStore.createNewTree();
            // We pass the tableName down so DiskDataStore can log it!
            this.dataStore = new DiskDataStore(tableName, heapBufferPool, logManager, 0); 
        } else {
            this.dataStore = new DiskDataStore(tableName, heapBufferPool, logManager, heapPages - 1);
        }
    }

    public String getTableName() { return tableName; }
    public DiskDataStore getDataStore() { return dataStore; }
    public DiskBMinusTreeStore getBtreeStore() { return btreeStore; }
    public BufferPoolManager getHeapBufferPool() { return heapBufferPool; }
    public BufferPoolManager getIndexBufferPool() { return indexBufferPool; }

    @Override
    public void close() throws Exception {
        if (heapBufferPool != null) heapBufferPool.flushAllPages();
        if (indexBufferPool != null) indexBufferPool.flushAllPages();
        if (heapDiskManager != null) heapDiskManager.close();
        if (indexDiskManager != null) indexDiskManager.close();
    }
}