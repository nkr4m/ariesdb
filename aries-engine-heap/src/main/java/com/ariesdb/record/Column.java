package com.ariesdb.record;

public class Column {
    private final String name;
    private final DataType type;
    private final int maxLength; 

    public Column(String name, DataType type) {
        this(name, type, 0);
    }

    public Column(String name, DataType type, int maxLength) {
        this.name = name;
        this.type = type;
        this.maxLength = maxLength;
    }

    public String getName() { return name; }
    public DataType getType() { return type; }
    public int getMaxLength() { return maxLength; }
}