# Demo Walkthrough

Complete the [Environment Setup](README.md) before starting. All infrastructure (Operator, clusters, caches, Redis baseline, test apps) must be deployed and healthy.

## Verify environment

```bash
oc get infinispan -n rhdg-poc-test
oc get caches -n rhdg-poc-test
oc get pods -n rhdg-poc-test
```

Expected: both clusters `WellFormed`, all caches `Ready`, all pods `Running`.

---

## Act 1: Redis Baseline

The `redis-client-app` starts connected to the Redis baseline instance. This establishes that the application works correctly with standard Redis before switching to Data Grid.

### 1.1 Verify connection to Redis

```bash
ROUTE=$(oc get route redis-client-app -n rhdg-poc-test -o jsonpath='{.spec.host}')
curl -sk "https://${ROUTE}/api/info" | jq .
```

Expected:

```json
{
  "backend_host": "redis",
  "backend_port": 6379,
  "status": "connected",
  "ping": "PONG"
}
```

### 1.2 Open the web UI

Open in a browser:

```
https://<redis-client-app route hostname>
```

The top bar shows connection status (green dot = Live). Six cards display: Cluster, Cache, Mode, Host (`redis`), Port (`6379`), Status (`Connected`).

### 1.3 Test 1 — PING

Already validated: the `/api/info` response includes `"ping": "PONG"` and the Status card shows `Connected`. This confirms the RESP connection is alive.

### 1.4 Test 2 — SET with TTL

In the **Strings** tab:

1. Type `ack:msg-001` in the **Key** field, `delivery-confirmed` in the **Value** field, and `30` in the **TTL (s)** field.
2. Click **SET**.
3. The result line shows: `SET ack:msg-001 -> OK (TTL: 30s)`.
4. The entry appears in the **Cache Entries** table with a TTL countdown.
5. The **Event Log** shows a green `SET ack:msg-001` event in real time (Pub/Sub via SSE).

### 1.5 Test 3 — GET

1. Type `ack:msg-001` in the **Key** field.
2. Click **GET**.
3. The result line shows: `GET ack:msg-001 -> "delivery-confirmed" (TTL: XXs remaining)`.

The TTL value decreases each time you click GET, confirming the countdown is active.

### 1.6 Test 4 — TTL

1. Type `ack:msg-001` in the **Key** field.
2. Click **TTL** (cyan button).
3. The result line shows: `TTL ack:msg-001 -> XXs remaining` in cyan.

Click TTL repeatedly to watch the countdown. When the key does not exist (after expiration or deletion), the result shows: `TTL ack:msg-001 -> key does not exist`.

### 1.7 Test 5 — GETDEL

1. First, create a new entry: type `ack:msg-002` in Key, `pending-ack` in Value, `60` in TTL. Click **SET**.
2. Now type `ack:msg-002` in Key and click **GETDEL** (purple button).
3. The result line shows: `GETDEL ack:msg-002 -> "pending-ack" (key deleted)` in purple.
4. The entry disappears from the **Cache Entries** table.
5. The **Event Log** shows a purple `GETDEL ack:msg-002` event.
6. Click **GET** on the same key: `GET ack:msg-002 -> (nil)`. The key no longer exists.

GETDEL returns the value and atomically deletes the key. This is the exact operation needed for correlation ID consumption: read the stored identifier and remove it in a single atomic step, preventing duplicate processing.

### 1.8 Test 6 — Duplicate detection (overwrite)

1. Type `ack:msg-003` in Key, `first-delivery` in Value, `60` in TTL. Click **SET**.
2. Result: `SET ack:msg-003 -> OK (TTL: 60s)`.
3. Now type `ack:msg-003` again in Key, `second-delivery` in Value, `60` in TTL. Click **SET**.
4. Result: `SET ack:msg-003 -> OK (TTL: 60s) [OVERWRITTEN - key already existed]` — shown in amber.

The `[OVERWRITTEN]` indicator proves the key already existed. In a correlation workflow, this means a duplicate delivery was detected: the correlation ID was already stored from a previous message. The application can use this signal to flag or reject the duplicate.

### 1.9 Test 7 — Auto-expiration

1. Type `ack:msg-004` in Key, `will-expire` in Value, `10` in TTL. Click **SET**.
2. Watch the **Cache Entries** table:
   - The TTL column shows a countdown, updating every 2 seconds.
   - Below 60s, the TTL turns **amber**. Below 30s, it turns **orange bold**.
   - When the TTL reaches 0, the entry disappears from the table.
3. The **Event Log** shows an orange `EXPIRED ack:msg-004 (auto-expired by TTL)` event.

No manual deletion was needed. The cache automatically evicted the entry after the TTL elapsed. This validates that temporary correlation data is self-cleaning.

### 1.10 Summary

All 7 functional tests pass against Redis.

| # | Test | Redis Result |
|---|------|-------------|
| 1 | PING | PONG |
| 2 | SET with TTL | OK, countdown active |
| 3 | GET | Returns value + remaining TTL |
| 4 | TTL | Returns remaining seconds |
| 5 | GETDEL | Returns value, key deleted atomically |
| 6 | Duplicate detection | `[OVERWRITTEN]` on existing key |
| 7 | Auto-expiration | Entry auto-removed, EXPIRED event logged |

---

## Act 2: Live Switch to Data Grid

### 2.1 Patch the deployment

```bash
oc patch deployment redis-client-app -n rhdg-poc-test \
  --patch-file patches/patch-redis-client-to-datagrid.yaml
oc rollout status deployment/redis-client-app -n rhdg-poc-test
```

### 2.2 Verify the switch

```bash
curl -sk "https://${ROUTE}/api/info" | jq .
```

Expected:

```json
{
  "cluster": "healthcare",
  "cache": "respCache",
  "backend_host": "healthcare",
  "backend_port": 11222,
  "status": "connected",
  "ping": "PONG"
}
```

Refresh the browser. The connection cards now show `healthcare` as Host and `11222` as Port. The app code is **identical** — only the connection endpoint changed via environment variables.

---

## Act 3: Data Grid — Same 7 Tests

Repeat all 7 tests from Act 1. The operations and results are identical, proving RESP protocol transparency.

### 3.1 Test 1 — PING

Already confirmed in step 2.2: `"ping": "PONG"`, `"status": "connected"`.

### 3.2 Test 2 — SET with TTL

1. Type `ack:msg-101` in Key, `confirmed-via-datagrid` in Value, `30` in TTL. Click **SET**.
2. Result: `SET ack:msg-101 -> OK (TTL: 30s)`.
3. Entry appears in the table. Event appears in the **Event Log**.

### 3.3 Test 3 — GET

1. Type `ack:msg-101` in Key. Click **GET**.
2. Result: `GET ack:msg-101 -> "confirmed-via-datagrid" (TTL: XXs remaining)`.

### 3.4 Test 4 — TTL

1. Type `ack:msg-101` in Key. Click **TTL**.
2. Result: `TTL ack:msg-101 -> XXs remaining`.

### 3.5 Test 5 — GETDEL

1. Create: `ack:msg-102`, value `pending-ack-dg`, TTL `60`. Click **SET**.
2. Type `ack:msg-102` in Key. Click **GETDEL**.
3. Result: `GETDEL ack:msg-102 -> "pending-ack-dg" (key deleted)`.
4. Click **GET**: `GET ack:msg-102 -> (nil)`.

### 3.6 Test 6 — Duplicate detection

1. SET `ack:msg-103` = `first` (TTL 60). Result: no overwrite flag.
2. SET `ack:msg-103` = `second` (TTL 60). Result: `[OVERWRITTEN - key already existed]`.

### 3.7 Test 7 — Auto-expiration

1. SET `ack:msg-104` = `short-lived` with TTL `10`.
2. Watch the TTL countdown in the table: amber below 60s, orange bold below 30s.
3. Entry disappears. Event Log shows `EXPIRED ack:msg-104 (auto-expired by TTL)`.

### 3.8 Summary

| # | Test | Redis | Data Grid | Identical? |
|---|------|-------|-----------|-----------|
| 1 | PING | PONG | PONG | Yes |
| 2 | SET with TTL | OK | OK | Yes |
| 3 | GET | Value + TTL | Value + TTL | Yes |
| 4 | TTL | Remaining seconds | Remaining seconds | Yes |
| 5 | GETDEL | Atomic get + delete | Atomic get + delete | Yes |
| 6 | Duplicate detection | OVERWRITTEN | OVERWRITTEN | Yes |
| 7 | Auto-expiration | EXPIRED event | EXPIRED event | Yes |

The application code did not change. Only the backend endpoint was patched from Redis to Data Grid. All 7 functional tests produce identical results.

---

## Act 4: Cross-Application Pub/Sub

### 4.1 Start external-cache-client

In a separate terminal, start the Quarkus app that connects to Data Grid via the external RESP Route:

```bash
cd apps/external-cache-client
DG_PASSWORD=<your-healthcare-password> mvn quarkus:dev
```

### 4.2 Verify connection

```bash
curl -s http://localhost:8080/api/cache/info | jq .
```

Expected:

```json
{
  "host": "respcache.apps.<your-cluster-domain>",
  "port": 443,
  "connected": true
}
```

### 4.3 Cross-app event sharing

Keep the **redis-client-app browser** and the **external-cache-client terminal** visible side by side.

1. In the browser, SET `ack:msg-200` = `from-spring-boot` (TTL 60). Both the browser Event Log AND the Quarkus terminal show the event.
2. In a terminal, write via the Quarkus app:
   ```bash
   curl -X PUT http://localhost:8080/api/cache/ack:msg-201 \
     -H "Content-Type: text/plain" -d "from-quarkus"
   ```
3. The entry appears in the **redis-client-app browser UI** in real time via Pub/Sub.

Two different frameworks (Spring Boot on OpenShift + Quarkus running locally), same Data Grid cluster, same Pub/Sub channel, real-time bidirectional event sharing over standard RESP protocol.

---

## Act 5: Spring Cache and Spring Session

### 5.1 Spring Cache

```bash
ROUTE=$(oc get route spring-cache-app -n rhdg-poc-test -o jsonpath='{.spec.host}')

# First call: fetches from source, stores in cache
curl -sk "https://${ROUTE}/api/specialties" | jq .

# Second call: served from cache (faster response)
curl -sk "https://${ROUTE}/api/specialties" | jq .

# Cache statistics
curl -sk "https://${ROUTE}/api/specialties/stats" | jq .
```

The `stats` endpoint shows hit/miss counts confirming cache is working.

### 5.2 Spring Session

```bash
ROUTE=$(oc get route spring-session-app -n rhdg-poc-test -o jsonpath='{.spec.host}')

# Create a session attribute
curl -sk -c /tmp/cookies.txt -X PUT "https://${ROUTE}/api/session/attributes/user" \
  -H "Content-Type: text/plain" -d 'daniel' | jq .

# Read back the session (using the same cookie)
curl -sk -b /tmp/cookies.txt "https://${ROUTE}/api/session" | jq .
```

The session is stored in the `user-sessions` cache on the `platform` cluster, not in the application pod. If the pod restarts, the session survives.

---

## Act 6: Data Grid Console

### 6.1 Open the Console

```
https://<healthcare-route-hostname>/console/
```

Log in with `ops-admin` credentials from `manifests/03-healthcare-credentials.yaml`.

### 6.2 Inspect the cluster

1. **Data Container** — verify both nodes are online and healthy.
2. **respCache** — open the cache and inspect entries created during the demo.
3. **Cache metrics** — observe hit/miss ratios, entry count, memory usage.
4. **event-tracking** and **app-config** — verify cache configuration matches the Cache CRs.

### 6.3 Platform cluster

```
https://<platform-route-hostname>/console/
```

Log in with `ops-admin` credentials from `manifests/04-platform-credentials.yaml`.

Inspect the `user-sessions` cache to see the session data created in Act 5.2.

---

## Act 7: High Availability

### 7.1 Verify cluster state

```bash
oc get pods -l clusterName=healthcare -n rhdg-poc-test
```

Expected: 2 pods Running.

### 7.2 Create test data

In the browser, SET `ack:ha-test` = `survives-failover` (TTL 300).

### 7.3 Kill a pod

```bash
oc delete pod healthcare-0 -n rhdg-poc-test
```

### 7.4 Verify data survives

While the pod restarts, click **GET** on `ack:ha-test` in the browser. The value is still available from the surviving pod (`healthcare-1`).

```bash
# Wait for recovery
oc wait --for=condition=WellFormed infinispan/healthcare -n rhdg-poc-test --timeout=120s
oc get pods -l clusterName=healthcare -n rhdg-poc-test
```

Both pods are back. Data was preserved on the surviving owner and automatically rebalanced. Distributed caches with `owners: 2` tolerate single-node failures with zero data loss.

---

## Validation Matrix

This matrix summarizes every capability validated during the PoC.

### Consumption Models

| # | Capability | How Validated | App / Cache | Evidence |
|---|-----------|---------------|-------------|----------|
| C-1 | RESP Protocol (Redis compatibility) | Same Spring Boot + Jedis app works against Redis then Data Grid, zero code changes | `redis-client-app` -> `respCache` | Act 1-3 |
| C-2 | RESP — 7 Functional Tests | PING, SET+TTL, GET, TTL, GETDEL, duplicate detection, auto-expiration | `redis-client-app` -> `respCache` | Act 1, Act 3 |
| C-3 | RESP Pub/Sub (cross-app) | Spring Boot + Quarkus share events via PUBLISH/SUBSCRIBE | `redis-client-app` + `external-cache-client` | Act 4 |
| C-4 | Spring Cache (`@Cacheable`) | Spring Boot app caches data via Hot Rod | `spring-cache-app` -> `app-config` | Act 5.1 |
| C-5 | Spring Session | HTTP session externalized to Data Grid | `spring-session-app` -> `user-sessions` | Act 5.2 |

### Multi-Tenancy and Isolation

| # | Capability | How Validated | Evidence |
|---|-----------|---------------|----------|
| T-1 | Multiple isolated clusters | Two independent Infinispan CRs, each with own StatefulSet and pods | [README Ch.6](README.md#6-deploy-data-grid-clusters) |
| T-2 | Per-cluster credentials | Independent Secrets, no cross-cluster access | [README Ch.5](README.md#5-create-credentials) |
| T-3 | RBAC roles | `application` for apps, `admin` for operations | [README Section 5.4](README.md#54-rbac-roles) |

### Operations

| # | Capability | How Validated | Evidence |
|---|-----------|---------------|----------|
| O-1 | High Availability | Pod killed, data preserved, auto-recovery | Act 7 |
| O-2 | Monitoring | ServiceMonitor auto-created, Prometheus scrapes, Grafana dashboard | [README Ch.11](README.md#11-monitoring) |
| O-3 | Declarative cache management | Caches defined as Cache CRs in YAML | [README Ch.7](README.md#7-create-caches) |

### Security

| # | Capability | How Validated | Evidence |
|---|-----------|---------------|----------|
| S-1 | TLS encryption | Custom TLS (cert-manager) and automatic (Service) | [README Ch.6](README.md#6-deploy-data-grid-clusters) |
| S-2 | Authentication | All endpoints require credentials | Act 1-3 |
| S-3 | Credential isolation | Per-cluster Secrets | [README Ch.5](README.md#5-create-credentials) |
