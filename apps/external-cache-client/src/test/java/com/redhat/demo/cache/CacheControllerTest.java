package com.redhat.demo.cache;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(value = RedisTestResource.class, restrictToAnnotatedClass = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CacheControllerTest {

    @Test
    @Order(1)
    void putEntry() {
        given()
            .contentType(ContentType.TEXT)
            .body("test-value")
        .when()
            .put("/api/cache/test-key")
        .then()
            .statusCode(200)
            .body("key", is("test-key"))
            .body("value", is("test-value"))
            .body("action", is("stored"));
    }

    @Test
    @Order(2)
    void getEntry() {
        given()
        .when()
            .get("/api/cache/test-key")
        .then()
            .statusCode(200)
            .body("key", is("test-key"))
            .body("value", is("test-value"));
    }

    @Test
    @Order(3)
    void getEntryNotFound() {
        given()
        .when()
            .get("/api/cache/nonexistent")
        .then()
            .statusCode(404)
            .body("error", is("not found"));
    }

    @Test
    @Order(4)
    void listKeys() {
        given()
        .when()
            .get("/api/cache/keys")
        .then()
            .statusCode(200)
            .body("keys", hasItem("test-key"));
    }

    @Test
    @Order(5)
    void info() {
        given()
        .when()
            .get("/api/cache/info")
        .then()
            .statusCode(200)
            .body("backend", is("Data Grid (RESP)"))
            .body("protocol", is("RESP"));
    }

    @Test
    @Order(6)
    void deleteEntry() {
        given()
        .when()
            .delete("/api/cache/test-key")
        .then()
            .statusCode(200)
            .body("action", is("deleted"));

        given()
        .when()
            .get("/api/cache/test-key")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(7)
    void putMultipleAndListKeys() {
        given().contentType(ContentType.TEXT).body("v1").put("/api/cache/key-a");
        given().contentType(ContentType.TEXT).body("v2").put("/api/cache/key-b");
        given().contentType(ContentType.TEXT).body("v3").put("/api/cache/key-c");

        given()
        .when()
            .get("/api/cache/keys")
        .then()
            .statusCode(200)
            .body("keys", hasItems("key-a", "key-b", "key-c"));
    }

    @Test
    @Order(8)
    void pubSubReceivesEventsOnPut() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        String redisHost = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getValue("datagrid.resp.host", String.class);
        int redisPort = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getValue("datagrid.resp.port", Integer.class);

        JedisPubSub listener = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                received.add(message);
                latch.countDown();
            }
        };

        Thread subThread = new Thread(() -> {
            try (Jedis sub = new Jedis(redisHost, redisPort,
                    DefaultJedisClientConfig.builder().autoNegotiateProtocol(false).build())) {
                sub.subscribe(listener, "cache-events");
            }
        }, "test-subscriber");
        subThread.setDaemon(true);
        subThread.start();

        Thread.sleep(500);

        given().contentType(ContentType.TEXT).body("pubsub-val")
            .when().put("/api/cache/pubsub-key")
            .then().statusCode(200);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Pub/Sub event not received within 5s");
        assertTrue(received.stream().anyMatch(m -> m.contains("SET") && m.contains("pubsub-key")));

        listener.unsubscribe();
    }

    @Test
    void healthCheck() {
        given()
        .when()
            .get("/q/health")
        .then()
            .statusCode(200)
            .body("status", is("UP"));
    }
}
