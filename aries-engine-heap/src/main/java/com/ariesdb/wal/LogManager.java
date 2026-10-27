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
        this.logFilename = dbFilename.replaceAll("\\.db$", "") + ".log";
        File file = new File(logFilename);
        
        this.logFile = new RandomAccessFile(file, "rw");
        this.logChannel = logFile.getChannel();
        
        long existingLength = logChannel.size();
        this.globalLSN = new AtomicLong(existingLength > 0 ? findLatestLSN() : 1L);
    }

    /**
     * Appends a new log record to the WAL file.
     */
    public synchronized LogRecord appendLog(long txnId, LogType type, int pageId, int slotId, int key, byte[] data) throws Exception {
        long lsn = globalLSN.getAndIncrement();
        LogRecord record = new LogRecord(lsn, txnId, type, pageId, slotId, key, data);
        byte[] serialized = record.serialize();

        logChannel.position(logChannel.size());
        logChannel.write(ByteBuffer.wrap(serialized));
        logChannel.force(false);

        return record; // <-- Return the object!
    }

    public long getCurrentOffset() throws Exception {
        return logChannel.size();
    }

    public synchronized List<LogRecord> readLogsFromOffset(long startOffset) throws Exception {
        List<LogRecord> records = new ArrayList<>();
        if (logChannel.size() <= startOffset) return records;

        logChannel.position(startOffset);
        
        while (logChannel.position() < logChannel.size()) {
            // LSN(8) + TxnId(8) + Type(4) + PageId(4) + SlotId(4) + Key(4) + DataLen(4) = 36 bytes header
            ByteBuffer headerBuffer = ByteBuffer.allocate(36);
            logChannel.read(headerBuffer);
            headerBuffer.flip();
            
            headerBuffer.getLong(); // lsn
            headerBuffer.getLong(); // txnId
            headerBuffer.getInt();  // type
            headerBuffer.getInt();  // pageId
            headerBuffer.getInt();  // slotId
            headerBuffer.getInt();  // key
            int dataLen = headerBuffer.getInt();

            ByteBuffer fullRecordBuffer = ByteBuffer.allocate(36 + dataLen);
            logChannel.position(logChannel.position() - 36); 
            logChannel.read(fullRecordBuffer);
            fullRecordBuffer.flip();

            LogRecord record = LogRecord.deserialize(fullRecordBuffer);
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