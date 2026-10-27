package com.ariesdb.wal;

public enum LogType {
    INSERT(1),
    UPDATE(2),
    DELETE(3),
    COMMIT(4), // <-- NEW
    ABORT(5);  // <-- NEW

    private final int id;
    LogType(int id) { this.id = id; }
    public int getId() { return id; }

    public static LogType fromId(int id) {
        for (LogType type : values()) {
            if (type.id == id) return type;
        }
        throw new IllegalArgumentException("Unknown LogType ID: " + id);
    }
}