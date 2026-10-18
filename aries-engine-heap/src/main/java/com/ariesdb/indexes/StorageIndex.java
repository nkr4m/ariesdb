package com.ariesdb.indexes;


import com.ariesdb.storage.RID;

/**
 * The core contract for any index structure in AriesDB.
 */
public interface StorageIndex<K extends Comparable<K>> {
    void insert(K key, RID rid);
    RID search(K key);
    void delete(K key);
}
