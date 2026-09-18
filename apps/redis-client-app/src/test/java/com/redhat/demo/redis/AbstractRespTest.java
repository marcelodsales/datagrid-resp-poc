package com.redhat.demo.redis;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.HttpURLConnection;
import java.net.URI;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(OrderAnnotation.class)
abstract class AbstractRespTest {

    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    @Order(1)
    void connectionReturnsConnectedStatus() {
        given()
            .when().get("/api/info")
            .then()
                .statusCode(200)
                .body("status", equalTo("connected"))
                .body("ping", equalTo("PONG"));
    }

    @Test
    @Order(2)
    void setAndGetStringWithTtl() {
        given()
            .contentType(ContentType.TEXT)
            .body("test-value-123")
            .queryParam("ttlSeconds", 60)
        .when()
            .put("/api/strings/test:key")
        .then()
            .statusCode(200)
            .body("operation", equalTo("SET"));

        given()
        .when()
            .get("/api/strings/test:key")
        .then()
            .statusCode(200)
            .body("value", equalTo("test-value-123"))
            .body("ttl", greaterThan(0));
    }

    @Test
    @Order(3)
    void hashSetAndGetAll() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"name\":\"Ana Torres\",\"dept\":\"Oncology\",\"status\":\"active\"}")
        .when()
            .put("/api/hashes/patient:test")
        .then()
            .statusCode(200)
            .body("fields_count", equalTo(3));

        given()
        .when()
            .get("/api/hashes/patient:test")
        .then()
            .statusCode(200)
            .body("fields.name", equalTo("Ana Torres"))
            .body("fields.dept", equalTo("Oncology"));

        given()
        .when()
            .get("/api/hashes/patient:test/status")
        .then()
            .statusCode(200)
            .body("value", equalTo("active"));
    }

    @Test
    @Order(4)
    void incrementCounter() {
        given().when().post("/api/counters/test:counter/incr")
            .then().statusCode(200).body("value", equalTo(1));

        given().when().post("/api/counters/test:counter/incr")
            .then().statusCode(200).body("value", equalTo(2));

        given().when().post("/api/counters/test:counter/decr")
            .then().statusCode(200).body("value", equalTo(1));
    }

    @Test
    @Order(5)
    void getDelReturnsValueAndRemovesKey() {
        given().contentType(ContentType.TEXT).body("volatile-data")
            .queryParam("ttlSeconds", 60)
            .when().put("/api/strings/test:getdel").then().statusCode(200);

        given()
        .when()
            .delete("/api/strings/test:getdel/getdel")
        .then()
            .statusCode(200)
            .body("operation", equalTo("GETDEL"))
            .body("key", equalTo("test:getdel"))
            .body("value", equalTo("volatile-data"));

        given().when().get("/api/strings/test:getdel")
            .then().statusCode(404);
    }

    @Test
    @Order(6)
    void getDelOnMissingKeyReturns404() {
        given()
        .when()
            .delete("/api/strings/nonexistent:key/getdel")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(7)
    void ttlEndpointReturnsTtlValue() {
        given().contentType(ContentType.TEXT).body("ttl-check")
            .queryParam("ttlSeconds", 120)
            .when().put("/api/strings/test:ttl").then().statusCode(200);

        given()
        .when()
            .get("/api/strings/test:ttl/ttl")
        .then()
            .statusCode(200)
            .body("key", equalTo("test:ttl"))
            .body("ttl", greaterThan(0));
    }

    @Test
    @Order(8)
    void setOverwriteReturnsTrueOnExistingKey() {
        given().contentType(ContentType.TEXT).body("first")
            .when().put("/api/strings/test:overwrite").then().statusCode(200)
            .body("overwritten", equalTo(false));

        given().contentType(ContentType.TEXT).body("second")
            .when().put("/api/strings/test:overwrite").then().statusCode(200)
            .body("overwritten", equalTo(true));
    }

    @Test
    @Order(10)
    void deleteKeyReturns404OnSubsequentGet() {
        given().contentType(ContentType.TEXT).body("ephemeral")
            .when().put("/api/strings/test:del").then().statusCode(200);

        given().when().delete("/api/strings/test:del")
            .then().statusCode(200).body("deleted", equalTo(true));

        given().when().get("/api/strings/test:del")
            .then().statusCode(404);
    }

    @Test
    @Order(11)
    void keysListReturnsStoredEntries() {
        given().contentType(ContentType.TEXT).body("a")
            .when().put("/api/strings/list:one").then().statusCode(200);
        given().contentType(ContentType.TEXT).body("b")
            .when().put("/api/strings/list:two").then().statusCode(200);

        given()
            .queryParam("pattern", "list:*")
        .when()
            .get("/api/keys")
        .then()
            .statusCode(200)
            .body("count", equalTo(2))
            .body("keys", hasItems("list:one", "list:two"));
    }

    @Test
    @Order(12)
    void snapshotReturnsAllEntries() {
        given().contentType(ContentType.TEXT).body("snap-val")
            .when().put("/api/strings/snapkey").then().statusCode(200);

        given()
        .when()
            .get("/api/snapshot")
        .then()
            .statusCode(200)
            .body("find { it.key == 'snapkey' }.value", equalTo("snap-val"))
            .body("find { it.key == 'snapkey' }.type", equalTo("string"));
    }

    @Test
    @Order(13)
    void sseReceivesPubSubEvents() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread sseReader = new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(
                        "http://localhost:" + port + "/api/events").toURL().openConnection();
                conn.setRequestProperty("Accept", "text/event-stream");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line;
                String currentEvent = "";
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        currentEvent = line.substring(6);
                    } else if (line.startsWith("data:") && "cache-event".equals(currentEvent)) {
                        received.add(line.substring(5));
                        latch.countDown();
                        break;
                    }
                }
                reader.close();
            } catch (Exception e) {
                // timeout or connection closed
            }
        });
        sseReader.setDaemon(true);
        sseReader.start();

        Thread.sleep(1000);

        given().contentType(ContentType.TEXT).body("sse-test-value")
            .when().put("/api/strings/sse:key").then().statusCode(200);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "SSE event not received within 5s");
        assertTrue(received.stream().anyMatch(s -> s.contains("SET") && s.contains("sse:key")));
    }
}
