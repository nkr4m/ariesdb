package com.ariesdb.core;

import java.util.Objects;

/**
 * Represents the physical address of a record on disk.
 */
public class RID {
    private final int pageId;
    private final int slotId;

    public RID(int pageId, int slotId) {
        this.pageId = pageId;
        this.slotId = slotId;
    }

    public int getPageId() {
        return pageId;
    }

    public int getSlotId() {
        return slotId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RID rid = (RID) o;
        return pageId == rid.pageId && slotId == rid.slotId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(pageId, slotId);
    }

    @Override
    public String toString() {
        return "RID[" + pageId + ", " + slotId + "]";
    }
}
