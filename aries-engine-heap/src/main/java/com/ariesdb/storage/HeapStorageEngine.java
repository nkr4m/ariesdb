package com.ariesdb.storage;


import com.ariesdb.core.EngineAPI;
import com.ariesdb.core.RID;

public class HeapStorageEngine implements EngineAPI {
    private DiskManager diskManager;
    private SlottedPage activePage;
    private int activePageId;

    @Override
    public void start(String databaseName) throws Exception {
        this.diskManager = new DiskManager(databaseName + ".db");
        
        int totalPages = diskManager.getTotalPages();
        if (totalPages == 0) {
            this.activePageId = 0;
            this.activePage = new SlottedPage(0);
        } else {
            this.activePageId = totalPages - 1;
            this.activePage = diskManager.readPage(activePageId);
        }
    }

    @Override
    public RID insert(byte[] data) throws Exception {
        int slotId = activePage.insert(data);
        
        if (slotId == -1) { // Current 4KB page is full
            diskManager.writePage(activePageId, activePage); // Flush to disk
            
            activePageId++;
            activePage = new SlottedPage(activePageId);
            slotId = activePage.insert(data);
        }
        
        diskManager.writePage(activePageId, activePage); // Synchronous write
        return new RID(activePageId, slotId);
    }

    @Override
    public byte[] read(RID rid) throws Exception {
        SlottedPage pageToRead;
        
        if (rid.getPageId() == activePageId) {
            pageToRead = activePage; // Cache hit
        } else {
            pageToRead = diskManager.readPage(rid.getPageId()); // Disk read
        }
        
        return pageToRead.read(rid.getSlotId());
    }

    @Override
    public void shutdown() throws Exception {
        if (diskManager != null && activePage != null) {
            diskManager.writePage(activePageId, activePage);
            diskManager.close();
        }
    }
}
