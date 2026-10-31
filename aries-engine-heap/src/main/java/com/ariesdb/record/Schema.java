package com.ariesdb.record;

import java.util.List;

public class Schema {
    private final List<Column> columns;

    public Schema(List<Column> columns) {
        this.columns = columns;
    }

    public List<Column> getColumns() { return columns; }
    
    public Column getColumn(int index) { 
        return columns.get(index); 
    }
    
    public int getColumnCount() { 
        return columns.size(); 
    }
}