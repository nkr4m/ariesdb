package com.ariesdb.cli;

import com.ariesdb.engine.AriesEngine;
import com.ariesdb.storage.disk.DiskManager;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class DatabaseCommands {

    @Value("${ariesdb.storage.path:./aries_data}")
    private String storagePath;

    private AriesEngine<Integer> engine; 
    private boolean isRunning = false;

    @Command(name = "start", description = "Boot up the database engine.")
    public String startDB(@Argument(index = 0, defaultValue = "testdb") String dbName) {
        try {
            // 1. Ensure the target directory exists
            Path directoryPath = Paths.get(storagePath);
            if (!Files.exists(directoryPath)) {
                Files.createDirectories(directoryPath);
            }

            // 2. Construct the base path (e.g., ./aries_data/testdb)
            String basePath = new File(directoryPath.toFile(), dbName).getAbsolutePath();
            
            // 3. Create separate files for Heap Data and B-Tree Index
            String heapPath = basePath + ".db";
            String indexPath = basePath + ".idx";

            // 4. Boot the engine with TWO DiskManagers
            DiskManager heapDiskManager = new DiskManager(heapPath);
            DiskManager indexDiskManager = new DiskManager(indexPath);
            
            engine = new AriesEngine<>(heapDiskManager, indexDiskManager, basePath, 3);
            
            isRunning = true;
            return "SUCCESS: AriesDB started. Files bound to: " + basePath + " (.*)";
        } catch (Exception e) {
            return "ERROR: Failed to start database - " + e.getMessage();
        }
    }

    @PreDestroy
    public void cleanup() {
        if (engine != null) {
            try {
                engine.close();
                System.out.println("SUCCESS: Database engine closed and flushed to disk.");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Command(name = "insert", description = "Insert a record using a primary key.")
    public String insertRecord(@Argument(index = 0) int key, @Argument(index = 1) String data) {
        if (!isRunning) return "ERROR: Please run 'start' first.";
        
        try {
            engine.insert(key, data.getBytes());
            return "INSERT 0 1 -> Record stored and indexed under Key: " + key;
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Command(name = "read", description = "Read a record directly via its primary key.")
    public String readRecord(@Argument(index = 0) int key) {
        if (!isRunning) return "ERROR: Please run 'start' first.";

        try {
            byte[] rawBytes = engine.read(key);
            if (rawBytes == null) {
                return "ERROR: Record not found or was deleted (Tombstone).";
            }
            return "Data: [" + new String(rawBytes) + "]";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Command(name = "update", description = "Update an existing record (Out-of-place).")
    public String updateRecord(@Argument(index = 0) int key, @Argument(index = 1) String data) {
        if (!isRunning) return "ERROR: Please run 'start' first.";
        
        try {
            boolean success = engine.update(key, data.getBytes());
            return success ? "UPDATE 0 1 -> Key " + key + " updated to new physical slot." 
                           : "ERROR: Cannot update. Key not found.";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Command(name = "delete", description = "Delete a record by key (Lazy Tombstone).")
    public String deleteRecord(@Argument(index = 0) int key) {
        if (!isRunning) return "ERROR: Please run 'start' first.";
        
        try {
            boolean success = engine.delete(key);
            return success ? "DELETE 0 1 -> Key " + key + " marked as tombstone in B-Tree." 
                           : "ERROR: Key not found.";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Command(name = "checkpoint", description = "Force a database checkpoint to truncate WAL recovery time.")
    public String checkpoint() {
        if (!isRunning) return "ERROR: Please run 'start' first.";
        
        try {
            engine.performCheckpoint();
            return "CHECKPOINT SUCCESS -> Dirty pages flushed and WAL offset saved.";
        } catch (Exception e) {
            return "ERROR: Checkpoint failed - " + e.getMessage();
        }
    }
    
    
    @Command(name = "stress", description = "Insert multiple records sequentially to test B-Tree splitting.")
    public String stressTest(@Argument(index = 0, defaultValue = "1000") int count) {
        if (!isRunning) return "ERROR: Please run 'start' first.";
        
        long startTime = System.currentTimeMillis();
        try {
            for (int i = 0; i < count; i++) {
                // Generates dummy data like "Payload-450"
                String payload = "Payload-" + i;
                engine.insert(i, payload.getBytes());
            }
            long duration = System.currentTimeMillis() - startTime;
            return "STRESS TEST SUCCESS -> Inserted " + count + " records in " + duration + "ms. B-Tree splits executed.";
        } catch (Exception e) {
            return "ERROR during stress test: " + e.getMessage();
        }
    }
}