package com.ariesdb.cli;

import com.ariesdb.engine.AriesEngine;
import com.ariesdb.storage.disk.DiskManager;
import com.ariesdb.transaction.Transaction;

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
    
    // Tracks the active transaction for this CLI session
    private Transaction currentTxn = null;

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

    // --- TRANSACTION COMMANDS ---

    @Command(name = "begin", description = "Start a new transaction block.")
    public String beginTransaction() {
        if (!isRunning) return "ERROR: Please run 'start' first.";
        if (currentTxn != null) return "ERROR: Transaction " + currentTxn.getTxnId() + " is already active! Commit or rollback first.";
        
        currentTxn = engine.begin();
        return "BEGIN -> Transaction " + currentTxn.getTxnId() + " started.";
    }

    @Command(name = "commit", description = "Commit the active transaction to disk.")
    public String commitTransaction() {
        if (!isRunning) return "ERROR: Please run 'start' first.";
        if (currentTxn == null) return "ERROR: No active transaction to commit.";

        try {
            long txnId = currentTxn.getTxnId();
            engine.commit(currentTxn);
            currentTxn = null; // Clear session state
            return "COMMIT -> Transaction " + txnId + " committed durably to WAL.";
        } catch (Exception e) {
            return "ERROR during commit: " + e.getMessage();
        }
    }

    @Command(name = "rollback", description = "Abort and undo the active transaction.")
    public String rollbackTransaction() {
        if (!isRunning) return "ERROR: Please run 'start' first.";
        if (currentTxn == null) return "ERROR: No active transaction to rollback.";

        try {
            long txnId = currentTxn.getTxnId();
            engine.rollback(currentTxn);
            currentTxn = null; // Clear session state
            return "ROLLBACK -> Transaction " + txnId + " aborted. Memory state reversed.";
        } catch (Exception e) {
            return "ERROR during rollback: " + e.getMessage();
        }
    }

    // --- CRUD COMMANDS ---

    @Command(name = "insert", description = "Insert a record using a primary key.")
    public String insertRecord(@Argument(index = 0) int key, @Argument(index = 1) String data) {
        if (!isRunning) return "ERROR: Please run 'start' first.";
        
        boolean isAutoCommit = (currentTxn == null);
        Transaction txn = isAutoCommit ? engine.begin() : currentTxn;
        
        try {
            engine.insert(txn, key, data.getBytes());
            if (isAutoCommit) {
                engine.commit(txn);
                return "INSERT (Auto-Commit) 0 1 -> Record stored and indexed under Key: " + key;
            } else {
                return "INSERT (Txn " + txn.getTxnId() + ") 0 1 -> Record stored in Buffer Pool under Key: " + key;
            }
        } catch (Exception e) {
            try { if (isAutoCommit) engine.rollback(txn); } catch (Exception ignored) {}
            return "ERROR: " + e.getMessage();
        }
    }

    @Command(name = "read", description = "Read a record directly via its primary key.")
    public String readRecord(@Argument(index = 0) int key) {
        if (!isRunning) return "ERROR: Please run 'start' first.";

        boolean isAutoCommit = (currentTxn == null);
        Transaction txn = isAutoCommit ? engine.begin() : currentTxn;

        try {
            byte[] rawBytes = engine.read(txn, key);
            
            // Release the Shared Lock instantly if Auto-Commit
            if (isAutoCommit) engine.commit(txn); 
            
            if (rawBytes == null) {
                return "ERROR: Record not found or was deleted (Tombstone).";
            }
            return "Data: [" + new String(rawBytes) + "]";
        } catch (Exception e) {
            try { if (isAutoCommit) engine.rollback(txn); } catch (Exception ignored) {}
            return "ERROR: " + e.getMessage();
        }
    }

    @Command(name = "update", description = "Update an existing record (Out-of-place).")
    public String updateRecord(@Argument(index = 0) int key, @Argument(index = 1) String data) {
        if (!isRunning) return "ERROR: Please run 'start' first.";
        
        boolean isAutoCommit = (currentTxn == null);
        Transaction txn = isAutoCommit ? engine.begin() : currentTxn;
        
        try {
            boolean success = engine.update(txn, key, data.getBytes());
            if (!success) {
                if (isAutoCommit) engine.rollback(txn);
                return "ERROR: Cannot update. Key not found.";
            }
            if (isAutoCommit) engine.commit(txn);
            return "UPDATE 0 1 -> Key " + key + " updated to new physical slot.";
        } catch (Exception e) {
            try { if (isAutoCommit) engine.rollback(txn); } catch (Exception ignored) {}
            return "ERROR: " + e.getMessage();
        }
    }

    @Command(name = "delete", description = "Delete a record by key (Lazy Tombstone).")
    public String deleteRecord(@Argument(index = 0) int key) {
        if (!isRunning) return "ERROR: Please run 'start' first.";
        
        boolean isAutoCommit = (currentTxn == null);
        Transaction txn = isAutoCommit ? engine.begin() : currentTxn;
        
        try {
            boolean success = engine.delete(txn, key);
            if (!success) {
                if (isAutoCommit) engine.rollback(txn);
                return "ERROR: Key not found.";
            }
            if (isAutoCommit) engine.commit(txn);
            return "DELETE 0 1 -> Key " + key + " marked as tombstone in B-Tree.";
        } catch (Exception e) {
            try { if (isAutoCommit) engine.rollback(txn); } catch (Exception ignored) {}
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
    
    @Command(name = "stress", description = "Insert multiple records sequentially inside a single transaction.")
    public String stressTest(@Argument(index = 0, defaultValue = "1000") int count) {
        if (!isRunning) return "ERROR: Please run 'start' first.";
        
        long startTime = System.currentTimeMillis();
        // Wrap the entire stress test in a single massive Group Commit transaction!
        Transaction txn = engine.begin();
        try {
            for (int i = 0; i < count; i++) {
                String payload = "Payload-" + i;
                engine.insert(txn, i, payload.getBytes());
            }
            engine.commit(txn);
            long duration = System.currentTimeMillis() - startTime;
            return "STRESS TEST SUCCESS -> Group Committed " + count + " records in " + duration + "ms. B-Tree splits executed.";
        } catch (Exception e) {
            try { engine.rollback(txn); } catch (Exception ignored) {}
            return "ERROR during stress test: " + e.getMessage();
        }
    }
    
    @Command(name = "test-locks", description = "Run a multi-threaded test to see 2PL locking in action.")
    public String testLocks() {
        if (!isRunning) return "ERROR: Please run 'start' first.";
        
        System.out.println("Preparing: Inserting Key 50 -> 'Initial'");
        Transaction initTxn = engine.begin();
        try {
            engine.insert(initTxn, 50, "Initial".getBytes());
            engine.commit(initTxn);
        } catch (Exception e) {
            return "Init failed: " + e.getMessage();
        }

        Thread t1 = new Thread(() -> {
            Transaction txnA = null;
            try {
                System.out.println("[Thread 1] Starting Transaction A...");
                txnA = engine.begin();
                System.out.println("[Thread 1] Updating Key 50 (Acquiring Exclusive Lock)...");
                engine.update(txnA, 50, "Txn A Data".getBytes());
                
                System.out.println("[Thread 1] Lock Acquired! Sleeping for 3 seconds...");
                Thread.sleep(3000); // Hold the lock longer than the 2000ms timeout!
                
                System.out.println("[Thread 1] Woke up. Committing Transaction A...");
                engine.commit(txnA);
                System.out.println("[Thread 1] Committed and Locks Released.");
            } catch (Exception e) {
                System.out.println("[Thread 1] ERROR: " + e.getMessage());
                if (txnA != null) {
                    try { engine.rollback(txnA); } catch (Exception ignored) {}
                }
            }
        });

        Thread t2 = new Thread(() -> {
            Transaction txnB = null;
            try {
                Thread.sleep(500); // Give Thread 1 a 500ms head start
                System.out.println("[Thread 2] Starting Transaction B...");
                txnB = engine.begin();
                
                System.out.println("[Thread 2] Attempting to read Key 50 (Requesting Shared Lock)...");
                System.out.println("[Thread 2] BLOCKED! Waiting for Thread 1 to release the lock...");
                
                // This will block for exactly 2000ms and then throw the RuntimeException we wrote in AriesEngine!
                byte[] data = engine.read(txnB, 50); 
                
                System.out.println("[Thread 2] Read success: " + new String(data));
                engine.commit(txnB);
            } catch (Exception e) {
                System.out.println("[Thread 2] CAUGHT EXPECTED EXCEPTION: " + e.getMessage());
                if (txnB != null) {
                    try { engine.rollback(txnB); } catch (Exception ignored) {}
                }
            }
        });

        t1.start();
        t2.start();

        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return "Test Complete! You just witnessed Strict 2PL Isolation.";
    }
    
    

    // --- NEW: SYSTEM CRASH SIMULATION ---

    @Command(name = "crash", description = "Simulate a brutal power failure by instantly halting the JVM.")
    public String simulateCrash() {
        System.out.println("💥 SIMULATING POWER FAILURE... KICKING THE PLUG!");
        // halt(1) instantly kills the process, completely bypassing the @PreDestroy hook!
        Runtime.getRuntime().halt(1);
        return ""; // This line is never reached
    }

    @PreDestroy
    public void cleanup() {
        if (engine != null) {
            try {
                // If the user closed the app normally without committing, roll back.
                // (Note: The 'crash' command bypasses this entirely to simulate a real hardware failure).
                if (currentTxn != null) {
                    System.out.println("WARN: Uncommitted transaction detected on shutdown. Rolling back...");
                    engine.rollback(currentTxn);
                }
                engine.close();
                System.out.println("SUCCESS: Database engine closed and flushed to disk.");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}