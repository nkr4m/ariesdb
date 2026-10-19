package com.ariesdb.buffer;

import com.ariesdb.buffer.replacer.Replacer;
import com.ariesdb.common.config.Constants;
import com.ariesdb.storage.disk.DiskManager;
import com.ariesdb.storage.page.Page;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BufferPoolManager {
    private final int poolSize;
    private final Page[] frames;                     
    private final DiskManager diskManager;
    private final Replacer replacer;
    
    // Maps logical Page ID (Disk) -> physical Frame ID (RAM array index)
    private final ConcurrentHashMap<Integer, Integer> pageTable;
    
    // Tracks completely empty frame slots
    private final ConcurrentLinkedQueue<Integer> freeList;

    public BufferPoolManager(int poolSize, DiskManager diskManager, Replacer replacer) {
        this.poolSize = poolSize;
        this.diskManager = diskManager;
        this.replacer = replacer;
        
        this.frames = new Page[poolSize];
        this.pageTable = new ConcurrentHashMap<>();
        this.freeList = new ConcurrentLinkedQueue<>();

        // Initialize all frames and put them in the free list
        for (int i = 0; i < poolSize; i++) {
            this.frames[i] = new Page();
            this.freeList.add(i);
        }
    }

    /**
     * Fetches the requested page from the buffer pool or reads it from disk.
     */
    public synchronized Page fetchPage(int pageId) {
        // 1. Cache Hit: Page is already in memory
        if (pageTable.containsKey(pageId)) {
            int frameId = pageTable.get(pageId);
            Page page = frames[frameId];
            page.pin();
            replacer.pin(frameId); 
            return page;
        }

        // 2. Cache Miss: Find a frame to hold the page
        int frameId = getAvailableFrame();
        if (frameId == -1) {
            throw new RuntimeException("Buffer Pool is completely full and all pages are pinned!");
        }

        // 3. Load the data from disk into the frame
        Page page = frames[frameId];
        page.setPageId(pageId);
        
        try {
            diskManager.readPage(pageId, page.getData());
        } catch (Exception e) {
            throw new RuntimeException("Failed to read page " + pageId + " from disk.", e);
        }
        
        // 4. Update metadata
        pageTable.put(pageId, frameId);
        page.pin();
        replacer.pin(frameId);
        
        return page;
    }

    /**
     * Decrements the pin count of a page. If it reaches 0, it becomes an eviction candidate.
     */
    public synchronized void unpinPage(int pageId, boolean isDirty) {
        Integer frameId = pageTable.get(pageId);
        if (frameId == null) {
            return; // Page is not in memory
        }

        Page page = frames[frameId];
        if (isDirty) {
            page.setDirty(true);
        }

        page.unpin();
        
        // If no threads are using it anymore, tell the replacer it can be evicted
        if (page.getPinCount() == 0) {
            replacer.unpin(frameId);
        }
    }

    /**
     * Forces a single page to be written to disk immediately.
     */
    public synchronized void flushPage(int pageId) {
        Integer frameId = pageTable.get(pageId);
        if (frameId != null) {
            Page page = frames[frameId];
            if (page.isDirty()) {
                try {
                    diskManager.writePage(page.getPageId(), page.getData());
                    page.setDirty(false);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to flush page " + pageId + " to disk.", e);
                }
            }
        }
    }

    /**
     * Flushes ALL dirty pages currently in the buffer pool to disk.
     * Called during graceful shutdown in AriesEngine.
     */
    public synchronized void flushAllPages() {
        for (int frameId = 0; frameId < poolSize; frameId++) {
            Page page = frames[frameId];
            if (page.getPageId() != Constants.INVALID_PAGE_ID && page.isDirty()) {
                try {
                    diskManager.writePage(page.getPageId(), page.getData());
                    page.setDirty(false);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to flush page " + page.getPageId() + " during shutdown.", e);
                }
            }
        }
    }

    /**
     * Helper: Finds a free frame, or evicts an existing one to make space.
     */
    private int getAvailableFrame() {
        // Option A: We have a completely empty frame available
        Integer frameId = freeList.poll();
        if (frameId != null) {
            return frameId;
        }

        // Option B: Buffer is full, we must evict an unpinned page
        frameId = replacer.victim();
        if (frameId != -1) {
            Page evictedPage = frames[frameId];
            
            // If the evicted page was modified, save it to disk before overwriting it
            if (evictedPage.isDirty()) {
                try {
                    diskManager.writePage(evictedPage.getPageId(), evictedPage.getData());
                    evictedPage.setDirty(false);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to flush evicted page " + evictedPage.getPageId() + " to disk.", e);
                }
            }
            
            // Remove the old mapping from the page table
            pageTable.remove(evictedPage.getPageId());
            return frameId;
        }

        // Option C: Buffer is full and EVERY page is pinned (Out of Memory)
        return -1; 
    }
}