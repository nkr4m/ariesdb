package com.ariesdb.indexes;


import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base class for B-Tree nodes.
 * Contains only the routing keys and the leaf flag.
 */
public abstract class BTreeNode<K extends Comparable<K>> {
    protected boolean isLeaf;
    protected List<K> keys;

    public BTreeNode(boolean isLeaf) {
        this.isLeaf = isLeaf;
        this.keys = new ArrayList<>();
    }

    public boolean isLeaf() {
        return isLeaf;
    }
}