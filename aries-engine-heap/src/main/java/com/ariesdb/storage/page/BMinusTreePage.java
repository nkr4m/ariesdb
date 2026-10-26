package com.ariesdb.storage.page;

import com.ariesdb.common.utils.RID;
import java.nio.ByteBuffer;

/*
 * 
 * ===================================================================
                       B-TREE PAGE LAYOUT (4096 Bytes)
===================================================================

+-----------------------------------------------------------------+ <-- Offset 0
|                       HEADER (20 Bytes)                         |
|-----------------------------------------------------------------|
| [0-3]   isLeaf       (Int: 1 = True, 0 = False)                 |
| [4-7]   keyCount     (Int: Current number of keys, max 250)     |
| [8-11]  parentPageId (Int: Pointer to parent node page)         |
| [12-19] pageLsn      (Long: Log Sequence Number for ARIES WAL)  |
+-----------------------------------------------------------------+ <-- Offset 20
|                  CHILDREN POINTERS (1004 Bytes)                 |
|-----------------------------------------------------------------|
| [20-23]     Child Page ID 0   (Int)                             |
| [24-27]     Child Page ID 1   (Int)                             |
| ...                                                             |
| [1020-1023] Child Page ID 250 (Int)                             |
+-----------------------------------------------------------------+ <-- Offset 1024
|                         KEYS (1000 Bytes)                       |
|-----------------------------------------------------------------|
| [1024-1027] Key 0   (Int)                                       |
| [1028-1031] Key 1   (Int)                                       |
| ...                                                             |
| [2020-2023] Key 249 (Int)                                       |
+-----------------------------------------------------------------+ <-- Offset 2024
|                     VALUES / RIDs (2000 Bytes)                  |
|-----------------------------------------------------------------|
| [2024-2031] RID 0   [PageID (4 bytes) + SlotID (4 bytes)]       |
| [2032-2039] RID 1   [PageID (4 bytes) + SlotID (4 bytes)]       |
| ...                                                             |
| [4016-4023] RID 249 [PageID (4 bytes) + SlotID (4 bytes)]       |
+-----------------------------------------------------------------+ <-- Offset 4024
|                     UNUSED PADDING (72 Bytes)                   |
|-----------------------------------------------------------------|
| Empty space required to round out the exact 4096-byte frame.    |
+-----------------------------------------------------------------+ <-- Offset 4096
 * 
 */

/**
 * Maps a generic B-Minus Tree Node onto a 4KB physical disk page.
 */
public class BMinusTreePage {
    private static final int HEADER_SIZE = 20;
    
    // Array start offsets mapped precisely to the 4096-byte frame
    private static final int CHILDREN_OFFSET = HEADER_SIZE;
    private static final int KEYS_OFFSET = CHILDREN_OFFSET + (251 * 4); // 1024
    private static final int VALUES_OFFSET = KEYS_OFFSET + (250 * 4);   // 2024

    public static final int MAX_KEYS = 250; 

    private final Page page;
    private final ByteBuffer buffer;

    public BMinusTreePage(Page page) {
        this.page = page;
        this.buffer = page.getData();
    }

    /**
     * Formats a brand new B-Tree node page.
     */
    public void init(boolean isLeaf, int parentPageId) {
        buffer.putInt(0, isLeaf ? 1 : 0);
        buffer.putInt(4, 0); // keyCount = 0
        buffer.putInt(8, parentPageId);
        buffer.putLong(12, 0L); // PageLSN for ARIES WAL
    }

    // --- HEADER GETTERS & SETTERS ---

    public boolean isLeaf() {
        return buffer.getInt(0) == 1;
    }

    public void setLeaf(boolean isLeaf) {
        buffer.putInt(0, isLeaf ? 1 : 0);
    }

    public int getKeyCount() {
        return buffer.getInt(4);
    }

    public void setKeyCount(int count) {
        buffer.putInt(4, count);
    }

    public int getParentPageId() {
        return buffer.getInt(8);
    }

    public void setParentPageId(int parentPageId) {
        buffer.putInt(8, parentPageId);
    }

    public long getPageLsn() {
        return buffer.getLong(12);
    }

    public void setPageLsn(long lsn) {
        buffer.putLong(12, lsn);
    }

    // --- ARRAY GETTERS & SETTERS ---

    public int getKey(int index) {
        return buffer.getInt(KEYS_OFFSET + (index * 4));
    }

    public void setKey(int index, int key) {
        buffer.putInt(KEYS_OFFSET + (index * 4), key);
    }

    public RID getValue(int index) {
        int offset = VALUES_OFFSET + (index * 8);
        int pageId = buffer.getInt(offset);
        int slotId = buffer.getInt(offset + 4);
        return new RID(pageId, slotId);
    }

    public void setValue(int index, RID rid) {
        int offset = VALUES_OFFSET + (index * 8);
        buffer.putInt(offset, rid.getPageId());
        buffer.putInt(offset + 4, rid.getSlotId());
    }

    public int getChildPageId(int index) {
        return buffer.getInt(CHILDREN_OFFSET + (index * 4));
    }

    public void setChildPageId(int index, int childPageId) {
        buffer.putInt(CHILDREN_OFFSET + (index * 4), childPageId);
    }

    // --- UTILITY METHODS ---

    /**
     * Shifts keys and values to the right to make room for a new insertion.
     */
    public void insertAt(int index, int key, RID value) {
        int count = getKeyCount();
        
        // Shift keys, values, and children right
        for (int i = count; i > index; i--) {
            setKey(i, getKey(i - 1));
            setValue(i, getValue(i - 1));
            setChildPageId(i + 1, getChildPageId(i));
        }
        
        // Insert new key and value
        setKey(index, key);
        setValue(index, value);
        setKeyCount(count + 1);
    }
}