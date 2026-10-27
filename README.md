# AriesDB

AriesDB is a custom-built, disk-backed, page-oriented database engine implemented in Java. It uses a heap file for record data, a B-Tree index file for keys, and a write-ahead log (WAL) for recovery.

## Core Architecture

* **Dual-file storage:** Each database uses a `.db` heap file and a `.idx` B-Tree index file. Each file has its own `BufferPoolManager`.
* **Page-oriented layout:** Storage uses 4096-byte pages. Heap pages use a slotted-page layout for variable-length records; index pages store fixed-width B-Tree entries.
* **Buffer pool:** Pages are loaded on demand and evicted with a Clock replacement algorithm. The buffer pools use a STEAL / NO-FORCE policy.
* **Write-ahead log:** Changes are appended to a sequential `.log` file before being applied to the data and index pages. On startup, the engine uses the WAL and latest checkpoint to recover the database.
* **Transactions:** The CLI supports explicit `begin`, `commit`, and `rollback` commands. Writes issued outside an explicit transaction are automatically committed.
* **Two-phase locking:** Transactions acquire shared locks for reads and exclusive locks for writes. Locks are held until commit or rollback; lock acquisition waits for up to two seconds before timing out.

## Project Structure

* `ariesdb-parent`: Root Maven project for the modules.
* `aries-engine-heap`: Storage engine, buffer pools, heap and B-Tree stores, WAL, checkpointing, and transaction management.
* `aries-cli`: Spring Shell interface for managing the engine and running database commands.

## Getting Started

### Build

From the repository root, build the multi-module project:

```bash
mvn clean install -DskipTests
```

### Run the CLI

```bash
mvn spring-boot:run -pl aries-cli
```

## CLI Commands

Commands below are entered at the Spring Shell prompt.

### Start a database

Starts the default database, `testdb`, under `./aries_data`. The database name can be changed:

```text
start
start mydb
```

The engine creates or opens the corresponding `.db`, `.idx`, and `.log` files.

### Run the stress test

The stress command inserts sequential integer keys starting at `0` in one transaction. Its optional count defaults to `1000`.

```text
stress
stress 1000
```

Example output:

```text
STRESS TEST SUCCESS -> Group Committed 1000 records in <elapsed>ms. B-Tree splits executed.
```

The elapsed time depends on the machine and storage device; it is informational, not a performance guarantee. Run the test against a new or empty database: running it again with the same keys will encounter existing records.

### Read, insert, update, and delete

```text
read 42
insert 1000 "AriesDB Core Node"
update 1000 "AriesDB Core Node v2"
delete 1000
```

`read` retrieves a record by primary key. `update` writes a replacement record, and `delete` marks the record as deleted.

### Use an explicit transaction

Within an explicit transaction, inserts, updates, and deletes remain part of that transaction until committed or rolled back:

```text
begin
insert 1001 "transactional record"
commit

begin
insert 1002 "record to undo"
rollback
```

If no transaction is active, each write command uses its own transaction and commits automatically.

### Demonstrate transaction locking

The `test-locks` command runs two threads against key `50`: one updates the key and holds its exclusive lock for three seconds, while the other attempts a read using a shared lock. Since lock acquisition times out after two seconds, the read demonstrates contention by timing out before the writer commits.

```text
test-locks
```

The command seeds key `50` with `Initial` before starting the contention test. Run it on a database where key `50` can be inserted.

### Checkpoint and shutdown

```text
checkpoint
exit
```

`checkpoint` flushes dirty pages and saves the WAL offset. `exit` shuts down gracefully and flushes pending buffers.

### Simulate an abrupt crash

```text
crash
```

This command immediately halts the JVM and bypasses graceful shutdown. Use a disposable database for crash-recovery experiments; starting the same database again invokes WAL recovery.
