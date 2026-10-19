package com.ariesdb.cli;

import com.ariesdb.engine.AriesEngine;
import com.ariesdb.storage.disk.DiskManager;

import jakarta.annotation.PreDestroy; // Spring Boot 3+ annotation
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.stereotype.Component;

@Component
public class DatabaseCommands {

    private DiskManager diskManager;
    private AriesEngine<Integer> engine; 
    private boolean isRunning = false;

    @Command(name = "start", description = "Boot up the database engine.")
    public String startDB(@Argument(index = 0, defaultValue = "testdb") String dbName) {
        try {
            String fileName = dbName + ".db";
            diskManager = new DiskManager(fileName);
            engine = new AriesEngine<>(diskManager, 3);
            
            isRunning = true;
            return "SUCCESS: AriesDB started. Bound to file: " + fileName;
        } catch (Exception e) {
            return "ERROR: Failed to start database - " + e.getMessage();
        }
    }

    // --- AUTOMATIC FLUSH ON SHUTDOWN ---
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
    // -----------------------------------

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
}