# Red Hat Data Grid 8.6 — Deploy & Operations Insights

Consolidated reference from: Operator Guide, Server Guide, Helm Chart Guide, Performance and Sizing Guide.

---

## 1. Deployment Options on OpenShift

### 1.1 Operator (Recommended)

The Infinispan Operator manages Data Grid clusters via CRDs on OpenShift.

**CRDs available:**
| CRD | apiVersion | Purpose |
|-----|-----------|---------|
| `Infinispan` | `infinispan.org/v1` | Cluster lifecycle (create, scale, upgrade) |
| `Cache` | `infinispan.org/v2alpha1` | Declarative cache creation |
| `Batch` | `infinispan.org/v2alpha1` | Bulk CLI operations at runtime |
| `Backup` | `infinispan.org/v2alpha1` | Backup cluster state to PVC archive |
| `Restore` | `infinispan.org/v2alpha1` | Restore cluster state from PVC archive |

> **Ref:** Operator Guide, Ch.1 — Operator concepts and CRDs

**Operator installation:**
- Via OperatorHub in OpenShift Web Console (recommended)
- Subscription in `openshift-operators` namespace for cluster-wide availability
- CLI plugin `oc infinispan` available for convenience operations

> **Ref:** Operator Guide, Ch.3 — Installing Data Grid Operator

### 1.2 Helm Chart (Alternative)

Helm chart deploys Data Grid without the Operator — uses a StatefulSet directly.

**Install command:**
```
helm install infinispan openshift-helm-charts/redhat-data-grid --values infinispan-values.yaml
```

**Key differences from Operator:**
- No CRDs (Cache, Batch, Backup/Restore) — must use REST/CLI for cache management
- Default credentials stored in `<release>-generated-secret` with users `developer` (admin) and `monitor`
- Requires `monitoring-edit` role for deploying with ServiceMonitor enabled
- `deploy.usePrefixedLabels: true` needed if admission controllers (e.g., Kyverno) conflict with label keys `app`/`clusterName`

> **Ref:** Helm Chart Guide, Ch.1 — Deploying as Helm Chart Releases

**Default Helm resource limits:**
| Resource | Default |
|----------|---------|
| CPU limit | 500m |
| Memory limit | 512Mi |
| CPU request | 500m |
| Memory request | 512Mi |
| Storage size | 1Gi |
| Replicas | 1 |

> **Ref:** Helm Chart Guide, Section 1.5 — Deployment Configuration Values

---

## 2. Network Architecture

### 2.1 Ports

| Port | Protocol | Purpose |
|------|----------|---------|
| 11222 | TCP | Hot Rod + REST (single-port multiplexed) |
| 11223 | TCP | Admin/metrics endpoint (Operator internal) |
| 8888 | TCP | JGroups DNS_PING cluster discovery |
| 7900 | TCP | Cross-site RELAY2 replication |
| 9999 | TCP | JMX (disabled by default) |

> **Ref:** Server Guide, Ch.2 — Network interfaces and ports; Operator Guide, Ch.12

### 2.2 Expose Types

| Method | Port | Client Intelligence | TLS |
|--------|------|-------------------|-----|
| Route (default) | 443 | BASIC only | Passthrough (auto via OpenShift service certs) |
| LoadBalancer | 11222 | BASIC only | Optional |
| NodePort | 30000-32767 | BASIC only | Optional |
| Internal (ClusterIP) | 11222 | HASH_DISTRIBUTION_AWARE | Auto (service certs) |

**Critical insight:** External access (Route/LB/NodePort) forces `BASIC` client intelligence — Hot Rod clients lose topology-awareness and hash-distribution optimization. Internal access preserves `HASH_DISTRIBUTION_AWARE` for best performance.

> **Ref:** Operator Guide, Ch.12 — Configuring network access; Performance Guide, Section 2.2

### 2.3 IP Address Filtering

Endpoints support `ip-filter` rules with CIDR-based accept/reject for access control.

```xml
<ip-filter>
  <accept from="192.168.0.0/16"/>
  <reject from="192.168.1.0/24"/>
</ip-filter>
```

> **Ref:** Server Guide, Ch.3 — Endpoint IP filtering

---

## 3. Protocol Comparison

| Feature | Hot Rod | REST | RESP | Memcached |
|---------|---------|------|------|-----------|
| Topology awareness | Yes | No | No | No |
| Hash-distribution routing | Yes | No | No | No |
| Near-caching | Yes | No | No | No |
| Conditional operations | Yes | Yes | No | No |
| Transactions | Yes | No | No | No |
| Queries | Yes | Yes | No | No |
| Server tasks | Yes | Yes | No | No |
| Listeners/events | Yes | Yes (SSE) | No | No |
| Cross-site failover | Yes | No | No | No |
| Auth mechanisms | SASL | HTTP Basic/Digest/Bearer/Cert | PLAIN only | No |

**RESP limitations:** Only supports Property and LDAP realms for authentication. No topology/hash awareness, no conditional operations, no transactions, no queries, no cross-site failover.

> **Ref:** Server Guide, Ch.3 — Endpoint connectors and protocol comparison

---

## 4. Cluster Configuration

### 4.1 Infinispan CR Spec

```yaml
apiVersion: infinispan.org/v1
kind: Infinispan
metadata:
  name: example
spec:
  replicas: 3
  service:
    type: DataGrid          # DataGrid (full) or Cache (minimal)
  container:
    cpu: "2000m:500m"       # limit:request
    memory: "2Gi:1Gi"       # limit:request
    extraJvmOpts: "-XX:+UseG1GC"
  expose:
    type: Route
  security:
    endpointSecretName: connect-secret
    endpointEncryption:
      type: Secret           # or Service (auto OpenShift certs)
      certSecretName: tls-secret
```

> **Ref:** Operator Guide, Ch.4–5 — Creating and configuring clusters

### 4.2 Custom Server Configuration

Override default server config via a ConfigMap referenced by `spec.configMapName`. The ConfigMap must contain an `infinispan-config.yaml` (or XML/JSON) file.

**The Operator merges** user config with required settings (clustering, security, endpoints). User config takes precedence for endpoint/security sections.

> **Ref:** Operator Guide, Ch.6 — Configuring Data Grid clusters with custom server configuration

### 4.3 Cluster Transport

Default JGroups stack is `kubernetes` (uses DNS_PING for pod discovery). The DNS query is:
```
<cluster-name>-ping.<namespace>.svc.cluster.local
```

For Helm deployments, set `deploy.infinispan.cacheContainer.transport.stack: kubernetes`.

> **Ref:** Server Guide, Ch.12 — Cluster transport; Helm Guide, Section 2.2

---

## 5. Sizing and Performance

### 5.1 Memory Sizing Formulas

**Data set size:**
```
Data set size = Number of entries * (Avg key size + Avg value size + Memory overhead)
```

Memory overhead: ~200 bytes/entry (JVM heap) or ~60 bytes/entry (off-heap).

**Distributed cache sizing:**
```
Distributed data set = Data set size * (Number of owners + 1)
```

The `+1` accounts for rebalancing during topology changes.

**Node count planning:**
```
Planned nodes = Minimum number of nodes + Number of owners - 1
```

**Example:** 1M entries x 10KB each, 3 owners, 4GB per node:
- Data set = 10GB
- Distributed = 10GB * (3+1) = 40GB
- Min nodes = 40GB / 4GB = 10
- Planned nodes = 10 + 3 - 1 = 12

> **Ref:** Performance Guide, Section 2.1 — How to calculate the size of your data set

### 5.2 JVM Heap Allocation

| Workload type | Heap for data storage |
|---------------|----------------------|
| Cache only (read/write/delete) | 50% of JVM heap |
| Cache + data processing (queries, listeners) | 33% of JVM heap |

**Off-heap storage benefits:**
- Less memory overhead per entry (~60 vs ~200 bytes)
- Avoids GC pressure on data entries
- JDK 23+ uses JDK-supported native memory APIs

**Off-heap trade-off:** `cache.get()` still reads objects into heap temporarily; JVM heap dumps won't show off-heap entries.

> **Ref:** Performance Guide, Sections 2.1.2 and 2.5

### 5.3 Segments

- Default: 256 segments per cluster
- Optimal for up to 20 nodes
- For >20 nodes: `Number of segments = 20 * Number of nodes`
- Changing segments requires full cluster restart
- Use `StoreMigrator` if persistent storage is configured

> **Ref:** Performance Guide, Section 2.2 — Number of segments

### 5.4 Cache Mode Decision

| Criteria | Distributed | Replicated |
|----------|------------|------------|
| Best for | Write-heavy, scaling capacity | Read-heavy, data guarantees |
| Data per node | Subset (owners) | Full copy |
| Scaling effect | Capacity increases linearly | Each node needs full data set |
| Write network cost | writes = owners | writes = all nodes |
| Read cost | May require network hop | Always local |

**Recommendation from docs:** Distributed caches are the best choice for most Data Grid Server deployments.

> **Ref:** Performance Guide, Section 2.2 — Clustered cache modes

### 5.5 Synchronous vs Asynchronous Replication

| Mode | Consistency | Latency | Throughput |
|------|------------|---------|-----------|
| Synchronous | Strong | Higher (waits for acks) | Can be higher total due to parallel processing |
| Asynchronous | Eventual | Lower | Limited by JGroups per-sender ordering |

**Flow control:** JGroups `max_credits` attribute (4MB default) — blocks originator when unconfirmed replications exceed threshold. Applies to both modes.

**Active/Active cross-site:** Never use synchronous backup (deadlocks). Use `strategy=async` with merge policies (PREFERRED_ALWAYS, PREFERRED_NON_NULL, REMOVE_ALL, or custom XSiteEntryMergePolicy).

> **Ref:** Performance Guide, Sections 2.2 and 2.12

### 5.6 Benchmarking with Hyperfoil

Red Hat recommends Hyperfoil for benchmarking Data Grid on OpenShift (Community Operator, not certified).

**Metrics collected:** throughput (req/s), mean latency, p50, p90, p99, p99.9, p99.99, timeouts, errors, blocked requests.

**Benchmark test structure:** rampup phase (increasing rate) + constant rate phase, for both PUT and GET operations.

> **Ref:** Performance Guide, Ch.3 — Benchmarking Data Grid on OpenShift

---

## 6. Security

### 6.1 Authentication

**Default Operator behavior:** Auto-generates credentials in a Secret named `<cluster>-generated-secret` with `operator` (admin) user. The IDENTITIES_BATCH env var processes CLI commands to set up users.

**RBAC Roles:**
| Role | Permissions | Use for |
|------|------------|---------|
| admin | ALL | Cluster operators |
| deployer | ALL_READ, ALL_WRITE, LISTEN, EXEC, MONITOR, CREATE | CI/CD pipelines |
| application | ALL_READ, ALL_WRITE, LISTEN, EXEC, MONITOR | Application services |
| observer | ALL_READ, MONITOR | Read-only dashboards |
| monitor | MONITOR | Prometheus scraping |

> **Ref:** Operator Guide, Ch.9 — Configuring RBAC; Helm Guide, Section 3.2.1

### 6.2 Authentication Mechanisms — Performance Impact

| SASL / HTTP | Performance |
|-------------|------------|
| PLAIN / BASIC | Fastest but least secure — only with TLS |
| DIGEST / DIGEST | Uses MD5 hashing, no plaintext, but vulnerable to MITM without TLS |
| SCRAM / DIGEST | Extra hashing rounds, more secure, slower |
| OAUTHBEARER / BEARER_TOKEN | Offloads to identity provider, lower direct cost |
| GSSAPI / SPNEGO | Kerberos KDC, separate system handles auth |
| EXTERNAL / CLIENT_CERT | Mutual TLS, certificate comparison overhead |

**Hot Rod validates credentials once per connection; HTTP validates per request.**

> **Ref:** Performance Guide, Section 2.7 — Cluster Security; Server Guide, Ch.4

### 6.3 TLS/Encryption

**Operator — automatic encryption:**
- `type: Service` — uses OpenShift service serving certificates (auto-renewed, 1-year validity)
- `type: Secret` — custom PKCS12/PEM keystore in a Secret

**Helm — manual encryption:**
- Separate secrets for endpoint (`deploy.ssl.endpointSecretName`) and transport (`deploy.ssl.transportSecretName`)
- Keystore paths: `/etc/encrypt/endpoint/keystore.p12` and `/etc/encrypt/transport/cert.p12`

**Cluster transport encryption:** Configured via security realm with keystore + truststore on transport stack.

**Performance note:** TLS handshake cost is one-time per connection (Hot Rod). Java 17+ TLS performance is on par with native implementations. Encryption latency is not a concern after connection establishment.

> **Ref:** Operator Guide, Ch.8; Helm Guide, Ch.4; Performance Guide, Section 2.7

---

## 7. High Availability

### 7.1 Anti-Affinity

Two strategies via `spec.scheduling.affinity`:
- `kubernetes.io/hostname` — spread pods across nodes (default for Helm: preferredDuringSchedulingIgnoredDuringExecution)
- `topology.kubernetes.io/zone` — spread pods across availability zones

> **Ref:** Operator Guide, Ch.14 — Anti-affinity strategies

### 7.2 Auto-Scaling (HPA)

The Operator exposes `.status.scale` sub-resource, enabling HPA.

**Constraints:**
- Remove HPA before upgrading Data Grid
- The Operator reconciles `spec.replicas` — if HPA sets replicas, Operator may override unless HPA controller wins

> **Ref:** Operator Guide, Ch.15 — Automatic scaling

### 7.3 Network Partitions and Split Brain

- Minority partition caches enter `DEGRADED` mode
- Majority partition remains available
- **Main cause of split brain:** GC pauses making nodes unresponsive
- **Mitigation:** Tune GC (G1GC default, consider ZGC or Shenandoah for low-pause)
- Merge policies: `PREFERRED_ALWAYS`, `PREFERRED_NON_NULL`, `REMOVE_ALL`
- Default: no merge policy (faster recovery but potential inconsistency)

**CAP theorem strategies:**
- Availability: `whenSplit: ALLOW_READ_WRITES`
- Consistency: `whenSplit: DENY_READ_WRITES`

> **Ref:** Performance Guide, Section 2.11 — Network partitions and degraded clusters

### 7.4 Backup and Disaster Recovery

**Via Operator CRs (not available with Helm):**
1. Create `Backup` CR → spawns zero-capacity pod → archives to PVC (.zip)
2. Create `Restore` CR → spawns zero-capacity pod → restores from PVC archive
3. Both are one-time events (not scheduled)

**Cross-site replication:**
- Managed mode: Operator creates Routes + ServiceAccounts with tokens between clusters
- Manual mode: Static URLs for site connectivity
- Transport port: 7900 (RELAY2)

> **Ref:** Operator Guide, Ch.13 (cross-site), Ch.19 (backup/restore)

---

## 8. Monitoring

### 8.1 Prometheus Integration

**Operator:** Auto-creates ServiceMonitor CR when `spec.service.type: DataGrid`. Metrics available on port 11223 via a dedicated metrics endpoint with its own security realm.

**Helm:** Set `deploy.monitoring.enabled: true` (requires `monitoring-edit` role). Default metrics config uses BASIC auth on port 11223.

> **Ref:** Operator Guide, Ch.13 — Configuring monitoring; Helm Guide, Section 1.5

### 8.2 JMX

- Disabled by default on the container image
- Enable via custom server config on port 9999
- Cryostat available for JDK Flight Recorder (JFR) profiling

> **Ref:** Server Guide, Ch.9 — JMX monitoring

### 8.3 Logging

- JSON logging to stdout: `deploy.logging.console.json: true` (Helm) or configure via `spec.logging` (Operator)
- Categories configurable per package (e.g., `org.infinispan: debug`)
- JSON format includes: time, service, namespace, pod, POD_NAME, POD_NAMESPACE

> **Ref:** Helm Guide, Section 1.5 — deploy.logging fields

---

## 9. Cache Management

### 9.1 Cache CR (Operator Only)

```yaml
apiVersion: infinispan.org/v2alpha1
kind: Cache
metadata:
  name: my-cache
spec:
  clusterName: example
  name: my-cache
  template: |
    distributedCache:
      mode: SYNC
      owners: "2"
      statistics: "true"
      encoding:
        mediaType: application/x-protostream
      expiration:
        lifespan: "5000"
        maxIdle: "1000"
      memory:
        maxCount: "1000000"
        whenFull: REMOVE
      partitionHandling:
        whenSplit: ALLOW_READ_WRITES
        mergePolicy: PREFERRED_NON_NULL
```

> **Ref:** Operator Guide, Ch.16 — Creating caches with Cache CR

### 9.2 Batch CR (Operator Only)

Execute CLI commands against a running cluster:
```yaml
apiVersion: infinispan.org/v2alpha1
kind: Batch
metadata:
  name: create-caches
spec:
  cluster: example
  config: |
    create cache --template=org.infinispan.DIST_SYNC my-cache
    put --cache=my-cache key1 value1
```

> **Ref:** Operator Guide, Ch.17 — Performing batch operations

### 9.3 Stale Data Strategies

- **Expiration:** Cluster-wide, reaper thread cleans expired entries (tunable interval)
- **Eviction:** Per-node, based on `maxCount` or `maxSize` — does not remove from other nodes
- **Passivation:** Writes to persistent storage only on eviction, reads activate back to memory

**Eviction + persistent storage recommended** to avoid data inconsistency from per-node eviction.

> **Ref:** Performance Guide, Sections 2.3 and 2.4

### 9.4 Persistent Storage

| Mode | Behavior | Latency | Consistency |
|------|----------|---------|-------------|
| Write-through | Synchronous write to cache + store | Higher | Strong |
| Write-behind | Async queue to store | Lower | Eventual |

**Pre-loading:** Populates caches on cluster start from persistent storage. Can cause OOM if persistent data exceeds available RAM.

**Operator:** Set `spec.service.container.storage` for PVC size. Default StorageClass used unless specified.

**Helm:** `deploy.container.storage.ephemeral: false` (default) for persistent; `true` for volatile.

> **Ref:** Performance Guide, Section 2.6; Operator Guide, Ch.5

---

## 10. Custom Code Deployment

### 10.1 Via PVC (Operator)

```yaml
spec:
  dependencies:
    volumeClaimName: my-libs-pvc
```

JAR files placed in the PVC are added to the server classpath.

### 10.2 Via Artifact Download (Operator)

```yaml
spec:
  dependencies:
    artifacts:
      - url: https://repo.example.com/my-filter-1.0.jar
      - maven: org.example:my-proto:1.0
```

Supports HTTP, FTP, Maven coordinates. Archives (.tar, .tar.gz, .zip) auto-extracted.

### 10.3 Via Helm

`deploy.container.libraries` — space-separated list of URLs or Maven coordinates downloaded before server startup.

> **Ref:** Operator Guide, Ch.18 — Deploying custom code; Helm Guide, Section 1.5

---

## 11. Upgrades

### 11.1 Operator Upgrades

- Operator upgrade is independent of Data Grid version
- Data Grid cluster upgrade: modify `spec.version` or change image
- Rolling upgrade: Operator performs graceful shutdown node by node
- **Remove HPA before upgrading**
- Operator handles StatefulSet strategy (zero-downtime with proper config)

> **Ref:** Operator Guide, Ch.5 — Upgrading Data Grid clusters

### 11.2 Helm Upgrades

```
helm upgrade infinispan openshift-helm-charts/redhat-data-grid --values infinispan-values.yaml
```

Pods are rebuilt according to StatefulSet update strategy.

> **Ref:** Helm Guide, Section 1.3 — Upgrading Data Grid Helm releases

---

## 12. Client Connection Configuration

### 12.1 Internal Clients (Same OpenShift Cluster)

**Hot Rod:**
- Service DNS: `<cluster-name>.<namespace>.svc.cluster.local:11222`
- Client intelligence: `HASH_DISTRIBUTION_AWARE` (default, best performance)
- TLS: Use the service CA cert from `<cluster-name>-cert-secret`

### 12.2 External Clients (Outside OpenShift)

**Hot Rod:**
- Must set client intelligence to `BASIC`
- Use Route hostname on port 443 (TLS passthrough)
- Provide SNI hostname matching the Route

**REST:**
- Standard HTTPS to Route hostname

> **Ref:** Operator Guide, Ch.20 — Establishing remote client connections; Helm Guide, Ch.6

---

## 13. Data Consistency and Transactions

- Data Grid supports ACID transactions (pessimistic and optimistic locking)
- **Optimistic locking:** Best for low key-contention — acquires locks at commit time
- **Pessimistic locking:** Best for high key-contention — acquires locks immediately
- **REPEATABLE_READ isolation** adds version metadata per entry (extra memory + network)
- Read isolation levels do not impact performance except with REPEATABLE_READ + optimistic locking

> **Ref:** Performance Guide, Section 2.10 — Data consistency

---

## 14. Operational Tips for the PoC

1. **Start with Operator**, not Helm — Cache/Batch/Backup CRs are essential for cache-as-a-service operations
2. **Use distributed caches with SYNC mode and 2 owners** as the baseline for the PoC
3. **Set anti-affinity by hostname** at minimum; by zone if multi-AZ
4. **Use `application/x-protostream` encoding** for interoperability across protocols (Hot Rod, REST, RESP)
5. **Size JVM heap at 50% for cache-only workloads**, 33% if using queries/listeners
6. **Keep 256 segments** unless cluster exceeds 20 nodes
7. **Use OpenShift service certs** (`type: Service`) for automatic TLS — simplest path
8. **Expose internally** (ClusterIP) for optimal Hot Rod performance; Route only for admin console or external REST access
9. **Configure eviction + persistent storage** together to prevent data inconsistency from per-node eviction
10. **GC tuning matters:** G1GC is default and suitable; monitor GC pauses to prevent split-brain scenarios
11. **Hyperfoil for benchmarking** — deploy as Community Operator, create benchmark tests with rampup + constant rate phases
12. **Helm `deploy.securityContext.fsGroup: 185`** needed to match Data Grid's default group ownership on mounted volumes
