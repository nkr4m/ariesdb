package com.ariesdb.wal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class LogRecord {
    private final long lsn;
    private final long txnId;
    private final LogType type;
    private final String tableName; // NEW: The WAL now tracks which table this log belongs to!
    private final int pageId;
    private final int slotId;
    private final int key;
    private final byte[] data;

    public LogRecord(long lsn, long txnId, LogType type, String tableName, int pageId, int slotId, int key, byte[] data) {
        this.lsn = lsn;
        this.txnId = txnId;
        this.type = type;
        this.tableName = tableName;
        this.pageId = pageId;
        this.slotId = slotId;
        this.key = key;
        this.data = data;
    }

    public long getLsn() { return lsn; }
    public long getTxnId() { return txnId; }
    public LogType getType() { return type; }
    public String getTableName() { return tableName; }
    public int getPageId() { return pageId; }
    public int getSlotId() { return slotId; }
    public int getKey() { return key; }
    public byte[] getData() { return data; }

    public byte[] serialize() {
        byte[] tableBytes = (tableName == null ? "" : tableName).getBytes(StandardCharsets.UTF_8);
        int dataLen = (data != null) ? data.length : 0;
        
        // Calculate exact payload size
        int payloadSize = 40 + tableBytes.length + dataLen; 
        
        // We add 4 bytes at the very start to store the payloadSize itself
        ByteBuffer buffer = ByteBuffer.allocate(4 + payloadSize);
        
        buffer.putInt(payloadSize); // 1. Length-Prefix for the LogManager
        buffer.putLong(lsn);
        buffer.putLong(txnId);
        buffer.putInt(type.ordinal());
        
        buffer.putInt(tableBytes.length);
        buffer.put(tableBytes);
        
        buffer.putInt(pageId);
        buffer.putInt(slotId);
        buffer.putInt(key);
        
        buffer.putInt(dataLen);
        if (data != null) {
            buffer.put(data);
        }
        
        return buffer.array();
    }

    public static LogRecord deserialize(ByteBuffer buffer) {
        long lsn = buffer.getLong();
        long txnId = buffer.getLong();
        LogType type = LogType.values()[buffer.getInt()];
        
        int tableLen = buffer.getInt();
        byte[] tableBytes = new byte[tableLen];
        buffer.get(tableBytes);
        String tableName = new String(tableBytes, StandardCharsets.UTF_8);
        if (tableName.isEmpty()) tableName = null;

        int pageId = buffer.getInt();
        int slotId = buffer.getInt();
        int key = buffer.getInt();
        
        int dataLen = buffer.getInt();
        byte[] data = null;
        if (dataLen > 0) {
            data = new byte[dataLen];
            buffer.get(data);
        }
        
        return new LogRecord(lsn, txnId, type, tableName, pageId, slotId, key, data);
    }
}