package com.ariesdb.storage.disk;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class DiskManager {
    private FileChannel fileChannel;
    private static final int PAGE_SIZE = 4096;

    public DiskManager(String fileName) throws Exception {
        File file = new File(fileName);
        // "rw" opens for read/write and creates the file if missing
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        this.fileChannel = raf.getChannel();
    }

    /**
     * Writes the contents of the provided ByteBuffer directly to the physical disk.
     */
    public void writePage(int pageId, ByteBuffer pageData) throws Exception {
        long offset = (long) pageId * PAGE_SIZE;
        
        // Rewind buffer position to 0 to ensure we write the whole 4KB block
        pageData.rewind(); 
        
        fileChannel.position(offset);
        fileChannel.write(pageData);
        fileChannel.force(false); // fsync: forces OS to write to physical disk immediately
    }

    /**
     * Reads a 4KB block from the disk and fills the provided ByteBuffer.
     * Prevents memory allocation overhead.
     */
    public void readPage(int pageId, ByteBuffer pageData) throws Exception {
        long offset = (long) pageId * PAGE_SIZE;
        
        // Clear the buffer to prepare it for incoming bytes
        pageData.clear(); 
        
        fileChannel.position(offset);
        int bytesRead = fileChannel.read(pageData);
        
        // If the file is smaller than the requested page (e.g., brand new page),
        // fill the remaining space in the buffer with zeros.
        if (bytesRead < PAGE_SIZE) {
            while (pageData.hasRemaining()) {
                pageData.put((byte) 0);
            }
        }
        
        // Reset position back to 0 so the Buffer Pool / Engine can read from the beginning
        pageData.rewind(); 
    }

    /**
     * Calculates how many 4KB pages currently exist in the file.
     */
    public int getTotalPages() throws Exception {
        return (int) (fileChannel.size() / PAGE_SIZE);
    }
    
    public void close() throws Exception {
        if (fileChannel != null) {
            fileChannel.close();
        }
    }
}