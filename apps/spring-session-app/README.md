# spring-session-app

Spring Boot app demonstrating **Spring Session** externalization to Red Hat Data Grid via Hot Rod.
HTTP sessions are stored in the `sessions` cache (distributed, protostream) on the `platform` cluster.

## What it tests

| Endpoint | Purpose |
|---|---|
| `GET /api/session` | Show current session: ID, timestamps, all attributes |
| `PUT /api/session/attributes/{key}` | Store a value in the session |
| `GET /api/session/attributes/{key}` | Read a value from the session |
| `DELETE /api/session` | Invalidate the session |

## Run tests locally

Requires Docker/Podman running (Testcontainers spins up an Infinispan server automatically).

```bash
mvn test
```

6 tests prove: session creation, attribute set/get, persistence across requests, session isolation, and invalidation.

## Run the app locally

```bash
docker run -d --name infinispan -p 11222:11222 -e USER=admin -e PASS=password quay.io/infinispan/server:15.1
mvn spring-boot:run
curl -c cookies.txt -b cookies.txt http://localhost:8080/api/session
```

## Stack

Spring Boot 3.5.16, Java 21, Infinispan Spring Boot Starter 15.2.1.Final, UBI9 OpenJDK 21 (runtime)
