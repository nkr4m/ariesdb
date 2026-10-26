package com.ariesdb.wal;

import java.nio.ByteBuffer;

public class LogRecord {
    private final long lsn;
    private final LogType type;
    private final int pageId;
    private final int slotId;
    private final int key;
    private final byte[] data;

    public LogRecord(long lsn, LogType type, int pageId, int slotId, int key, byte[] data) {
        this.lsn = lsn;
        this.type = type;
        this.pageId = pageId;
        this.slotId = slotId;
        this.key = key;
        this.data = data != null ? data : new byte[0];
    }

    /**
     * Serializes the log record into a byte array for disk writing.
     * Layout: [LSN (8)] [Type (4)] [PageId (4)] [SlotId (4)] [Key (4)] [DataLen (4)] [Data Bytes]
     */
    public byte[] serialize() {
        int dataLen = data.length;
        int totalSize = 8 + 4 + 4 + 4 + 4 + 4 + dataLen;
        
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.putLong(lsn);
        buffer.putInt(type.getId());
        buffer.putInt(pageId);
        buffer.putInt(slotId);
        buffer.putInt(key);
        buffer.putInt(dataLen);
        buffer.put(data);
        
        return buffer.array();
    }

    /**
     * Deserializes raw bytes from the log file back into a LogRecord object.
     */
    public static LogRecord deserialize(ByteBuffer buffer) {
        long lsn = buffer.getLong();
        LogType type = LogType.fromId(buffer.getInt());
        int pageId = buffer.getInt();
        int slotId = buffer.getInt();
        int key = buffer.getInt();
        int dataLen = buffer.getInt();
        
        byte[] data = new byte[dataLen];
        buffer.get(data);
        
        return new LogRecord(lsn, type, pageId, slotId, key, data);
    }

    // Getters
    public long getLsn() { return lsn; }
    public LogType getType() { return type; }
    public int getPageId() { return pageId; }
    public int getSlotId() { return slotId; }
    public int getKey() { return key; }
    public byte[] getData() { return data; }
}
