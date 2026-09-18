# redis-client-app

Spring Boot + Jedis app that works identically against **Redis** and **Red Hat Data Grid** (via RESP protocol).
Proves that existing Redis applications can switch to Data Grid by changing only the connection endpoint.

## What it tests

| Endpoint | Redis operation | Purpose |
|---|---|---|
| `GET /api/info` | PING | Connection status and backend identification |
| `PUT /api/strings/{key}?ttlSeconds=N` | SET / SETEX | Store string values with optional TTL |
| `GET /api/strings/{key}` | GET + TTL | Retrieve value and remaining TTL |
| `DELETE /api/strings/{key}` | DEL | Delete a key |
| `PUT /api/hashes/{key}` | HSET | Store a JSON object as a Redis hash |
| `GET /api/hashes/{key}` | HGETALL | Retrieve all hash fields |
| `POST /api/counters/{key}/incr` | INCR | Atomic counter increment |
| `GET /api/keys?pattern=*` | KEYS | List keys by pattern |

## Run tests locally

Requires Docker/Podman running (Testcontainers spins up both Redis and Infinispan automatically).

```bash
mvn test
```

- `RedisBackendTest` — 6 tests against a Redis container
- `DataGridRespBackendTest` — same 6 tests against an Infinispan container (RESP endpoint)

Both suites share the same test logic (`AbstractRespTest`). If all 12 pass, RESP transparency is confirmed.

## Run the app locally

```bash
# Start a Redis (or Infinispan) container
docker run -d --name redis -p 6379:6379 -e REDIS_PASSWORD=changeme bitnami/redis:latest

# Run the app
mvn spring-boot:run

# Test it
curl http://localhost:8080/api/info
```

## Switch backend (Redis → Data Grid)

Change environment variables — no code changes needed:

| Variable | Redis | Data Grid |
|---|---|---|
| `REDIS_HOST` | `redis` | `healthcare` |
| `REDIS_PORT` | `6379` | `11222` |
| `REDIS_USERNAME` | `default` | `app-healthcare` |
| `REDIS_PASSWORD` | (redis password) | (datagrid password) |

## Stack

Spring Boot 3.5.16, Java 21, Jedis 5.2.0, UBI9 OpenJDK 25 (runtime)
