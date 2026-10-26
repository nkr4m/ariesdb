// src/main/java/com/ariesdb/storage/page/SlottedPage.java
package com.ariesdb.storage.page;

import com.ariesdb.common.config.Constants;
import java.nio.ByteBuffer;

/**
 * Represents the physical layout of a 4KB (4,096 bytes) memory page.
 * The page uses a slotted architecture where the slot directory grows forward 
 * from the top, and the variable-length data grows backward from the bottom.
 * 
 * <pre>
 * [ START OF PAGE - BYTE 0 ]
 * ---------------------------------------------------------
 * | HEADER (16 Bytes)                                     |
 * |-------------------------------------------------------|
 * | Bytes 0-3:  Page ID (e.g., 0)                         |
 * | Bytes 4-7:  Slot Count (e.g., 2 records inserted)     |
 * | Bytes 8-15: PageLSN (Log Sequence Number)             |
 * ---------------------------------------------------------
 * | SLOT DIRECTORY (8 Bytes per slot, growing FORWARD)    |
 * |-------------------------------------------------------|
 * | Bytes 16-23: Slot 0 (Offset: 4091, Length: 5)         |
 * | Bytes 24-31: Slot 1 (Offset: 4085, Length: 6)         |
 * | Bytes 32-39: [ Next slot goes here... ]               |
 * ---------------------------------------------------------
 * |                      ^                                |
 * |                      |                                |
 * |                                                       |
 * |                  FREE SPACE                           |
 * |       (This empty gap shrinks from both sides)        |
 * |                                                       |
 * |                      |                                |
 * |                      v                                |
 * ---------------------------------------------------------
 * | DATA RECORDS (Variable length, growing BACKWARD)      |
 * |-------------------------------------------------------|
 * | Bytes 4085-4090: "Record" (Data for Slot 1)           |
 * | Bytes 4091-4095: "Hello"  (Data for Slot 0)           |
 * ---------------------------------------------------------
 * [ END OF PAGE - BYTE 4096 ]
 * </pre>
 */
public class SlottedPage {
    public static final int PAGE_SIZE = Constants.PAGE_SIZE;
    // --- UPDATED: Header is now 16 bytes to fit the 8-byte LSN ---
    private static final int HEADER_SIZE = 16; 
    private static final int SLOT_SIZE = 8;   // 4 bytes for offset, 4 for length

    private final Page page;
    private final ByteBuffer buffer;

    /**
     * Wraps an existing memory frame (Page) from the Buffer Pool.
     */
    public SlottedPage(Page page) {
        this.page = page;
        this.buffer = page.getData();
    }

    /**
     * Formats a brand new page with an empty header and LSN set to 0.
     */
    public void init() {
        buffer.putInt(0, page.getPageId());
        buffer.putInt(4, 0);   // slotCount = 0
        buffer.putLong(8, 0L); // PageLSN = 0
    }

    // --- NEW: PageLSN Getters and Setters ---
    public long getPageLsn() {
        return buffer.getLong(8);
    }

    public void setPageLsn(long lsn) {
        buffer.putLong(8, lsn);
    }
    // ----------------------------------------

    public int getSlotCount() {
        return buffer.getInt(4);
    }

    private int getFreeSpacePointer() {
        int slotCount = getSlotCount();
        if (slotCount == 0) {
            return PAGE_SIZE; // Data starts at the very end and grows backwards
        }
        // The free space pointer is exactly where the last inserted record begins
        int lastSlotOffset = HEADER_SIZE + ((slotCount - 1) * SLOT_SIZE);
        return buffer.getInt(lastSlotOffset);
    }

    /**
     * Inserts raw bytes into the page.
     * @return The assigned slotId, or -1 if the page is full.
     */
    public int insertRecord(byte[] record) {
        int slotCount = getSlotCount();
        int freeSpacePointer = getFreeSpacePointer();

        int spaceNeeded = record.length + SLOT_SIZE;
        int currentHeaderEnd = HEADER_SIZE + (slotCount * SLOT_SIZE);
        
        // Check if we have enough room between the directory and the data
        if (currentHeaderEnd + spaceNeeded > freeSpacePointer) {
            return -1; // Page is full! 
        }

        // 1. Move the free space pointer backward
        freeSpacePointer -= record.length;

        // 2. Write the actual data directly into the off-heap ByteBuffer
        buffer.position(freeSpacePointer);
        buffer.put(record);

        // 3. Write the slot directory entry (offset and length)
        buffer.putInt(currentHeaderEnd, freeSpacePointer);
        buffer.putInt(currentHeaderEnd + 4, record.length);

        // 4. Update the header's slot count
        buffer.putInt(4, slotCount + 1);

        return slotCount; // This is the assigned slotId
    }

    /**
     * Reads a record instantly using just its Slot ID.
     */
    public byte[] getRecord(int slotId) {
        int slotCount = getSlotCount();
        if (slotId >= slotCount) {
            throw new IllegalArgumentException("Invalid Slot ID: " + slotId);
        }

        int slotLocation = HEADER_SIZE + (slotId * SLOT_SIZE);
        int offset = buffer.getInt(slotLocation);
        int length = buffer.getInt(slotLocation + 4);

        if (length == 0) {
            return null; // Record was deleted/tombstoned
        }

        // Read the exact bytes from the data portion of the buffer
        byte[] record = new byte[length];
        buffer.position(offset);
        buffer.get(record);
        
        return record;
    }
    
    /**
     * Physically marks a record as deleted by setting its length to 0 (Tombstone).
     */
    public boolean deleteRecord(int slotId) {
        int slotCount = getSlotCount();
        if (slotId >= slotCount) {
            return false;
        }

        int slotLocation = HEADER_SIZE + (slotId * SLOT_SIZE);
        
        // Overwrite the length integer (Offset + 4) to 0
        buffer.putInt(slotLocation + 4, 0); 
        return true;
    }
}