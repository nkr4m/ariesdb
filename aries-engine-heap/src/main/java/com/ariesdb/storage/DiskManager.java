package com.ariesdb.storage;


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

    public void writePage(int pageId, SlottedPage page) throws Exception {
        long offset = (long) pageId * PAGE_SIZE;
        ByteBuffer buffer = ByteBuffer.wrap(page.getPageBytes());
        
        fileChannel.position(offset);
        fileChannel.write(buffer);
        fileChannel.force(false); // fsync: forces OS to write to physical disk immediately
    }

    public SlottedPage readPage(int pageId) throws Exception {
        long offset = (long) pageId * PAGE_SIZE;
        ByteBuffer buffer = ByteBuffer.allocate(PAGE_SIZE);
        
        fileChannel.position(offset);
        int bytesRead = fileChannel.read(buffer);
        
        if (bytesRead < PAGE_SIZE) {
            // Page doesn't exist yet, return a fresh one
            return new SlottedPage(pageId);
        }
        return new SlottedPage(buffer.array());
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
