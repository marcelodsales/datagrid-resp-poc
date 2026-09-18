# Insights from Bankia Implementation Reference

Source documents (Red Hat Consulting, 2018-2019, RHDG 7.x):
- "Datagrid 7 for Bankia: Project Corporate Grid" (14 pages) — project scoping
- "Red Hat Data Grid en Bankia" (60 pages) — architecture presentation
- "Red Hat Data Grid 7 in Bankia" (35 pages) — operations guide
- "Bankia Implantación RHDG" (37 pages) — services proposal

---

## 1. Architecture Decisions

### Deployment Mode
- **Client-Server mode chosen** (not Library/Embedded) for corporate grid
- Rationale: independent cache lifecycle, protocol flexibility (Hot Rod, REST), no app-level dependencies, scalable independently from application servers
- Library mode was explicitly **not recommended** for Bankia's use case

### Cluster Topology
- 4 environments: EPD (Dev), EPI (Pre-production), EPI2 (Pre-production 2), PR (Production)
- Production: 4 nodes, 2 cores/node, 8GB RAM/node (16GB recommended), 2 replicas per entry
- Non-production: 4 nodes, 1 core/node, 8GB RAM/node, 2 replicas
- Total memory ~32GB per cluster, ~12GB usable for unique objects after replication overhead
- Cluster survives loss of 1 node maintaining all data on remaining 3 nodes (8x3=24GB)

### Isolation Model
- Options evaluated: one cluster per environment, single cluster with separate Cache Containers, server-groups
- Final approach: separate clusters per environment with identical topology for consistency

---

## 2. Cache Design

### Cache Topologies Used
- 48 distributed caches, 5 replicated caches (from scoping doc)
- Distributed: scalable, subset of nodes hold each entry
- Replicated: all nodes hold all entries, used for small reference data

### Cache Templates (Critical Pattern)
- Bankia defined cache templates using Jinja2/Ansible (`.xml.j2` files)
- Template naming convention: `{topology}-{persistence}-{eviction}-{expiration}.xml.j2`
- Examples:
  - `distributed-disk-persistent-eviction-expiration.xml.j2`
  - `distributed-no-persistent-eviction-expiration.xml.j2`
  - `replicated-oracle-persistent-expiration.xml.j2`
- Template features always include: RBAC, statistics enabled, memory eviction (BINARY, by MEMORY size), default expiration (lifespan)
- Immutable variables (same across envs): cache_name, cache_template, cache_type, cache_users
- Variable per environment: owners (replicas), cache_max_memory, cache_default_expiration, backup (yes/no)

### Cache Configuration Example (XML)
```xml
<distributed-cache name="{{ cache_name }}" statistics="true" owners="{{ owners }}">
  <security>
    <authorization roles="system monitor {{ cache_users }}"/>
  </security>
  <memory>
    <binary eviction="MEMORY" size="{{ cache_max_memory }}"/>
  </memory>
  <leveldb-store shared="false" fetch-state="true" passivation="false"/>
  <expiration lifespan="{{ cache_default_expiration }}"/>
</distributed-cache>
```

### Cache Lifecycle Flow
1. DEVs + ARCH request cache in EPD: define name, RBAC, type (distributed/replicated), memory limit, expiration
2. OPS create cache in EPD: add YAML to Git, run Ansible playbook from template
3. DEVs request promotion to EPI: same config (some variables immutable)
4. DEVs request promotion to EPI2/PR: same flow

---

## 3. Security Model

### Authentication
- Service accounts for application connections via Hot Rod
- User accounts for developers/admins (console, CLI, management)
- Kerberos evaluated for interactive users
- Recommendation: start with simple auth model, expand to Kerberos later

### Authorization (RBAC)
- Users bound to ROLEs (role mapping)
- Each ROLE has specific permissions per cache
- Cache authorization element specifies which roles can access it
- Example: `Microsvcs-Write` role in the `Microsvcs` Cache Container
- Grouping criterion: caches grouped by application/project

---

## 4. Automation (Ansible-Based)

### Infrastructure as Code
- Full Ansible automation: `ansible-datagrid/` repository
- Structure: `inventories/` (per env/cluster), `cache-templates/`, `caches-epd/`, `playbooks/`, `roles/`
- Per-cache YAML config files in `caches-{env}/` directories (one YAML per cache)

### Operations Automated
- Cluster install/setup/start/stop/restart
- Node add/remove
- User management (cache users, management users)
- Cache CRUD (create from template, create from XML, delete, modify eviction/expiration/authorization)
- Datasource management
- Health checks
- Cross-datacenter operations (`site push main`)

---

## 5. Memory and Eviction Guidelines

### Memory Sizing Rules
- Data in heap must NOT exceed **50% of total heap** for basic get/put operations
- If using analytics (queries, streams, distributed executors): limit to **33% of heap**
- Bankia used BINARY storage with eviction by MEMORY occupation
- This allows sysadmins to calculate total cluster memory by summing max memory of all caches per JVM

### Eviction vs Expiration
- **Eviction**: local, removes from memory only, triggered when limit is reached, uses TinyLFU policy (BINARY storage)
- **Expiration**: cluster-wide, removes from memory AND cache store, time-based (lifespan and max-idle)
- Good practice: always specify expiration time when putting entries (override cache default at entry level)

### Defaults in Bankia Templates
- Max memory: 10MB default (per cache)
- Default expiration: 1 hour (3600000ms), configurable to -1 (never expire)

---

## 6. Persistence Options

- **SingleFile store**: local files, good read/write performance, keeps keys in memory (limits scalability for large key sets)
- **LevelDB store**: local files, high throughput, does NOT keep keys in memory (better for large datasets, slightly slower)
- **JDBC store**: shared database, all cluster nodes persist to one DB, lacks local-cache performance, stores as binary blobs (not human-readable)
- Bankia used LevelDB for disk persistence and Oracle/PostgreSQL for JDBC persistence

---

## 7. Cross-Site Replication (DR)

- Active/passive or active/active between datacenters
- Synchronous or asynchronous replication between sites
- Uses RELAY2 (JGroups) for inter-cluster communication
- Clients automatically fail over to backup site and return to primary when it recovers
- Topology changes in one site do NOT affect other sites

---

## 8. Monitoring

- JMX metrics for cache status, rebalancing, topology
- HTTP management interface (port 55706 in Bankia)
- `dataMemoryUsed` metric per cache (requires eviction to be configured)
- RHDG server does NOT know serialized data format — only client knows actual data size
- Cache metrics show rebalancing state and cluster membership changes
- Server logs track state transfer and cluster membership evolution

---

## 9. Middleware Integration Matrix (Bankia Context)

| Product | Component | Use Case |
|---------|-----------|----------|
| RHOAR | Spring Framework | SpringCache with Hot Rod |
| RHOAR | Node.js client | Hot Rod for Node.js |
| EAP | Session Externalization | HTTP session to DG |
| EAP | Library Module | Embedded mode in EAP |
| EAP | Hot Rod Client Module | Remote client for EAP |
| JWS | Session Manager | Session externalization |
| RHSSO | Cross-Site Replication | DR for SSO sessions |
| Fuse | Camel Component | Producer/Consumer caching |

---

## 10. Key Lessons for the organization PoC

1. **Client-Server is the right mode for "Cache as a Service"** — Bankia validated this architecture in production for a large bank
2. **Cache templates are essential for governance** — standardize creation, prevent configuration drift between environments
3. **RBAC per cache is the multi-tenancy mechanism** — each application gets its own role with permissions on specific caches
4. **Automation is critical for Day-2 operations** — Bankia used Ansible; the organization should use OpenShift Operator + GitOps (modern equivalent)
5. **Memory sizing requires discipline** — the 50% heap rule and per-cache memory limits prevent OOM in production
6. **Design apps to work without cache** — explicit recommendation from Bankia consultants (slide 58)
7. **Async APIs and configuration reduce latency** — `putAsync()` and Spring `@Cacheable` are already async-friendly
8. **Features Bankia chose NOT to use** (struck through in slides): JTA/XA transactions, auto node discovery (used static config), off-heap memory, certificates-based auth, invalidation mode, max-idle expiration, Security Manager, write-behind persistence
9. **Cache name convention matters** — Bankia used descriptive names like `GID-REFRESH-TOKEN`, `API-PSD-OPERATION-INFO`
10. **The v7-to-v8 differences are significant** — the organization will use 8.6 on OpenShift with Operator, which replaces most of Bankia's Ansible automation with declarative CRDs
