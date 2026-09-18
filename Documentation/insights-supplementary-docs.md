# Insights: Supplementary Data Grid 8.6 Documentation

Source documents (all Red Hat Data Grid 8.6 official docs):
- REST API (90 pages)
- Querying Data Grid Caches (55 pages)
- Using the Data Grid CLI (82 pages)
- Data Grid Code Tutorials (18 pages)
- Using the Memcached Protocol Endpoint (13 pages)

---

## 1. REST API

### Key Points for PoC

- REST endpoint built on Netty, supports HTTP/1.1 and HTTP/2 (p.10)
- Authentication configured via CLI `user` command — same RBAC model as Hot Rod (p.10)
- Supports multiple data formats: `application/json`, `application/xml`, `application/x-protostream`, `text/plain`, `application/octet-stream` (p.10-11)
- Automatic conversion between `application/x-protostream` and `application/json` when Protobuf schemas are registered (p.12)
- Cache creation via `POST /rest/v2/caches/{cacheName}` with XML or JSON configuration payload (p.15)
- Cache configuration supports XML, JSON, and YAML formats (p.15)
- Cache name limit: 255 characters max (p.15)
- Full CRUD on cache entries: PUT, GET, DELETE, HEAD (p.28-31)
- Cache statistics, health, and cluster info available via REST (p.35, 60-61)
- Cross-site replication operations controllable via REST (p.48-51)
- Backup and restore operations via REST (p.66-70)
- Security management via REST: RBAC roles, principals, permissions (p.86-90)
- CORS support with configurable rules per connector (p.13)
- Server tasks can be uploaded and executed via REST (p.85-86)
- Logging configuration can be modified at runtime via REST (p.83-84)
- Rolling upgrades between clusters via REST (p.51-53)
- Counters (strong and weak) fully manageable via REST including atomic operations: getAndSet, compareAndSet, compareAndSwap (p.53-56)
- Protobuf schemas registration via `___protobuf_metadata` cache (p.56-57)
- Container health status endpoint: `/rest/v2/cache-managers/{name}/health` (p.61)

### PoC Relevance
- REST API is the primary management and operations interface for "Cache as a Service"
- Health endpoints critical for OpenShift liveness/readiness probes
- RBAC management via REST enables multi-tenant administration
- Cache creation via REST enables self-service cache provisioning

---

## 2. Querying Data Grid Caches

### Key Points for PoC

- Data Grid uses Apache Lucene for indexing (p.8)
- Indexing must be enabled per cache via `<indexing>` element (p.8)
- Two data models for indexing: Protobuf messages (remote caches) and Java objects with `@Indexed` annotation (embedded caches) (p.8-9)
- Index storage options: filesystem (default, persists across restarts) or local-heap (faster but volatile) (p.10)
- Index startup modes: AUTO (default), purge, reindex, none (p.10-11)
- Indexing mode: auto (immediate) or manual (batch updates) (p.12)
- Index sharding for large datasets: `<index-sharding shards="6"/>` (p.15-16)
- Native indexing annotations: `@Indexed`, `@Basic`, `@Decimal`, `@Keyword`, `@Text`, `@Embedded` (p.16)
- Ickle query language: SQL-like syntax for Data Grid queries (p.19)
- Supports pagination, named parameters, projections, sorting, grouping, aggregation (p.19-26)
- Full-text queries: fuzzy, range, phrase, proximity, wildcard, regex, boosting (p.27-28)
- Spatial queries with `@GeoPoint` and `@GeoField` annotations (p.28-34)
- Remote cache querying via Hot Rod Java clients (p.35)
- Querying from Data Grid Console and CLI (p.39)
- Continuous queries: real-time notifications on query result changes (p.52-53)
- Query statistics and performance tuning available (p.55)

### PoC Relevance
- Querying is a differentiator vs Redis — Data Grid supports structured queries, not just key-value lookups
- Ickle queries via REST or Hot Rod can demonstrate advanced cache capabilities
- Continuous queries could be demonstrated for real-time event-driven scenarios

---

## 3. Data Grid CLI

### Key Points for PoC

- CLI connects remotely to Data Grid Server for data access and management (p.13)
- User creation: `bin/cli.sh user create admin -p changeme -g admin` (p.14)
- RBAC roles: admin (ALL), deployer (ALL_READ/WRITE/LISTEN/EXEC/MONITOR/CREATE), application (ALL_READ/WRITE/LISTEN/EXEC/MONITOR), observer (ALL_READ/MONITOR), monitor (MONITOR) (p.16)
- Groups support: create groups, assign roles to groups, add users to groups (p.15-16)
- Connect to server: `bin/cli.sh connect` (defaults to localhost:11222) (p.17)
- Connect to container on OpenShift: `bin/cli.sh connect -c https://podname:11222` (p.17)
- Resource navigation with `cd` / `ls` commands (p.18-19)
- Cache creation via CLI with XML/JSON/YAML configuration or from templates (p.22-27)
- Cache entry operations: `put`, `get`, `remove`, `clearcache` (p.30-31)
- Batch operations from files or interactive mode (p.33)
- CLI properties and persistent storage configuration (p.35)
- Trust store configuration for TLS connections (p.36)
- Counter management: create, increment, decrement (p.38-39)
- Cross-site replication operations: bring online/offline, push state, configure transfer mode (p.40-41)
- Backup and restore operations for entire clusters (p.42-43)
- Command reference (44 commands): add, alias, alter, availability, backup, benchmark, cache, cas, cd, clearcache, config, connect, container, counter, create, credentials, describe, disconnect, drop, encoding, get, help, index, install, logging, ls, migrate, patch, put, query, quit, rebalance, remove, reset, schema, server, shutdown, site, stats, task, troubleshoot, unalias, user, version (p.44-82)
- `benchmark` command for performance testing (p.48-49)
- `troubleshoot` command for diagnostics (p.78-79)
- `server connector ipfilter` for IP-based access control (p.73)

### PoC Relevance
- CLI is essential for initial setup: user creation, role assignment, cache creation
- `benchmark` command useful for performance validation during PoC
- `troubleshoot` command for diagnostics
- Group-based RBAC directly supports multi-tenant model

---

## 4. Data Grid Code Tutorials

### Key Points for PoC

- Three categories: Remote Caches, Embedded Caches, Spring/Spring Boot (p.1)
- Tutorials require Java 11+ minimum (p.7)
- Default connection: localhost:11222 with admin/password credentials (p.7)
- If Docker is available and no server running, tutorials auto-start with Testcontainers (p.7)

**Remote Cache Tutorials (most relevant for PoC):**
- Remote cache use example (basic distributed cache)
- Cache alias example (cache aliasing)
- Per cache configuration (dynamic cache config via client)
- Near caches (client-side near caching for read performance)
- Cache Admin API (create caches and templates dynamically)
- Encoding (cache encoding modes)
- Custom marshalling (Protostream schemas)
- Client listeners (cache event detection)
- Query / Continuous query
- Transactions (remote transactions)
- Secured caches (authorization-enabled caches)
- TLS authorization
- Multimap
- Task execution (server tasks from Hot Rod client)
- JUnit 5 and Testcontainers
- Persistence
- **Redis Client** (read/write using RESP protocol) (p.8)
- Reactive API (Mutiny-based)

**Embedded Cache Tutorials:**
- Distributed/Replicated/Invalidated caches
- Cache alias, Transactions, Security
- Streams, JCache integration, Functional Maps, Map API, Multimap
- Metrics, Queries
- Clustered Listeners, Counters, Locks, Clustered execution
- Kubernetes and OpenShift tutorial with JKube maven plugin (p.11-12)

**Spring and Spring Boot Tutorials:**
- Spring Cache remote mode (with Data Grid Server) (p.13)
- Spring Cache + Reactor + Infinispan Server
- Spring Session remote mode
- Spring Cache embedded mode
- Spring Session embedded mode
- Spring cache embedded without Spring Boot
- Prometheus metrics collection via actuator (p.13)
- Actuator cache metrics at `http://localhost:8080/actuator/metrics` (p.13)

### PoC Relevance
- **Redis Client tutorial** directly maps to PoC Use Case 3 (RESP protocol validation)
- **Secured caches** and **TLS authorization** tutorials map to PoC Use Case 4
- **Spring Cache/Session** tutorials map to PoC Use Case 3 (Spring integration)
- **Kubernetes/OpenShift tutorial** maps to PoC Use Case 1 (deploy on OCP)
- These are runnable code examples — starting point for PoC applications

---

## 5. Memcached Protocol Endpoint

### Key Points for PoC

- Data Grid implements Memcached text and binary protocols (p.7)
- Memcached endpoint enabled by default on single-port endpoint (p.7)
- Client encoding configurable via `client-encoding` attribute in `memcached-connector` (p.7)
- Protocol detection: manual (TEXT, BINARY) or AUTO — automatic detection on single-port requires authentication for text protocol (p.7)
- Security: text protocol uses initial `set` command with username+password; binary protocol uses SASL challenge/response (p.7)
- flush_all only clears the local node, does not propagate to cluster (p.7-8)
- Unsupported features: individual statistics (pointer_size, rusage_*, bytes, connections, threads, etc.), settings with arguments, delete hold time, verbosity command, meta protocol (1.6+) (p.8-9)

### PoC Relevance
- **Low relevance for the organization PoC** — RESP (Redis protocol) is the customer's target, not Memcached
- Confirms Data Grid supports multiple protocol endpoints simultaneously (Memcached + Hot Rod + REST + RESP on same server)
- The single-port multiplexing model is relevant: all protocols on port 11222

---

## Cross-Cutting Insights for PoC Architecture

1. **Multi-protocol single-port**: Data Grid 8.6 runs Hot Rod, REST, RESP, and Memcached on a single port (11222). This simplifies OpenShift networking — one Service, one Route.

2. **RBAC is consistent across all access methods**: CLI, REST API, Hot Rod — same users, roles, and permissions model. This is the foundation for multi-tenant "Cache as a Service".

3. **Cache lifecycle is fully automatable**: Create, configure, modify, delete caches via REST API or CLI. Enables GitOps workflows and self-service provisioning.

4. **Monitoring surface**: REST health endpoints for OCP probes, Prometheus metrics via actuator (Spring Boot apps), Data Grid Console for visual management, CLI `stats` for diagnostics.

5. **Code tutorials are runnable starting points**: The official tutorials cover every PoC use case — Redis Client, Spring Cache, Spring Session, Secured Caches, Kubernetes deployment. Build PoC apps from these.

6. **Querying as differentiator**: Data Grid's Ickle query language, full-text search, and continuous queries are capabilities Redis does not offer natively. Useful to demonstrate added value beyond simple caching.
