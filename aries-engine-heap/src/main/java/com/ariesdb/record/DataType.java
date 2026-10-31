package com.ariesdb.record;

public enum DataType {
    INT,      // 4 bytes
    BIGINT,   // 8 bytes
    BOOLEAN,  // 1 byte
    VARCHAR   // Variable length (4-byte length header + string bytes)
}