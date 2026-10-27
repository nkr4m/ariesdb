# AriesDB

A custom-built, disk-backed, page-oriented database engine implemented in Java. AriesDB is designed from the ground up following enterprise-grade storage architectures and the ARIES recovery protocol. It successfully bridges the gap between instant RAM access and durable, physical disk storage.

## Core Architecture

AriesDB separates concerns into dedicated storage managers, ensuring robust scaling and isolation between raw data and index structures.

*   **Dual-File Storage System:** The engine operates on two distinct physical files: `.db` (Heap Data) and `.idx` (B-Tree Index). Each file is managed by its own isolated `BufferPoolManager` to prevent Page ID collisions.
*   **Page-Oriented Layout:** Disk I/O is optimized using 4096-byte (4KB) frames. 
    *   **Heap Data:** Uses a `SlottedPage` layout with a dynamic directory at the end of the frame to manage variable-length string payloads and handle fragmentation.
    *   **Index Data:** Uses a `BMinusTreePage` layout with strict, fixed-width byte offsets for instantaneous array access (250 Keys, 251 Child Pointers, 250 RIDs per page).
*   **Buffer Pool Manager:** Implements a STEAL / NO-FORCE caching policy. 4KB pages are loaded into RAM strictly on-demand and evicted using a Clock Replacement algorithm, shielding the system from slow random disk reads.
*   **Write-Ahead Logging (WAL):** All modifications are instantly appended to a sequential `testdb.log` file before flushing pages to the physical disk. On system crash, the engine reads the WAL backward and physically re-applies missing operations to the stale 4KB frames.

## Project Structure

*   `ariesdb-parent`: The root Maven project managing dependencies.
*   `aries-engine-heap`: The core storage engine containing the `DiskManager`, `BufferPoolManager`, `LogManager`, `DiskDataStore`, and `DiskBMinusTreeStore`.
*   `aries-cli`: A Spring Shell interactive command-line interface for booting the engine, executing queries, and triggering checkpoints.

## Getting Started

### 1. Build the Project
Compile the multi-module Maven project from the root directory:
```bash
mvn clean install -DskipTests
```

### 2. Run the CLI
Boot the interactive Spring Shell prompt:
```bash
mvn spring-boot:run -pl aries-cli
```


### 3. Core Commands & Stress Test Example
Once inside the CLI prompt, initialize the database and run a stress test to trigger dynamic B-Tree page splits. The engine handles 1,000 durable insertions in under 600ms.
```bash
$>start testdb
SUCCESS: AriesDB started. Files bound to: ./aries_data/testdb

$>stress 1000
STRESS TEST SUCCESS -> Inserted 1000 records in 583ms. B-Tree splits executed.

$>insert 42 "AriesDB Core Node"
INSERT 0 1 -> Record stored and indexed under Key: 42

$>read 42
Data: [Payload-42]

$>checkpoint
Starting Checkpoint...
Checkpoint complete at log offset: 77983
CHECKPOINT SUCCESS -> Dirty pages flushed and WAL offset saved.
```
