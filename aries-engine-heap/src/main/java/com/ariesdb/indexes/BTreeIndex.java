package com.ariesdb.indexes;


import com.ariesdb.storage.RID;

/**
 * Abstract parent class for B-Tree variants.
 * Holds the degree configuration and the root node.
 */
public abstract class BTreeIndex<K extends Comparable<K>> implements StorageIndex<K> {
    protected int degree; // Minimum degree (t)
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
