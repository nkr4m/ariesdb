# AriesDB CLI Quick Start Guide

Welcome to the AriesDB CLI! This guide will help you build, launch, and interact with your custom database engine directly from your terminal.

## 🛠️ Build & Run

1. **Compile the project:** 
   Navigate to the root directory of your project and build it using Maven (skipping tests for a faster build):
   ```bash
   mvn clean install -DskipTests
   ```

2. **Boot the CLI:**
   Launch the Spring Shell interface. *(Note: Adjust the `-pl` flag if your CLI module is named differently)*:
   ```bash
   mvn spring-boot:run -pl aries-cli
   ```

---

## 💻 Command Reference

Once inside the interactive Spring Shell prompt (`shell:>` or `aries:>`):

### 1. Boot the Engine
Initializes the database files (defaults to `./aries_data/testdb.db` and `.idx`) and recovers from the WAL if a crash occurred.
```bash
start testdb
```

### 2. Stress Test & Insertion
Verify the B-Tree page splitting logic by inserting a bulk sequence of records instantly.
```bash
stress 1000
```
Insert a single specific record manually:
```bash
insert 42 "AriesDB Core Node"
```

### 3. Read Operations
Fetch data using the primary key. This traverses the B-Tree index to find the RID and fetches the heap payload.
```bash
read 999
read 42
```

### 4. Update & Delete
Updates use an out-of-place write mechanism, while deletes mark the record as a lazy tombstone.
```bash
update 42 "AriesDB Core Node v2"
delete 42
```

### 5. Persistence & Checkpointing
Force all dirty pages in the Heap and Index Buffer Pools to flush to physical disk and save the WAL offset.
```bash
checkpoint
```

### 6. Safe Shutdown
Gracefully close the CLI. The shutdown hook will automatically flush pending buffers and release file locks.
```bash
exit
```