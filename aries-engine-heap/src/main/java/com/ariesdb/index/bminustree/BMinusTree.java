package com.ariesdb.index.bminustree;

import com.ariesdb.common.utils.RID;

public class BMinusTree<K extends Comparable<K>> extends BMinusTreeIndex<K> {

    public BMinusTree(int degree) {
        super(degree);
        this.root = new BMinusTreeNode<>(true);
    }

    // ==========================================
    // 1. SEARCH LOGIC
    // ==========================================
    
    @Override
    public RID search(K key) {
        return searchNode(root, key);
    }

    private RID searchNode(BMinusTreeNode<K> node, K key) {
        int i = 0;
        
        while (i < node.keys.size() && key.compareTo(node.keys.get(i)) > 0) {
            i++;
        }

        if (i < node.keys.size() && key.compareTo(node.keys.get(i)) == 0) {
            if (node.isTombstone.get(i)) {
                return null; 
            }
            return node.rids.get(i);
        }

        if (node.isLeaf) {
            return null;
        }

        return searchNode(node.children.get(i), key);
    }

    // ==========================================
    // 2. INSERTION & SPLITTING LOGIC
    // ==========================================

    @Override
    public void insert(K key, RID rid) {
        BMinusTreeNode<K> r = root;
        
        if (r.keys.size() == 2 * degree - 1) {
            BMinusTreeNode<K> newRoot = new BMinusTreeNode<>(false);
            this.root = newRoot;
            newRoot.children.add(r);
            splitChild(newRoot, 0, r);
            insertNonFull(newRoot, key, rid);
        } else {
            insertNonFull(r, key, rid);
        }
    }

    private void insertNonFull(BMinusTreeNode<K> node, K key, RID rid) {
        for (int j = 0; j < node.keys.size(); j++) {
            if (key.compareTo(node.keys.get(j)) == 0) {
                node.rids.set(j, rid);
                node.isTombstone.set(j, false); 
                return;
            }
        }

        int i = node.keys.size() - 1;

        if (node.isLeaf) {
            node.keys.add(null);
            node.rids.add(null);
            node.isTombstone.add(false);

            while (i >= 0 && key.compareTo(node.keys.get(i)) < 0) {
                node.keys.set(i + 1, node.keys.get(i));
                node.rids.set(i + 1, node.rids.get(i));
                node.isTombstone.set(i + 1, node.isTombstone.get(i));
                i--;
            }
            
            node.keys.set(i + 1, key);
            node.rids.set(i + 1, rid);
            node.isTombstone.set(i + 1, false);
        } else {
            while (i >= 0 && key.compareTo(node.keys.get(i)) < 0) {
                i--;
            }
            i++;

            BMinusTreeNode<K> child = node.children.get(i);
            
            if (child.keys.size() == 2 * degree - 1) {
                splitChild(node, i, child);
                
                if (key.compareTo(node.keys.get(i)) > 0) {
                    i++;
                } else if (key.compareTo(node.keys.get(i)) == 0) {
                    node.rids.set(i, rid);
                    node.isTombstone.set(i, false);
                    return;
                }
            }
            
            insertNonFull(node.children.get(i), key, rid);
        }
    }

    private void splitChild(BMinusTreeNode<K> parent, int childIndex, BMinusTreeNode<K> fullChild) {
        BMinusTreeNode<K> newChild = new BMinusTreeNode<>(fullChild.isLeaf);
        
        for (int j = 0; j < degree - 1; j++) {
            newChild.keys.add(fullChild.keys.get(j + degree));
            newChild.rids.add(fullChild.rids.get(j + degree));
            newChild.isTombstone.add(fullChild.isTombstone.get(j + degree));
        }

        if (!fullChild.isLeaf) {
            for (int j = 0; j < degree; j++) {
                newChild.children.add(fullChild.children.get(j + degree));
            }
        }

        parent.children.add(childIndex + 1, newChild);

        parent.keys.add(childIndex, fullChild.keys.get(degree - 1));
        parent.rids.add(childIndex, fullChild.rids.get(degree - 1));
        parent.isTombstone.add(childIndex, fullChild.isTombstone.get(degree - 1));

        fullChild.keys.subList(degree - 1, fullChild.keys.size()).clear();
        fullChild.rids.subList(degree - 1, fullChild.rids.size()).clear();
        fullChild.isTombstone.subList(degree - 1, fullChild.isTombstone.size()).clear();
        if (!fullChild.isLeaf) {
            fullChild.children.subList(degree, fullChild.children.size()).clear();
        }
    }

    // ==========================================
    // 3. DELETION LOGIC (LAZY / TOMBSTONE)
    // ==========================================

    @Override
    public void delete(K key) {
        markTombstone(root, key);
    }

    private boolean markTombstone(BMinusTreeNode<K> node, K key) {
        int i = 0;
        
        while (i < node.keys.size() && key.compareTo(node.keys.get(i)) > 0) {
            i++;
        }

        if (i < node.keys.size() && key.compareTo(node.keys.get(i)) == 0) {
            if (!node.isTombstone.get(i)) {
                node.isTombstone.set(i, true);
                return true;
            }
            return false;
        }

        if (node.isLeaf) {
            return false;
        }

        return markTombstone(node.children.get(i), key);
    }
}