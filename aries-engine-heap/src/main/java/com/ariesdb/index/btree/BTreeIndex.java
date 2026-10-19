package com.ariesdb.index.btree;

import com.ariesdb.common.utils.RID;
import com.ariesdb.index.StorageIndex;

public abstract class BTreeIndex<K extends Comparable<K>> implements StorageIndex<K> {
    protected int degree; 
    protected BTreeNode<K> root;

    public BTreeIndex(int degree) {
        if (degree < 2) {
            throw new IllegalArgumentException("Degree must be at least 2");
        }
        this.degree = degree;
    }

    public BTreeNode<K> getRoot() {
        return root;
    }

    @Override
    public abstract void insert(K key, RID rid);

    @Override
    public abstract RID search(K key);

    @Override
    public abstract void delete(K key);
}