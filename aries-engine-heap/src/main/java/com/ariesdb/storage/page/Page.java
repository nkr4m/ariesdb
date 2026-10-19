package com.ariesdb.storage.page;

import com.ariesdb.common.config.Constants;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

public class Page {
    private int pageId = Constants.INVALID_PAGE_ID;
    
    // The actual 4KB block of RAM. 
    // allocateDirect() keeps it off-heap so the Java Garbage Collector ignores it.
    private final ByteBuffer data = ByteBuffer.allocateDirect(Constants.PAGE_SIZE);
    
    // Metadata for the Buffer Pool's Clock Replacer
    private boolean isDirty = false;
    private final AtomicInteger pinCount = new AtomicInteger(0);

    public int getPageId() { 
        return pageId; 
    }
    
    public void setPageId(int pageId) { 
        this.pageId = pageId; 
    }
    
    public ByteBuffer getData() { 
        return data; 
    }
    
    // Pinning logic for thread safety
    public void pin() { 
        pinCount.incrementAndGet(); 
    }
    
    public void unpin() { 
        pinCount.decrementAndGet(); 
    }
    
    public int getPinCount() { 
        return pinCount.get(); 
    }
    
    // Dirty tracking for disk flushing
    public boolean isDirty() { 
        return isDirty; 
    }
    
    public void setDirty(boolean dirty) { 
        this.isDirty = dirty; 
    }
}
