package com.ariesdb.catalog;

import com.ariesdb.engine.AriesEngine;
import com.ariesdb.record.Column;
import com.ariesdb.record.DataType;
import com.ariesdb.record.Schema;
import com.ariesdb.record.Tuple;
import com.ariesdb.transaction.Transaction;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DatabaseCatalog {
    private final AriesEngine<Integer> engine;
    
    private final Map<String, Integer> tableIds = new ConcurrentHashMap<>();
    private final Map<String, Schema> tableSchemas = new ConcurrentHashMap<>();
    private int nextTableId = 1;

    private final Schema sysTablesSchema;
    private final Schema sysColumnsSchema;

    public DatabaseCatalog(AriesEngine<Integer> engine) {
        this.engine = engine;
        
        this.sysTablesSchema = new Schema(List.of(
            new Column("table_id", DataType.INT),
            new Column("table_name", DataType.VARCHAR)
        ));
        
        this.sysColumnsSchema = new Schema(List.of(
            new Column("column_id", DataType.INT),
            new Column("table_id", DataType.INT),
            new Column("column_name", DataType.VARCHAR),
            new Column("data_type", DataType.VARCHAR)
        ));
    }

    public void createTable(Transaction txn, String tableName, Schema schema) throws Exception {
        if (tableSchemas.containsKey(tableName)) throw new IllegalArgumentException("Table already exists");
        
        int tableId = nextTableId++;
        tableIds.put(tableName, tableId);
        tableSchemas.put(tableName, schema);
        
        // 1. Tell the Engine to create the physical OS files (e.g., users.db, users.idx)
        engine.createTable(tableName);
        
        // 2. Save table definition to sys_tables
        Tuple tableTuple = new Tuple(List.of(tableId, tableName));
        engine.insert(txn, "sys_tables", tableId, tableTuple.serialize(sysTablesSchema));
        
        // 3. Save columns to sys_columns
        for (int i = 0; i < schema.getColumnCount(); i++) {
            Column col = schema.getColumn(i);
            int colKey = (tableId * 100) + i; // Unique ID for the column
            Tuple colTuple = new Tuple(List.of(colKey, tableId, col.getName(), col.getType().name()));
            engine.insert(txn, "sys_columns", colKey, colTuple.serialize(sysColumnsSchema));
        }
    }

    public void insertRow(Transaction txn, String tableName, int primaryKey, List<Object> values) throws Exception {
        Schema schema = tableSchemas.get(tableName);
        Tuple tuple = new Tuple(values);
        
        // File-per-table routing happens natively inside AriesEngine!
        engine.insert(txn, tableName, primaryKey, tuple.serialize(schema));
    }

    public Tuple readRow(Transaction txn, String tableName, int primaryKey) throws Exception {
        Schema schema = tableSchemas.get(tableName);
        
        byte[] rawBytes = engine.read(txn, tableName, primaryKey);
        if (rawBytes == null) return null;
        
        return Tuple.deserialize(rawBytes, schema);
    }
    
    public void loadCatalogFromDisk() throws Exception {
        Transaction txn = engine.begin();
        int loadedCount = 0;
        try {
            // Scan for table IDs (assuming a max of 100 for this test)
            for (int tId = 1; tId <= 100; tId++) {
                byte[] tableBytes = engine.read(txn, "sys_tables", tId);
                if (tableBytes != null) {
                    Tuple tableTuple = Tuple.deserialize(tableBytes, sysTablesSchema);
                    String tableName = (String) tableTuple.getValue(1);
                    
                    nextTableId = Math.max(nextTableId, tId + 1);
                    
                    // Skip caching the system tables themselves
                    if (tableName.equals("sys_tables") || tableName.equals("sys_columns") || tableName.equals("default")) {
                        continue;
                    }

                    // Read this table's columns from sys_columns
                    java.util.List<Column> columns = new java.util.ArrayList<>();
                    for (int cIdx = 0; cIdx < 50; cIdx++) {
                        int colKey = (tId * 100) + cIdx; 
                        byte[] colBytes = engine.read(txn, "sys_columns", colKey);
                        if (colBytes == null) break;
                        
                        Tuple colTuple = Tuple.deserialize(colBytes, sysColumnsSchema);
                        String colName = (String) colTuple.getValue(2);
                        String typeStr = (String) colTuple.getValue(3);
                        columns.add(new Column(colName, DataType.valueOf(typeStr)));
                    }
                    
                    tableIds.put(tableName, tId);
                    tableSchemas.put(tableName, new Schema(columns));
                    loadedCount++;
                }
            }
            engine.commit(txn);
            System.out.println("CATALOG BOOTED: Loaded " + loadedCount + " user tables from disk.");
        } catch (Exception e) {
            engine.rollback(txn);
            throw e;
        }
    }
}