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

    public LogManager(String dbDir) throws Exception {
        // The WAL now lives in the main database directory alongside the table files
        File dir = new File(dbDir);
        if (!dir.exists()) dir.mkdirs();
        
        this.logFilename = dbDir + "/wal.log";
        File file = new File(logFilename);
        
        this.logFile = new RandomAccessFile(file, "rw");
        this.logChannel = logFile.getChannel();
        
        long existingLength = logChannel.size();
        this.globalLSN = new AtomicLong(existingLength > 0 ? findLatestLSN() : 1L);
    }

    // CHANGED: Now requires tableName
    public synchronized LogRecord appendLog(long txnId, LogType type, String tableName, int pageId, int slotId, int key, byte[] data) throws Exception {
        long lsn = globalLSN.getAndIncrement();
        LogRecord record = new LogRecord(lsn, txnId, type, tableName, pageId, slotId, key, data);
        byte[] serialized = record.serialize();

        logChannel.position(logChannel.size());
        logChannel.write(ByteBuffer.wrap(serialized));
        logChannel.force(false);

        return record;
    }

    public long getCurrentOffset() throws Exception {
        return logChannel.size();
    }

    public synchronized List<LogRecord> readLogsFromOffset(long startOffset) throws Exception {
        List<LogRecord> records = new ArrayList<>();
        if (logChannel.size() <= startOffset) return records;

        logChannel.position(startOffset);
        
        while (logChannel.position() < logChannel.size()) {
            // 1. Read the 4-byte length prefix
            ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
            int bytesRead = logChannel.read(lengthBuffer);
            if (bytesRead < 4) break; 
            
            lengthBuffer.flip();
            int payloadSize = lengthBuffer.getInt();

            // 2. Read the exact payload
            ByteBuffer recordBuffer = ByteBuffer.allocate(payloadSize);
            logChannel.read(recordBuffer);
            recordBuffer.flip();

            // 3. Delegate to LogRecord
            LogRecord record = LogRecord.deserialize(recordBuffer);
            records.add(record);
        }
        return records;
    }

    private long findLatestLSN() throws Exception {
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