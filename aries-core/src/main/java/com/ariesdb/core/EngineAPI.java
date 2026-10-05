package com.ariesdb.core;

/**
 * The standard contract for all AriesDB storage engine implementations.
 */
public interface EngineAPI {
    
    /**
     * Boots up the engine, allocates memory, and opens file channels.
     * 
     * @param databaseName The name/path of the database file directory.
     */
    void start(String databaseName) throws Exception;

    /**
     * Serializes and writes a raw byte array to the storage engine.
     * 
     * @param data The raw tuple bytes to store.
     * @return The physical RID where the data was placed.
     */
    RID insert(byte[] data) throws Exception;

    /**
     * Fetches a raw byte array directly from a physical address.
     * 
     * @param rid The physical address of the record.
     * @return The raw tuple bytes.
     */
    byte[] read(RID rid) throws Exception;

    /**
     * Flushes all active pages/buffers to disk and closes file handles safely.
     */
    void shutdown() throws Exception;
}
