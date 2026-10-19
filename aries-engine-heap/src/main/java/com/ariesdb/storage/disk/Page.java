package com.ariesdb.storage.disk;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import com.ariesdb.common.config.Constants;

public class Page {
 private int pageId = Constants.INVALID_PAGE_ID;
 private final ByteBuffer data = ByteBuffer.allocateDirect(Constants.PAGE_SIZE);
 
 // Metadata for the Buffer Pool Clock Replacer
 private boolean isDirty = false;
 private final AtomicInteger pinCount = new AtomicInteger(0);

 public int getPageId() { return pageId; }
 public void setPageId(int pageId) { this.pageId = pageId; }
 
 public ByteBuffer getData() { return data; }
 
 public void pin() { pinCount.incrementAndGet(); }
 public void unpin() { pinCount.decrementAndGet(); }
 public int getPinCount() { return pinCount.get(); }
 
 public boolean isDirty() { return isDirty; }
 public void setDirty(boolean dirty) { isDirty = dirty; }
}
