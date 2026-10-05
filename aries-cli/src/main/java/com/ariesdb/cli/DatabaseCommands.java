package com.ariesdb.cli;

import com.ariesdb.core.EngineAPI;
import com.ariesdb.core.RID;
import com.ariesdb.storage.HeapStorageEngine;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.stereotype.Component;

@Component
public class DatabaseCommands {

    private EngineAPI engine = new HeapStorageEngine();
    private boolean isRunning = false;

    @Command(name = "start", description = "Boot up the database engine.")
    public String startDB(@Argument(index = 0, defaultValue = "testdb") String dbName) {
        try {
            engine.start(dbName);
            isRunning = true;
            return "SUCCESS: AriesDB started. File bound to: " + dbName + ".db";
        } catch (Exception e) {
            return "ERROR: Failed to start database - " + e.getMessage();
        }
    }

    @Command(name = "insert", description = "Insert a string record into the database.")
    public String insertRecord(@Argument(index = 0) String data) {
        if (!isRunning) return "ERROR: Please run 'start' first.";
        
        try {
            RID rid = engine.insert(data.getBytes());
            return "INSERT 0 1 -> Stored at physical address: " + rid.toString();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Command(name = "read", description = "Read a record directly from a physical RID.")
    public String readRecord(@Argument(index = 0) int pageId, @Argument(index = 1) int slotId) {
        if (!isRunning) return "ERROR: Please run 'start' first.";

        try {
            RID rid = new RID(pageId, slotId);
            byte[] rawBytes = engine.read(rid);
            String data = new String(rawBytes);
            return "Data: [" + data + "]";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
}