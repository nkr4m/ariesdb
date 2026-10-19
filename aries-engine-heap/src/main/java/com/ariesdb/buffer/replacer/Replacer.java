package com.ariesdb.buffer.replacer;

/**
 * Tracks page usage and decides which frame to evict when the Buffer Pool is full.
 * Operates strictly on physical Frame IDs (0 to poolSize - 1), NOT logical Page IDs.
 */
public interface Replacer {
    /**
     * Called when a page is pinned by a thread. Removes it from eviction candidates.
     */
    void pin(int frameId);

    /**
     * Called when a page's pin count drops to zero. Adds it to eviction candidates.
     */
    void unpin(int frameId);

    /**
     * Selects a victim frame to evict based on the algorithm.
     * @return The frameId of the victim, or -1 if all frames are pinned.
     */
    int victim();

    /**
     * @return The number of frames currently available for eviction.
     */
    int size();
}
