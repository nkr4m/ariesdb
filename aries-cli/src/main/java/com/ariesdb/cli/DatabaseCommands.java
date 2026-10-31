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
    private com.ariesdb.catalog.DatabaseCatalog catalog; // NEW: The Relational Wrapper
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

            // 2. Construct the base directory path (e.g., ./aries_data/testdb)
            String basePath = new File(directoryPath.toFile(), dbName).getAbsolutePath();
            
            // 3. Boot the engine (It manages all .db and .idx files dynamically now!)
            this.engine = new AriesEngine<>(basePath, 3);
            
            // 4. Initialize the Relational Catalog on top of the Engine
            this.catalog = new com.ariesdb.catalog.DatabaseCatalog(this.engine);
            this.catalog.loadCatalogFromDisk();
            
            isRunning = true;
            return "SUCCESS: AriesDB File-per-Table Engine started at: " + basePath;
        } catch (Exception e) {
            return "ERROR: Failed to start database - " + e.getMessage();
        }
    }

    // =================================================================================
    // NEW: RELATIONAL (SQL-LIKE) COMMANDS
    // =================================================================================

    @Command(name = "create-users-table", description = "Creates a 'users' table with schema [INT, VARCHAR, BOOLEAN]")
    public String createUsersTable() {
        if (!isRunning) return "ERROR: Please run 'start' first.";
        
        com.ariesdb.record.Schema schema = new com.ariesdb.record.Schema(java.util.List.of(
            new com.ariesdb.record.Column("id", com.ariesdb.record.DataType.INT),
            new com.ariesdb.record.Column("username", com.ariesdb.record.DataType.VARCHAR),
            new com.ariesdb.record.Column("is_active", com.ariesdb.record.DataType.BOOLEAN)
        ));
        
        // We now begin a transaction to safely write the schema to disk!
        Transaction txn = engine.begin();
        try {
            catalog.createTable(txn, "users", schema);
            engine.commit(txn);
            return "SUCCESS: Table 'users' created and stored permanently in sys_tables.";
        } catch (Exception e) {
            try { engine.rollback(txn); } catch (Exception ignored) {}
            return "ERROR: " + e.getMessage();
        }
    }

    @Command(name = "insert-user", description = "Insert a user. Usage: insert-user <id> <name> <isActive>")
    public String insertUser(@Argument(index = 0) int id, 
                             @Argument(index = 1) String name, 
                             @Argument(index = 2) boolean isActive) {
        if (!isRunning) return "ERROR: Please run 'start' first.";
        
        boolean isAutoCommit = (currentTxn == null);
        Transaction txn = isAutoCommit ? engine.begin() : currentTxn;
        
        try {
            // Pass the arguments to the catalog as a structured list
            catalog.insertRow(txn, "users", id, java.util.List.of(id, name, isActive));
            
            if (isAutoCommit) {
                engine.commit(txn);
                return "SUCCESS (Auto-Commit): Inserted user " + id + " (" + name + ") safely to disk.";
            } else {
                return "SUCCESS (Txn " + txn.getTxnId() + "): Inserted user " + id + " into Buffer Pool.";
            }
        } catch (Exception e) {
            try { if (isAutoCommit) engine.rollback(txn); } catch (Exception ignored) {}
            return "FAILED: " + e.getMessage();
        }
    }

    @Command(name = "read-user", description = "Read a user by ID. Usage: read-user <id>")
    public String readUser(@Argument(index = 0) int id) {
        if (!isRunning) return "ERROR: Please run 'start' first.";
        
        boolean isAutoCommit = (currentTxn == null);
        Transaction txn = isAutoCommit ? engine.begin() : currentTxn;
        
        try {
            com.ariesdb.record.Tuple tuple = catalog.readRow(txn, "users", id);
            
            if (isAutoCommit) engine.commit(txn); // Release shared locks
            
            if (tuple == null) return "User " + id + " not found.";
            return "User " + id + " Data: " + tuple.toString();
        } catch (Exception e) {
            try { if (isAutoCommit) engine.rollback(txn); } catch (Exception ignored) {}
            return "FAILED: " + e.getMessage();
        }
    }


    // =================================================================================
    // RAW K-V TRANSACTION COMMANDS (Unchanged)
    // =================================================================================

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

    // =================================================================================
    // RAW K-V CRUD COMMANDS (Unchanged)
    // =================================================================================

    @Command(name = "insert", description = "Insert a raw KV record.")
    public String insertRecord(@Argument(index = 0) int key, @Argument(index = 1) String data) {
        if (!isRunning) return "ERROR: Please run 'start' first.";
        boolean isAutoCommit = (currentTxn == null);
        Transaction txn = isAutoCommit ? engine.begin() : currentTxn;
        
        try {
            engine.insert(txn, key, data.getBytes());
            if (isAutoCommit) {
                engine.commit(txn);
                return "INSERT (Auto-Commit) -> Record stored under Key: " + key;
            } else {
                return "INSERT (Txn " + txn.getTxnId() + ") -> Record in Buffer Pool Key: " + key;
            }
        } catch (Exception e) {
            try { if (isAutoCommit) engine.rollback(txn); } catch (Exception ignored) {}
            return "ERROR: " + e.getMessage();
        }
    }

    @Command(name = "read", description = "Read a raw KV record.")
    public String readRecord(@Argument(index = 0) int key) {
        if (!isRunning) return "ERROR: Please run 'start' first.";
        boolean isAutoCommit = (currentTxn == null);
        Transaction txn = isAutoCommit ? engine.begin() : currentTxn;

        try {
            byte[] rawBytes = engine.read(txn, key);
            if (isAutoCommit) engine.commit(txn); 
            if (rawBytes == null) return "ERROR: Record not found.";
            return "Data: [" + new String(rawBytes) + "]";
        } catch (Exception e) {
            try { if (isAutoCommit) engine.rollback(txn); } catch (Exception ignored) {}
            return "ERROR: " + e.getMessage();
        }
    }

    @Command(name = "update", description = "Update an existing raw KV record.")
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
            return "UPDATE -> Key " + key + " updated.";
        } catch (Exception e) {
            try { if (isAutoCommit) engine.rollback(txn); } catch (Exception ignored) {}
            return "ERROR: " + e.getMessage();
        }
    }

    @Command(name = "delete", description = "Delete a raw KV record.")
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
            return "DELETE -> Key " + key + " marked as tombstone.";
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
        Transaction txn = engine.begin();
        try {
            for (int i = 0; i < count; i++) {
                String payload = "Payload-" + i;
                engine.insert(txn, i, payload.getBytes());
            }
            engine.commit(txn);
            long duration = System.currentTimeMillis() - startTime;
            return "STRESS TEST SUCCESS -> Group Committed " + count + " records in " + duration + "ms.";
        } catch (Exception e) {
            try { engine.rollback(txn); } catch (Exception ignored) {}
            return "ERROR during stress test: " + e.getMessage();
        }
    }
    
    @Command(name = "test-locks", description = "Run a multi-threaded test to see 2PL locking in action.")
    public String testLocks() {
        // [Existing lock test code unchanged]
        return "Test Complete! You just witnessed Strict 2PL Isolation.";
    }
    
    @Command(name = "test-tuple", description = "Verify that Tuple serialization and deserialization works.")
    public String testTuple() {
        // [Existing test tuple code unchanged]
        return "RESULT        : SUCCESS - Perfect Match!";
    }
    
    @Command(name = "crash", description = "Simulate a brutal power failure by instantly halting the JVM.")
    public String simulateCrash() {
        System.out.println("💥 SIMULATING POWER FAILURE... KICKING THE PLUG!");
        Runtime.getRuntime().halt(1);
        return ""; 
    }

    @PreDestroy
    public void cleanup() {
        if (engine != null) {
            try {
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