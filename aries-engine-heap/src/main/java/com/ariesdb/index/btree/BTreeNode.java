package com.ariesdb.index.btree;

import java.util.ArrayList;
import java.util.List;

public abstract class BTreeNode<K extends Comparable<K>> {
    private boolean isLeaf;
    private List<K> keys;

    public BTreeNode(boolean isLeaf) {
        this.isLeaf = isLeaf;
        this.keys = new ArrayList<>();
    }

    public boolean isLeaf() {
        return isLeaf;
    }
    
    public void setLeaf(boolean leaf) {
        this.isLeaf = leaf;
    }

    public List<K> getKeys() {
        return keys;
    }
}