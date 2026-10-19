package com.ariesdb.buffer.replacer;

public class ClockReplacer implements Replacer {
    private final int poolSize;
    private int clockHand;
    
    // Tracks if a frame is currently eligible for eviction (i.e., Pin Count == 0)
    private final boolean[] inReplacer;
    
    // The "Second Chance" bits
    private final boolean[] refBits;
    
    // Tracks total number of evictable frames
    private int unpinnedCount;

    public ClockReplacer(int poolSize) {
        this.poolSize = poolSize;
        this.clockHand = 0;
        this.inReplacer = new boolean[poolSize];
        this.refBits = new boolean[poolSize];
        this.unpinnedCount = 0;
    }

    /**
     * A thread is using this frame. Remove it from eviction eligibility.
     */
    @Override
    public synchronized void pin(int frameId) {
        if (frameId < 0 || frameId >= poolSize) {
            throw new IllegalArgumentException("Invalid frame ID: " + frameId);
        }
        if (inReplacer[frameId]) {
            inReplacer[frameId] = false;
            unpinnedCount--;
        }
    }

    /**
     * No threads are using this frame anymore. Add it to eviction eligibility 
     * and grant it a "second chance" (Ref Bit = true).
     */
    @Override
    public synchronized void unpin(int frameId) {
        if (frameId < 0 || frameId >= poolSize) {
            throw new IllegalArgumentException("Invalid frame ID: " + frameId);
        }
        if (!inReplacer[frameId]) {
            inReplacer[frameId] = true;
            refBits[frameId] = true; // Give it a second chance
            unpinnedCount++;
        }
    }

    /**
     * Sweeps the clock hand to find a victim frame.
     * @return The frameId to evict, or -1 if all frames are pinned.
     */
    @Override
    public synchronized int victim() {
        if (unpinnedCount == 0) {
            return -1; // Everything is pinned, cannot evict anything
        }

        while (true) {
            if (inReplacer[clockHand]) {
                if (refBits[clockHand]) {
                    // Frame was recently unpinned. Revoke its second chance and move on.
                    refBits[clockHand] = false;
                } else {
                    // Frame has no second chance left. This is the victim.
                    int victimFrameId = clockHand;
                    
                    // Remove the victim from the replacer tracking
                    inReplacer[victimFrameId] = false;
                    unpinnedCount--;
                    
                    // Advance hand for the next time victim() is called
                    clockHand = (clockHand + 1) % poolSize;
                    
                    return victimFrameId;
                }
            }
            // Move the clock hand forward in a circle
            clockHand = (clockHand + 1) % poolSize;
        }
    }

    @Override
    public synchronized int size() {
        return unpinnedCount;
    }
}