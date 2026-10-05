package com.ariesdb.storage;

import java.nio.ByteBuffer;

public class SlottedPage {
    public static final int PAGE_SIZE = 4096;
    private static final int HEADER_SIZE = 8; // 4 bytes for pageId, 4 for slotCount
    private static final int SLOT_SIZE = 8;   // 4 bytes for offset, 4 for length

    private int pageId;
    private int slotCount;
    private int freeSpacePointer;
    private byte[] data;
    private ByteBuffer buffer;

    // Create a brand new page
    public SlottedPage(int pageId) {
        this.pageId = pageId;
        this.slotCount = 0;
        this.freeSpacePointer = PAGE_SIZE; // Data starts at the very end and grows backwards
        this.data = new byte[PAGE_SIZE];
        this.buffer = ByteBuffer.wrap(data);
        
        // Write the header
        buffer.putInt(0, pageId);
        buffer.putInt(4, slotCount);
    }

    // Load an existing page from disk
    public SlottedPage(byte[] rawBytes) {
        this.data = rawBytes;
        this.buffer = ByteBuffer.wrap(data);
        this.pageId = buffer.getInt(0);
        this.slotCount = buffer.getInt(4);
        
        // Calculate where the free space starts by looking at the last inserted slot
        if (slotCount == 0) {
            this.freeSpacePointer = PAGE_SIZE;
        } else {
            int lastSlotOffset = HEADER_SIZE + ((slotCount - 1) * SLOT_SIZE);
            int dataOffset = buffer.getInt(lastSlotOffset);
            this.freeSpacePointer = dataOffset;
        }
    }

    /**
     * Inserts raw bytes into the page.
     * @return The slotId, or -1 if the page is full.
     */
    public int insert(byte[] record) {
        int spaceNeeded = record.length + SLOT_SIZE;
        int currentHeaderEnd = HEADER_SIZE + (slotCount * SLOT_SIZE);
        
        // Check if we have enough room between the directory and the data
        if (currentHeaderEnd + spaceNeeded > freeSpacePointer) {
            return -1; // Page is full! 
        }

        // 1. Move the free space pointer backward
        freeSpacePointer -= record.length;

        // 2. Write the actual data at the end of the page
        System.arraycopy(record, 0, data, freeSpacePointer, record.length);

        // 3. Write the slot directory entry (offset and length)
        buffer.putInt(currentHeaderEnd, freeSpacePointer);
        buffer.putInt(currentHeaderEnd + 4, record.length);

        // 4. Update the header
        int assignedSlotId = slotCount;
        slotCount++;
        buffer.putInt(4, slotCount);

        return assignedSlotId;
    }

    /**
     * Reads a record instantly using just its Slot ID.
     */
    public byte[] read(int slotId) {
        if (slotId >= slotCount) {
            throw new IllegalArgumentException("Invalid Slot ID");
        }

        int slotLocation = HEADER_SIZE + (slotId * SLOT_SIZE);
        int offset = buffer.getInt(slotLocation);
        int length = buffer.getInt(slotLocation + 4);

        byte[] record = new byte[length];
        System.arraycopy(data, offset, record, 0, length);
        return record;
    }

    public byte[] getPageBytes() {
        return data;
    }
    
    public int getPageId() {
        return pageId;
    }
}
