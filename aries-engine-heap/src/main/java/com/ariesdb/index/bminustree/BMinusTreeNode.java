package com.ariesdb.index.bminustree;

import com.ariesdb.common.utils.RID;
import java.util.ArrayList;
import java.util.List;

public class BMinusTreeNode<K extends Comparable<K>> {
    // Package-private fields for direct access by BMinusTree
    boolean isLeaf;
    List<K> keys;
    List<RID> rids;
    List<Boolean> isTombstone;
    List<BMinusTreeNode<K>> children;

    public BMinusTreeNode(boolean isLeaf) {
        this.isLeaf = isLeaf;
        this.keys = new ArrayList<>();
        this.rids = new ArrayList<>();
        this.isTombstone = new ArrayList<>();
        this.children = new ArrayList<>();
    }

    public boolean isLeaf() {
        return isLeaf;
    }
    
    public void setLeaf(boolean leaf) {
        this.isLeaf = leaf;
    }
}