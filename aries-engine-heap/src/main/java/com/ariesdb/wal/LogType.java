package com.ariesdb.wal;

public enum LogType {
    INSERT(1),
    DELETE(2),
    UPDATE(3);

    private final int id;

    LogType(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    public static LogType fromId(int id) {
        for (LogType type : values()) {
            if (type.id == id) return type;
        }
        throw new IllegalArgumentException("Unknown LogType ID: " + id);
    }
}
