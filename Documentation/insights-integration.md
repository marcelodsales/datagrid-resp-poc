# Data Grid 8.6 Integration Insights

Source: Official Red Hat Data Grid 8.6 Documentation PDFs

## 1. Spring Cache Integration

**Doc:** Using_Data_Grid_with_Spring (18 pages)

### Spring Cache Provider
- Data Grid supports Spring 6 and Spring 7
- Two modes: **embedded** (in-process) and **remote** (client-server via Hot Rod)
- Maven artifacts:
  - Remote: `infinispan-spring6-remote` or `infinispan-spring7-remote`
  - Embedded: `infinispan-spring6-embedded` or `infinispan-spring7-embedded`
- For Spring Boot: use `infinispan-spring-boot3-starter-embedded` or `infinispan-spring-boot4-starter-embedded`
- Remote caches require `hotrod-client.properties` configuration
- Annotations: `@EnableCaching`, `@Cacheable(value, key)`, `@CacheEvict(value, key, allEntries)`
- Timeout configuration: `infinispan.spring.operation.read.timeout` and `infinispan.spring.operation.write.timeout` (default: 0 = unlimited)

### Spring Session Externalization
- Store HTTP session data in Data Grid caches independently of the container
- Dependencies: `infinispan-spring6-remote` + `spring-session-core` + `spring-web`
- Annotations: `@EnableInfinispanRemoteHttpSession` or `@EnableInfinispanEmbeddedHttpSession`
- Parameters: `maxInactiveIntervalInSeconds` (default 1800), `cacheName` (default "sessions")
- Factory beans: `SpringRemoteCacheManagerFactoryBean` or `SpringEmbeddedCacheManagerFactoryBean`

### PoC Relevance
- Spring Cache: direct fit for the organization's Spring-based applications
- Spring Session: validates session externalization use case
- Remote mode is the correct choice for cache-as-a-service architecture

---

## 2. Spring Boot Starter

**Doc:** Data_Grid_Spring_Boot_Starter (22 pages)

### Embedded Caches (Chapter 1)
- Add `infinispan-spring-boot3-starter-embedded` or `infinispan-spring-boot4-starter-embedded`
- Inject `EmbeddedCacheManager` via `@Autowired`
- Reactive mode: set `infinispan.embedded.reactive=true`
- Configuration beans: `InfinispanGlobalConfigurer`, `InfinispanCacheConfigurer`, `Configuration`
- In Spring Boot 4, `InfinispanConfigurationCustomizer` was removed — use `InfinispanCacheConfigurer` and define "default" cache explicitly

### Remote Caches (Chapter 2)
- Hot Rod is a custom TCP binary wire protocol
- Inject `RemoteCacheManager` via `@Autowired`
- Reactive mode: set `infinispan.remote.reactive=true`
- Properties in `hotrod-client.properties` (priority) or `application.properties`
  - `hotrod-client.properties`: prefix `infinispan.client.hotrod.*`
  - `application.properties`: prefix `infinispan.remote.*`
- Marshalling: ProtoStream (recommended) or Java Serialization (allow list required)
- Cache creation on startup from client: `infinispan.client.hotrod.cache.mycache.template_name=mytemplate1`
- Near cache support: `infinispan.remote.cache.[name].near-cache.mode=INVALIDATED`
- Configuration beans: `InfinispanRemoteConfigurer`, `Configuration`, `InfinispanRemoteCacheCustomizer`

### Statistics via Actuator
- Add `spring-boot-starter-actuator` + `spring-boot-starter-web`
- Enable statistics per cache: `.jmxStatistics().enable()` or `<local-cache statistics="true"/>`
- Dynamic caches need `CacheMetricsRegistrar` binding

### Spring Session (Chapter 3)
- Built on `SpringRemoteCacheManager` / `SpringEmbeddedCacheManager`
- Must create a Data Grid cache first — no default cache provided
- Annotations: `@EnableCaching`, `@EnableInfinispanRemoteHttpSession`, `@EnableInfinispanEmbeddedHttpSession`

### Application Properties (Chapter 4)
- Full list of `infinispan.remote.*` and `infinispan.embedded.*` properties available

### PoC Relevance
- Spring Boot Starter is the fastest path for PoC apps
- Remote mode + Hot Rod is the target architecture
- Actuator integration useful for monitoring PoC item 4

---

## 3. RESP Protocol Endpoint (Redis Compatibility)

**Doc:** Using_the_RESP_protocol_endpoint (24 pages)

### Architecture
- RESP endpoint enabled by default on single-port endpoint
- Redis clients auto-detected and routed to internal connector
- Works with standalone and clustered deployments
- Clustered: automatic failover, hash-slot distribution, no -MOVED errors needed

### Cache Configuration
- Default cache: `respCache` with `application/octet-stream` encoding
- Must use `RESPHashFunctionPartitioner` (CRC16 hashing)
- Key encoding must be `application/octet-stream`
- Can provide custom cache config as long as constraints are met

### Explicit Endpoint Configuration
- Configure via `resp-connector` in server endpoints (XML/JSON/YAML)
- Can point to a custom cache name

### Cache-to-Database Mapping
- Use cache `aliases` attribute to map caches to Redis logical databases
- Default `respCache` maps to database `0`
- Supports multiple logical databases even in clustered mode (unlike Redis Cluster)

### Differences from Redis
- **Protocol**: RESP version 3 only (not v2)
- **Isolation**: Relaxed isolation — concurrent requests may see partial MSET results. Use MULTI...EXEC for atomicity (requires transactional cache config)
- **Transactions**: ACID transactions with rollback (Redis has no rollback). Recommended: PESSIMISTIC locking, NONE transaction mode
- **Lua Scripts**: Serialize responses using RESP3 (equivalent to `redis.setresp(3)`)

### Supported Redis Commands
- Full list includes: strings (GET, SET, MGET, MSET, INCR, etc.), hashes (HGET, HSET, HMSET, etc.), lists (LPUSH, RPUSH, LRANGE, etc.), sets, sorted sets, JSON operations, HyperLogLog (PFADD), pub/sub (PUBLISH, SUBSCRIBE), transactions (MULTI, EXEC, DISCARD), scripting (EVAL, EVALSHA), cluster commands
- JSON module supported (JSON.GET, JSON.SET, JSON.MERGE, etc.)

### PoC Relevance
- Critical for Redis replacement/migration use case
- Validates that existing Redis clients can connect to Data Grid without code changes
- RESP3-only is a constraint — verify client compatibility
- Isolation differences must be communicated to the organization (relaxed vs serializable)
- Logical database mapping enables multi-tenant RESP usage

---

## 4. Embedding Data Grid in Java Applications

**Doc:** Embedding_Data_Grid_in_Java_Applications (90 pages, read first 20)

### Maven Setup
- Red Hat GA Maven repo: `https://maven.repository.redhat.com/ga/`
- BOM: `infinispan-bom` version `16.0.6.Final-redhat-00001`
- Core dependency: `infinispan-core`

### Creating Embedded Caches
- API: `GlobalConfigurationBuilder` (CacheManager) + `ConfigurationBuilder` (cache config)
- Must call `cacheManager.start()` before creating caches
- One CacheManager instance per JVM recommended
- Cache creation methods:
  - `getCache(name)` — local only, must be called on each node
  - `createCache(name, template)` — dynamic, cluster-wide, but ephemeral
  - `getOrCreateCache(name, template)` — recommended, creates or returns existing
- `PERMANENT` flag: caches survive restarts (requires global state + config storage provider)

### Cache API
- Extends JDK `ConcurrentMap`
- Mortal vs Immortal entries: `put(key, value, lifespan, timeunit)` for TTL
- `putForExternalRead()` — optimistic, non-blocking, for read-heavy scenarios
- `AdvancedCache` — flags, internal components access
- Async API: `putAsync()`, `removeAsync()` — returns `CompletableFuture`

### Security (Chapter 3)
- User roles and permissions: LIFECYCLE, READ, WRITE, EXEC, LISTEN, BULK_READ, BULK_WRITE, CREATE, MONITOR, ALL, NONE, ADMIN
- Predefined roles: observer, application, deployer, admin, monitor
- Role mappers: IdentityRoleMapper, CommonNameRoleMapper, ClusterRoleMapper (default for servers)
- ACL cache: caches authorization decisions, configurable

### JBoss EAP Integration (Chapter 9)
- Configure applications to use Data Grid modules in JBoss EAP
- Configure Data Grid caches in Red Hat JBoss EAP
- Use Data Grid caches in JBoss EAP applications

### CDI Extension (Chapter 10)
- Inject embedded and remote caches via CDI
- Receive cache and cache manager events

### PoC Relevance
- JBoss EAP chapter (p.75) directly relevant for the organization's EAP integration use case
- Embedded mode less relevant for cache-as-a-service but useful for understanding capabilities
- Security model (roles/permissions) critical for multi-tenancy design

---

## 5. Hot Rod Java Client

**Doc:** Hot_Rod_Java_Client_Guide (53 pages, read first 20)

### Basic Setup
- Maven: `infinispan-client-hotrod` version `16.0.6.Final-redhat-00001`
- Simplest connection: `new RemoteCacheManager()` connects to localhost:11222
- Authentication: `ConfigurationBuilder` with `.security().authentication().username().password()`
- Multi-cluster: `.addCluster("cluster1").addClusterNodes(...)` with `switchToCluster()` for failover

### Configuration
- `hotrod-client.properties` file on classpath (auto-discovered)
- Properties: `server_list`, `auth_username`, `auth_password`, `auth_realm`
- Programmatic: `ConfigurationBuilder` with `withProperties()`

### SSL/TLS Encryption
- Trust store: `.trustStoreFileName()` + `.trustStorePassword()`
- Client certificate: `.keyStoreFileName()` + `.keyStorePassword()`
- Hostname validation via SAN (dNSName or iPAddress)
- Shared system certificates as alternative

### Authentication Mechanisms
- SCRAM-SHA-512, DIGEST-MD5, PLAIN, OAUTHBEARER, EXTERNAL, GSSAPI
- `TokenCallbackHandler` for OAuth2 token refresh
- GSSAPI requires Kerberos `LoginContext`

### Advanced Features (Chapter 2)
- Client intelligence: topology-aware, consistent-hash-aware
- Request balancing
- Client failover
- Near caches: client-side cache with INVALIDATED mode
- Forcing return values
- Creating remote caches from client

### Observability (Chapter 3)
- Client statistics
- Tracing propagation
- Client metrics

### Client API (Chapter 4)
- `RemoteCache` API
- Remote iterator
- MetadataValue API
- Streaming API
- Counter API
- Event listeners (create, modify, remove, expire events)
- Transactions: NON_XA, NON_DURABLE_XA, FULL_XA modes

### PoC Relevance
- Hot Rod is the native protocol — best performance for Java clients
- Multi-cluster support relevant for HA/DR scenarios
- Near caches can improve latency for read-heavy workloads
- Authentication mechanisms must align with the organization's identity infrastructure
- Event listeners enable reactive patterns (event correlation use case)

---

## Cross-Cutting PoC Observations

| PoC Item | Relevant Integration Docs |
|----------|--------------------------|
| 1. Deploy/Operations | Operator Guide (separate doc) |
| 2. Multi-tenancy | Security roles/permissions (Embedding ch.3), cache aliases (RESP) |
| 3. Integration Models | Spring Cache, Spring Boot Starter, RESP, Embedding (JBoss EAP), Hot Rod |
| 4. Security/HA/Monitoring | Hot Rod SSL/TLS, auth mechanisms, Actuator stats, near caches, multi-cluster |

### Key Decisions for PoC Design
1. **Remote mode** (client-server) is the correct architecture for cache-as-a-service
2. **Hot Rod** for native Java clients, **RESP** for Redis-compatible clients — both validated
3. **Spring Boot Starter** (remote) is the fastest path for demo applications
4. **RESP3-only** is a constraint — must verify the organization's Redis client library versions
5. **ProtoStream** marshalling preferred over Java Serialization for interoperability
6. **Relaxed isolation** in RESP mode is a behavioral difference from Redis that must be documented
