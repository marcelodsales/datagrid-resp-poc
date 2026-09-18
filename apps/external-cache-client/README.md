# External Cache Client

Quarkus 3.39.5 application that accesses Data Grid `respCache` from **outside** OpenShift using the RESP protocol (Redis-compatible). Uses `quarkus-redis-client` (Vert.x Redis) — the same library any Redis application would use.

Proves that Data Grid can be consumed externally via a standard Redis client library with zero code changes.

## Prerequisites

- Java 21+
- Maven 3.9+
- Data Grid `healthcare` cluster running with `respCache` and external Route enabled (`respcache.apps.ocp4.masales.cloud`)

## Running

```bash
cd apps/external-cache-client
mvn quarkus:dev
```

Listening on http://localhost:8080

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `DG_PASSWORD` | `h34lthc4r3!` | Password for RESP connection |

## REST API

```bash
# Store a value
curl -X PUT http://localhost:8080/api/cache/mykey \
  -H "Content-Type: text/plain" -d "myvalue"

# Retrieve a value
curl http://localhost:8080/api/cache/mykey

# Delete a value
curl -X DELETE http://localhost:8080/api/cache/mykey

# List all keys
curl http://localhost:8080/api/cache/keys

# Backend info
curl http://localhost:8080/api/cache/info

# Health check
curl http://localhost:8080/q/health
```

## What This Proves

An application running **outside** OpenShift, using a standard Redis client library (`quarkus-redis-client`), can read and write data to Data Grid's `respCache` through the RESP endpoint exposed via an OpenShift Route (`respcache.apps.ocp4.masales.cloud`).

The application code has no dependency on Infinispan or Data Grid — it is a pure Redis client. The connection URL (`rediss://respcache.apps.ocp4.masales.cloud:443`) is the only thing that ties it to Data Grid instead of Redis.
