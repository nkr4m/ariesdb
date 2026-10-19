package com.ariesdb.index.btree;

import com.ariesdb.common.utils.RID;

import java.util.ArrayList;
import java.util.List;

public class BTree<K extends Comparable<K>> extends BTreeIndex<K> {

    private static class Node<K extends Comparable<K>> extends BTreeNode<K> {
        List<RID> rids;
        List<Boolean> isTombstone;
        List<Node<K>> children;

        public Node(boolean isLeaf) {
            super(isLeaf);
            this.rids = new ArrayList<>();
            this.isTombstone = new ArrayList<>();
            this.children = new ArrayList<>();
        }
    }

    public BTree(int degree) {
        super(degree);
        this.root = new Node<>(true);
    }

    @Override
    public RID search(K key) {
        return searchNode((Node<K>) root, key);
    }

    private RID searchNode(Node<K> node, K key) {
        int i = 0;
        
        while (i < node.getKeys().size() && key.compareTo(node.getKeys().get(i)) > 0) {
            i++;
        }

        if (i < node.getKeys().size() && key.compareTo(node.getKeys().get(i)) == 0) {
            if (node.isTombstone.get(i)) {
                return null; 
            }
            return node.rids.get(i);
        }

        if (node.isLeaf()) {
            return null;
        }

        return searchNode(node.children.get(i), key);
    }

    @Override
    public void insert(K key, RID rid) {
        Node<K> r = (Node<K>) root;
        
        if (r.getKeys().size() == 2 * degree - 1) {
            Node<K> newRoot = new Node<>(false);
            this.root = newRoot;
            newRoot.children.add(r);
            splitChild(newRoot, 0, r);
            insertNonFull(newRoot, key, rid);
        } else {
            insertNonFull(r, key, rid);
        }
    }

    private void insertNonFull(Node<K> node, K key, RID rid) {
        for (int j = 0; j < node.getKeys().size(); j++) {
            if (key.compareTo(node.getKeys().get(j)) == 0) {
                node.rids.set(j, rid);
                node.isTombstone.set(j, false); 
                return;
            }
        }

        int i = node.getKeys().size() - 1;

        if (node.isLeaf()) {
            node.getKeys().add(null);
            node.rids.add(null);
            node.isTombstone.add(false);

            while (i >= 0 && key.compareTo(node.getKeys().get(i)) < 0) {
                node.getKeys().set(i + 1, node.getKeys().get(i));
                node.rids.set(i + 1, node.rids.get(i));
                node.isTombstone.set(i + 1, node.isTombstone.get(i));
                i--;
            }
            
            node.getKeys().set(i + 1, key);
            node.rids.set(i + 1, rid);
            node.isTombstone.set(i + 1, false);
        } else {
            while (i >= 0 && key.compareTo(node.getKeys().get(i)) < 0) {
                i--;
            }
            i++;

            Node<K> child = node.children.get(i);
            
            if (child.getKeys().size() == 2 * degree - 1) {
                splitChild(node, i, child);
                
                if (key.compareTo(node.getKeys().get(i)) > 0) {
                    i++;
                } else if (key.compareTo(node.getKeys().get(i)) == 0) {
                    node.rids.set(i, rid);
                    node.isTombstone.set(i, false);
                    return;
                }
            }
            
            insertNonFull(node.children.get(i), key, rid);
        }
    }

    private void splitChild(Node<K> parent, int childIndex, Node<K> fullChild) {
        Node<K> newChild = new Node<>(fullChild.isLeaf());
        
        for (int j = 0; j < degree - 1; j++) {
            newChild.getKeys().add(fullChild.getKeys().get(j + degree));
            newChild.rids.add(fullChild.rids.get(j + degree));
            newChild.isTombstone.add(fullChild.isTombstone.get(j + degree));
        }

        if (!fullChild.isLeaf()) {
            for (int j = 0; j < degree; j++) {
                newChild.children.add(fullChild.children.get(j + degree));
            }
        }

        parent.children.add(childIndex + 1, newChild);

        parent.getKeys().add(childIndex, fullChild.getKeys().get(degree - 1));
        parent.rids.add(childIndex, fullChild.rids.get(degree - 1));
        parent.isTombstone.add(childIndex, fullChild.isTombstone.get(degree - 1));

        fullChild.getKeys().subList(degree - 1, fullChild.getKeys().size()).clear();
        fullChild.rids.subList(degree - 1, fullChild.rids.size()).clear();
        fullChild.isTombstone.subList(degree - 1, fullChild.isTombstone.size()).clear();
        if (!fullChild.isLeaf()) {
            fullChild.children.subList(degree, fullChild.children.size()).clear();
        }
    }

    @Override
    public void delete(K key) {
        markTombstone((Node<K>) root, key);
    }

    private boolean markTombstone(Node<K> node, K key) {
        int i = 0;
        
        while (i < node.getKeys().size() && key.compareTo(node.getKeys().get(i)) > 0) {
            i++;
        }

        if (i < node.getKeys().size() && key.compareTo(node.getKeys().get(i)) == 0) {
            if (!node.isTombstone.get(i)) {
                node.isTombstone.set(i, true);
                return true;
            }
            return false;
        }

        if (node.isLeaf()) {
            return false;
        }

        return markTombstone(node.children.get(i), key);
    }
}