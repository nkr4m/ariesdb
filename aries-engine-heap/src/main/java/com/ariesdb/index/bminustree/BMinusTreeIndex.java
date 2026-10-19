package com.ariesdb.index.bminustree;

import com.ariesdb.common.utils.RID;
import com.ariesdb.index.StorageIndex;

public abstract class BMinusTreeIndex<K extends Comparable<K>> implements StorageIndex<K> {
    protected int degree; 
    protected BMinusTreeNode<K> root;

    public BMinusTreeIndex(int degree) {
        if (degree < 2) {
            throw new IllegalArgumentException("Degree must be at least 2");
        }
        this.degree = degree;
    }

    public BMinusTreeNode<K> getRoot() {
        return root;
    }

    @Override
    public abstract void insert(K key, RID rid);

    @Override
    public abstract RID search(K key);

    @Override
    public abstract void delete(K key);
}