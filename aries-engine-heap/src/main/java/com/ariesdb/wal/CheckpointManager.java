package com.ariesdb.wal;

import java.io.*;

public class CheckpointManager {
    private final File ckptFile;

    public CheckpointManager(String dbFilename) {
        this.ckptFile = new File(dbFilename.replaceAll("\\.db$", "") + ".ckpt");
    }

    /**
     * Writes the current log offset to the checkpoint file and forces it to disk.
     */
    public void writeCheckpoint(long logOffset) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(ckptFile);
             DataOutputStream dos = new DataOutputStream(fos)) {
            dos.writeLong(logOffset);
            fos.getFD().sync(); // Guarantee it hits the physical disk
        }
    }

    /**
     * Reads the last saved log offset. Returns 0 if no checkpoint exists.
     */
    public long readCheckpoint() {
        if (!ckptFile.exists()) return 0L;
        try (FileInputStream fis = new FileInputStream(ckptFile);
             DataInputStream dis = new DataInputStream(fis)) {
            return dis.readLong();
        } catch (IOException e) {
            return 0L;
        }
    }
}