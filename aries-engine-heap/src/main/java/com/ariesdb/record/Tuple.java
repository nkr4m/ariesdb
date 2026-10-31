package com.ariesdb.record;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class Tuple {
    private final List<Object> values;

    public Tuple(List<Object> values) {
        this.values = values;
    }

    public List<Object> getValues() { return values; }
    public Object getValue(int index) { return values.get(index); }

    public byte[] serialize(Schema schema) {
        // Step 1: Calculate exact byte size needed
        int size = 0;
        List<byte[]> varcharBytesList = new ArrayList<>();
        
        for (int i = 0; i < schema.getColumnCount(); i++) {
            DataType type = schema.getColumn(i).getType();
            if (type == DataType.INT) size += 4;
            else if (type == DataType.BIGINT) size += 8;
            else if (type == DataType.BOOLEAN) size += 1;
            else if (type == DataType.VARCHAR) {
                String str = (String) values.get(i);
                byte[] strBytes = str.getBytes(StandardCharsets.UTF_8);
                varcharBytesList.add(strBytes);
                size += 4 + strBytes.length; // 4 bytes for length prefix + actual bytes
            }
        }

        // Step 2: Pack the bytes
        ByteBuffer buffer = ByteBuffer.allocate(size);
        int varcharIdx = 0;

        for (int i = 0; i < schema.getColumnCount(); i++) {
            DataType type = schema.getColumn(i).getType();
            Object value = values.get(i);

            if (type == DataType.INT) {
                buffer.putInt((Integer) value);
            } else if (type == DataType.BIGINT) {
                buffer.putLong((Long) value);
            } else if (type == DataType.BOOLEAN) {
                buffer.put((byte) ((Boolean) value ? 1 : 0));
            } else if (type == DataType.VARCHAR) {
                byte[] strBytes = varcharBytesList.get(varcharIdx++);
                buffer.putInt(strBytes.length); 
                buffer.put(strBytes);           
            }
        }
        return buffer.array();
    }

    public static Tuple deserialize(byte[] data, Schema schema) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        List<Object> deserializedValues = new ArrayList<>();

        for (int i = 0; i < schema.getColumnCount(); i++) {
            DataType type = schema.getColumn(i).getType();

            if (type == DataType.INT) {
                deserializedValues.add(buffer.getInt());
            } else if (type == DataType.BIGINT) {
                deserializedValues.add(buffer.getLong());
            } else if (type == DataType.BOOLEAN) {
                deserializedValues.add(buffer.get() == 1);
            } else if (type == DataType.VARCHAR) {
                int length = buffer.getInt();
                byte[] strBytes = new byte[length];
                buffer.get(strBytes);
                deserializedValues.add(new String(strBytes, StandardCharsets.UTF_8));
            }
        }
        return new Tuple(deserializedValues);
    }
    
    @Override
    public String toString() {
        return values.toString();
    }
}