# Insights: Cache Configuration & Encoding/Marshalling

Source PDFs:
- Red_Hat_Data_Grid-8.6-Configuring_Data_Grid_Caches-en-US.pdf (181 pages)
- Red_Hat_Data_Grid-8.6-Cache_Encoding_and_Marshalling-en-US.pdf (39 pages)

---

## 1. Cache Modes

| Mode | Description | PoC Relevance |
|------|-------------|---------------|
| Local | Single node, no clustering | Dev/test only |
| Simple | Local without transactions/notifications/persistence support | Fastest local option |
| Replicated | Full copy on every node | Small datasets, high-read |
| Distributed | Partitioned across nodes via consistent hashing | **Primary choice for the organization** |
| Invalidation | Local copies invalidated on write | JPA/Hibernate L2 cache |
| Scattered | Like distributed but single owner + primary always stores | Lower write latency, higher read latency |

Key parameter for distributed: `numOwners` (default=2). Controls how many copies exist. Higher = more resilient, more memory, slower writes.

## 2. Cache Templates and Wildcards

- Templates defined with `*` suffix act as wildcards: `<distributed-cache-configuration name="tenant-*">` matches any cache created with name starting with `tenant-`.
- Templates support inheritance via `configuration` attribute.
- **the organization multi-tenancy insight**: Use wildcard templates like `tenant-*` to auto-configure caches per tenant without individual definitions.
- Cache configurations can be defined in XML, JSON, or YAML formats.

## 3. Statistics and Monitoring

- Metrics exposed at vendor scope: gauges (point-in-time) and histograms (distributions).
- Enable per-cache: `<statistics enabled="true"/>`.
- Enable at container level: `<cache-container statistics="true">`.
- JMX MBeans: `org.infinispan:type=Cache` and `org.infinispan:type=CacheManager`.
- Remote JMX: port 9999, requires security realm with `controlRole`/`monitorRole`.
- Cross-site monitoring: REST API polling (`GET /rest/v2/container/x-site/backups`) or Prometheus metrics (`vendor_cache_manager_x_site_admin_status`).
- State transfer metrics available during topology changes.
- `legacy=false` for new metrics format (recommended).

## 4. JVM Memory and Eviction

### Storage types
- **HEAP** (default): standard JVM heap, subject to GC.
- **OFF_HEAP**: native memory outside JVM heap. Avoids GC pressure but REQUIRES eviction configured (no automatic memory limit).

### Eviction strategies
- `max-count`: max entries in cache.
- `max-size`: max aggregate size (requires encoding with known size like ProtoStream, NOT POJOs).
- `when-full`:
  - `REMOVE` (default): evicts using approximation of LRU.
  - `MANUAL`: throws `ContainerFullException`, app must handle.
  - `EXCEPTION`: like MANUAL.
- Heap uses TinyLFU (Caffeine-based), off-heap uses custom LRU.

### Critical warning for the organization
OFF_HEAP without eviction configured = unbounded native memory growth = OOM kill by OS/OCP. Always set `max-count` or `max-size` with off-heap.

## 5. Expiration

- **lifespan**: TTL from creation. Entry dies after N milliseconds regardless of access.
- **maxIdle**: TTL from last access. Resets on every read/write.
- Both can be combined. Entry expires when EITHER fires.
- **maxIdle limitations**:
  - NOT compatible with persistent cache stores.
  - NOT compatible with invalidation mode.
  - In clustered caches, maxIdle sends `touch` commands to all owners on every access → increased latency. Avoid on hot keys or latency-sensitive caches.
- Expiration `lifespan=-1` and `maxIdle=-1` means never expire (default).
- Expiration reaper thread interval configurable via `<expiration interval="..."/>`.

## 6. Persistent Storage

### Store types
| Store | Use Case | Notes |
|-------|----------|-------|
| SoftIndexFileStore | Default file-based | Append-only log + B+ tree index, segmented |
| JDBC String-Keyed | RDBMS persistence | One row per entry, `_META` table for metadata |
| SQL Table Store | Single table mapping | No expiration/segment metadata stored |
| SQL Query Store | Multi-table with JOINs | No expiration/segment metadata stored |
| RocksDB | High-perf embedded KV | Not included by default, needs module install |
| Remote Store | Chain to another DG cluster | Uses Hot Rod protocol, supports segmentation |

### Write modes
- **Write-through** (default): synchronous write to store on every cache write.
- **Write-behind**: async write via modification queue. `fail-silently=true` drops failed writes silently. Configurable queue size and thread pool.

### Passivation
- `passivation=true`: entries written to store ONLY on eviction or shutdown, NOT on every write.
- NOT supported with transactional caches.
- NOT supported with shared stores.
- When passivation enabled + eviction: evicted entry goes to store, loaded back on access.
- When passivation disabled: store is a full mirror of cache (write-through/write-behind).

### Global persistent location
- Preserves cluster topology data, internal caches.
- Lock strategies for dangling locks: `FAIL` (default), `PURGE`, `IGNORE`.
- On OpenShift: must use PersistentVolumes for this.

### JDBC connection factories
- **connection-pool** (Agroal): for standalone server. Connection pool with min/max/blocking timeout.
- **managed-datasource**: JNDI lookup in app server (EAP). Uses server-managed pool.
- **simple-connection**: one connection at a time. Dev/test ONLY, no pool.

### SQL store warnings
- SQL Table/Query stores do NOT store metadata (expiration, segments, versioning). If you need expiration on JDBC-persisted data, use the String-Keyed store instead.

## 7. Network Partitions (Split-Brain)

### Detection
- FD_ALL failure detector in JGroups stack.
- ISPN100011 log message when partition detected.

### Partition handling strategies
| Strategy | Behavior | Trade-off |
|----------|----------|-----------|
| ALLOW_READ_WRITES (default) | Full availability during split | Risk of data inconsistency |
| DENY_READ_WRITES | Only if ALL replicas in partition | Consistency guaranteed, availability lost |
| ALLOW_READS | Reads allowed, writes denied unless all replicas present | Middle ground |

### Merge policies
| Policy | Behavior |
|--------|----------|
| NONE (default) | No conflict resolution, segments dropped for non-primary |
| PREFERRED_ALWAYS | Majority value wins |
| PREFERRED_NON_NULL | First non-null value wins |
| REMOVE_ALL | Conflicting entries removed |
| Custom | Implement `EntryMergePolicy` API |

### the organization recommendation
For a healthcare cache-as-a-service: use `DENY_READ_WRITES` + `PREFERRED_NON_NULL` merge policy. Healthcare data integrity > availability. Verify with `GET /rest/v2/container/health` and log ISPN100011.

## 8. Security Authorization (RBAC)

### Built-in roles
| Role | Permissions |
|------|------------|
| admin | ALL |
| deployer | ALL_READ, ALL_WRITE, LISTEN, EXEC, MONITOR, CREATE |
| application | ALL_READ, ALL_WRITE, LISTEN, EXEC, MONITOR |
| observer | ALL_READ, MONITOR |
| monitor | MONITOR |

### Cache-level permissions
READ, WRITE, EXEC, LISTEN, BULK_READ, BULK_WRITE, LIFECYCLE, ADMIN, MONITOR, ALL, ALL_READ, ALL_WRITE.

### Role mappers
- **Cluster role mapper** (default): stores principal→role mappings in cluster registry. Dynamic at runtime via CLI/REST.
- **Identity role mapper**: uses principal name directly as role name.
- **Common name role mapper**: extracts CN from LDAP DN as role name. Useful with certificates/LDAP.

### Name rewriters
- Case Principal Transformer: normalize to upper/lowercase.
- Regex Principal Transformer: extract role from complex DN patterns.

### the organization multi-tenancy insight
- Create custom roles per tenant: `user roles create --permissions=ALL_READ,ALL_WRITE tenant-a-role`
- Grant to users: `user roles grant --roles=tenant-a-role user-tenant-a`
- Combine with cache authorization: `<authorization roles="tenant-a-role"/>` on each tenant's cache.
- Use `authorization.group-only-mapping=false` to map user principals (not just groups) to roles.

## 9. Transactions

### Transaction modes
| Mode | Description |
|------|-------------|
| NONE (default) | No transactions |
| FULL_XA | XA with recovery |
| NON_DURABLE_XA | XA without recovery |
| NON_XA | Synchronization-based (lighter than XA) |
| BATCH | Lightweight batch grouping |

### Isolation levels
- **READ_COMMITTED**: second read in same TX may see concurrent write's update.
- **REPEATABLE_READ** (default): second read returns same value as first within TX.

### Locking
- **Pessimistic**: lock on write, held until commit/rollback. Better for high contention.
- **Optimistic** (default): lock at prepare time only. Better for low contention. Write skew checks enabled with REPEATABLE_READ.
- Lock striping: disabled by default (potential deadlocks). `<locking striping="true"/>` for fixed-overhead locking.
- Lock timeout: `<locking acquire-timeout="10s"/>`.

### Batching
- Lighter than full JTA. Use `cache.startBatch()` / `cache.endBatch(true|false)`.
- Changes replicated only at batch completion → reduces replication chatter.
- No TransactionManager needed.
- Use batching when DG is the only participant; use JTA when multiple resources involved.

### Transaction recovery
- For FULL_XA only.
- Recovery cache: local cache storing in-doubt transaction state.
- Reconciliation via JMX: `showInDoubtTransactions`, `forceCommit`, `forceRollback`.
- Recovery not enabled by default.

## 10. Locking and Concurrency

- MVCC (Multi-Versioned Concurrency Control): readers never block, writers queue per entry.
- `MVCCEntry` wraps entries during write to isolate concurrent readers.
- In clustered caches, primary owner responsible for locking keys.
- `concurrencyLevel` tunes striped lock container size (default matches ConcurrentHashMap).
- Data versioning: simple (internal, for write skew checks) or external (Hibernate integration).

## 11. Clustered Counters

### Types
- **StrongCounter**: single key, linearizable, supports bounds and compare-and-set/swap.
  - Use for: distributed ID generation, rate limiting (with `upper-bound` + `lifespan`).
  - Higher contention (single key).
- **WeakCounter**: multiple keys (controlled by `concurrency-level`), lower contention, no bounds.
  - Use for: statistics collection, high-throughput counting.
  - `reset()` is NOT atomic.

### Storage
- `PERSISTENT`: survives restarts (requires global-state).
- `VOLATILE`: lost on restart.

### Configuration
- Can be defined in XML config or created at runtime via `CounterManager` API.
- `CounterManager` available via `EmbeddedCounterManagerFactory` (embedded) or `RemoteCounterManagerFactory` (Hot Rod).
- `CounterListener` for change notifications.

---

## 12. Cache Encoding and Marshalling

### Encoding (media types)
- **application/x-protostream** (RECOMMENDED): Protobuf-based. Enables queries, multi-client interop (Hot Rod, REST, CLI, Console), server-side tasks.
- **application/x-jboss-marshalling**: legacy JBoss Marshalling. Migration path only.
- **application/x-java-serialized-object**: Java Serialization. Worse performance than ProtoStream.
- **application/x-java-object**: POJOs. Embedded caches only, NOT recommended for remote.
- **text/plain; charset=UTF-8**: text-based. Can convert to/from JSON, XML, ProtoStream.
- **application/json**: REST-friendly. DG auto-converts ProtoStream ↔ JSON.

### Critical rule
Without encoding config, remote caches store as raw `byte[]` without media type → unexpected behavior on conversion. **Always configure encoding explicitly.**

### ProtoStream marshalling
- Use `@ProtoField`, `@ProtoFactory`, `@Proto` annotations on Java classes.
- `@ProtoSchema` on interface generates `SerializationContextInitializer` at compile time.
- `protostream-processor` annotation processor generates schemas + marshallers.
- Protobuf does NOT support circular objects.
- `@ProtoAdapter` for third-party classes you can't annotate directly.
- `@ProtoEnumValue` for enum marshalling.

### Registering schemas on Data Grid Server
- Console: Schema tab → Add Protobuf schema.
- CLI: `schema --upload=person.proto person`.
- REST: `POST /rest/v2/schemas/<name>`.
- Hot Rod: `RemoteSchemasAdmin` API.
- JAR in `$RHDG_HOME/server/lib` with `META-INF/services` file → auto-registered at startup. But NOT saved in `___protobuf_metadata` cache, so must be deployed to ALL nodes.

### Schema compatibility
- Levels: UNRESTRICTED, LENIENT, STRICT (default).
- STRICT enforces: no reusing reserved fields/IDs, no changing field types/names/IDs, no removing reserved fields.

### Alternative marshallers
- JBoss Marshalling: `GenericJBossMarshaller`, requires `infinispan-jboss-marshalling` dependency + deserialization allowlist.
- Java Serialization: `JavaSerializationMarshaller`, requires allowlist. Worse performance.
- Custom: implement `Marshaller` interface.
- All non-ProtoStream marshallers require deserialization allowlist (security).

### Data conversion (DataFormat API)
- Hot Rod clients can override marshaller per operation via `DataFormat.builder()`.
- `keyMarshaller()` / `valueMarshaller()` to override individually.
- WARNING: Different key marshallers/encodings can interfere with client intelligence routing → extra network hops.
- Embedded caches: `AdvancedCache.withMediaType()` for on-demand conversion.

### Schema management API
- `RemoteSchemasAdmin`: create, update, delete, retrieve schemas programmatically.
- `retrieveError()` / `retrieveAllSchemaErrors()` to check for validation errors.
- Requires CREATE permission (deployer role minimum).

### Custom JARs on server
- Place in `$RHDG_HOME/server/lib` (NOT `$RHDG_HOME/lib`).
- JARs loaded at startup only → rolling restart required for changes.
- On OpenShift: custom image or init container to inject JARs.

---

## Key Decision Points for the organization PoC

1. **Cache mode**: Distributed with `numOwners=2` for balance of resilience and performance.
2. **Encoding**: Always `application/x-protostream`. Non-negotiable for multi-client scenarios (Spring, EAP, RESP, REST).
3. **Eviction**: Configure `max-count` or `max-size` on every cache. Off-heap only with eviction.
4. **Expiration**: Use `lifespan` for session-like data. Avoid `maxIdle` in clustered caches unless latency increase is acceptable.
5. **Partition handling**: `DENY_READ_WRITES` + `PREFERRED_NON_NULL` for healthcare data integrity.
6. **RBAC**: Custom roles per tenant, cache-level authorization, cluster role mapper for dynamic management.
7. **Transactions**: NONE for simple caches, BATCH for grouped writes, NON_XA for Hibernate/JPA integration.
8. **Persistence**: SoftIndexFileStore for general use; JDBC String-Keyed for database backing with expiration support.
9. **Marshalling**: ProtoStream with `@ProtoSchema` annotation processor. Register schemas on server via REST or CLI.
