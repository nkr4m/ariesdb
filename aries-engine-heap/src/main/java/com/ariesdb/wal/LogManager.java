package com.ariesdb.wal;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class LogManager implements AutoCloseable {
    private final String logFilename;
    private final RandomAccessFile logFile;
    private final FileChannel logChannel;
    private final AtomicLong globalLSN;

    public LogManager(String dbFilename) throws Exception {
        // Name the log file based on the database name (e.g., testdb.log)
        this.logFilename = dbFilename.replaceAll("\\.db$", "") + ".log";
        File file = new File(logFilename);
        
        this.logFile = new RandomAccessFile(file, "rw");
        this.logChannel = logFile.getChannel();
        
        // Initialize global LSN based on existing log size or start at 1
        long existingLength = logChannel.size();
        this.globalLSN = new AtomicLong(existingLength > 0 ? findLatestLSN() : 1L);
    }

    /**
     * Appends a new log record to the WAL file.
     * THE WAL RULE: This must be written to disk BEFORE the data page is allowed to flush.
     */
    public synchronized long appendLog(LogType type, int pageId, int slotId, int key, byte[] data) throws Exception {
        long lsn = globalLSN.getAndIncrement();
        LogRecord record = new LogRecord(lsn, type, pageId, slotId, key, data);
        byte[] serialized = record.serialize();

        // Move to the end of the log file and write
        logChannel.position(logChannel.size());
        logChannel.write(ByteBuffer.wrap(serialized));
        
        // Force flush to physical disk immediately (True WAL durability)
        logChannel.force(false);

        return lsn;
    }

    /**
     * Returns the current byte size (offset) of the log file.
     * Used by the CheckpointManager to know where it is safe to start reading next time.
     */
    public long getCurrentOffset() throws Exception {
        return logChannel.size();
    }

    /**
     * Scans the log file starting from a specific byte offset.
     * Returns a List of LogRecords, completely eliminating the need for lambdas.
     */
    public synchronized List<LogRecord> readLogsFromOffset(long startOffset) throws Exception {
        List<LogRecord> records = new ArrayList<>();
        if (logChannel.size() <= startOffset) return records;

        logChannel.position(startOffset); // Jump straight to the checkpoint!
        
        while (logChannel.position() < logChannel.size()) {
            // Read header parts to determine total record size
            // LSN(8) + Type(4) + PageId(4) + SlotId(4) + Key(4) + DataLen(4) = 28 bytes header
            ByteBuffer headerBuffer = ByteBuffer.allocate(28);
            logChannel.read(headerBuffer);
            headerBuffer.flip();
            
            headerBuffer.getLong(); // lsn
            headerBuffer.getInt();  // type
            headerBuffer.getInt();  // pageId
            headerBuffer.getInt();  // slotId
            headerBuffer.getInt();  // key
            int dataLen = headerBuffer.getInt();

            // Read data payload bytes
            ByteBuffer fullRecordBuffer = ByteBuffer.allocate(28 + dataLen);
            logChannel.position(logChannel.position() - 28); // reset position back to start of record
            logChannel.read(fullRecordBuffer);
            fullRecordBuffer.flip();

            LogRecord record = LogRecord.deserialize(fullRecordBuffer);
            records.add(record);
        }
        return records;
    }

    private long findLatestLSN() throws Exception {
        // Scan all logs on startup to find the max LSN without using lambdas
        long maxLsn = 1L;
        List<LogRecord> allRecords = readLogsFromOffset(0);
        for (LogRecord record : allRecords) {
            if (record.getLsn() >= maxLsn) {
                maxLsn = record.getLsn() + 1;
            }
        }
        return maxLsn;
    }

    @Override
    public void close() throws Exception {
        if (logChannel != null) logChannel.close();
        if (logFile != null) logFile.close();
    }
}