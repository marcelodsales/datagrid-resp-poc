# spring-cache-app

Spring Boot + Data Grid Hot Rod app demonstrating **Spring Cache** (`@Cacheable`, `@CachePut`, `@CacheEvict`) backed by Red Hat Data Grid.
Connects to the `app-config` cache (replicated, protostream) on the `healthcare` cluster.

## What it tests

| Endpoint | Spring Cache | Purpose |
|---|---|---|
| `GET /api/specialties/{code}` | `@Cacheable` | First call = cache miss (slow), second call = cache hit (fast) |
| `PUT /api/specialties` | `@CachePut` | Saves and caches a new entry |
| `DELETE /api/specialties/{code}/cache` | `@CacheEvict` | Evicts cache entry, forcing next lookup |
| `GET /api/specialties` | — | Lists all reference data (bypasses cache) |
| `GET /api/specialties/stats` | — | Shows lookup count (increments only on cache miss) |

## Run tests locally

Requires Docker/Podman running (Testcontainers spins up an Infinispan server automatically).

```bash
mvn test
```

6 tests prove: cache miss, cache hit, eviction, put-then-hit, list, and 404.

## Run the app locally

```bash
docker run -d --name infinispan -p 11222:11222 -e USER=admin -e PASS=password quay.io/infinispan/server:15.1
mvn spring-boot:run
curl http://localhost:8080/api/specialties/CAR
```

## Stack

Spring Boot 3.5.16, Java 21, Infinispan Spring Boot Starter 15.2.1.Final, UBI9 OpenJDK 21 (runtime)
