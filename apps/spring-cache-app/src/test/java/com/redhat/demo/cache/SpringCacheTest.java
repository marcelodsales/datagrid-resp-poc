package com.redhat.demo.cache;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SpringCacheTest {

    static final String USER = "admin";
    static final String PASSWORD = "password";

    static GenericContainer<?> infinispan = new GenericContainer<>("quay.io/infinispan/server:15.1")
            .withExposedPorts(11222)
            .withEnv("USER", USER)
            .withEnv("PASS", PASSWORD)
            .waitingFor(new LogMessageWaitStrategy().withRegEx(".*Infinispan Server.*started in.*"));

    @BeforeAll
    static void startContainer() {
        infinispan.start();
    }

    @AfterAll
    static void stopContainer() {
        infinispan.stop();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("infinispan.remote.server-list",
                () -> infinispan.getHost() + ":" + infinispan.getMappedPort(11222));
        registry.add("infinispan.remote.auth-username", () -> USER);
        registry.add("infinispan.remote.auth-password", () -> PASSWORD);
        registry.add("infinispan.remote.use-ssl", () -> "false");
    }

    @LocalServerPort
    int port;

    @BeforeEach
    void setup() {
        RestAssured.port = port;
    }

    @Test
    @Order(1)
    void listPreloadedSpecialties() {
        given()
            .when().get("/api/specialties")
            .then()
                .statusCode(200)
                .body("CAR.name", equalTo("Cardiology"))
                .body("PED.name", equalTo("Pediatrics"));
    }

    @Test
    @Order(2)
    void firstLookupIsCacheMiss() {
        // reset stats
        given().when().delete("/api/specialties/stats").then().statusCode(204);

        long start = System.currentTimeMillis();
        given()
            .when().get("/api/specialties/CAR")
            .then()
                .statusCode(200)
                .body("code", equalTo("CAR"))
                .body("name", equalTo("Cardiology"));
        long elapsed = System.currentTimeMillis() - start;

        // first call hits the simulated slow lookup (~500ms)
        given()
            .when().get("/api/specialties/stats")
            .then()
                .body("lookupCount", equalTo(1));
    }

    @Test
    @Order(3)
    void secondLookupIsCacheHit() {
        // reset stats
        given().when().delete("/api/specialties/stats").then().statusCode(204);

        // prime cache
        given().when().get("/api/specialties/NEU").then().statusCode(200);

        // second call — should be cached (no new lookup)
        long start = System.currentTimeMillis();
        given()
            .when().get("/api/specialties/NEU")
            .then()
                .statusCode(200)
                .body("name", equalTo("Neurology"));
        long elapsed = System.currentTimeMillis() - start;

        // only 1 lookup (the first), second was a cache hit
        given()
            .when().get("/api/specialties/stats")
            .then()
                .body("lookupCount", equalTo(1));
    }

    @Test
    @Order(4)
    void evictForcesNewLookup() {
        // reset stats
        given().when().delete("/api/specialties/stats").then().statusCode(204);

        // prime cache
        given().when().get("/api/specialties/TRA").then().statusCode(200);

        // evict
        given().when().delete("/api/specialties/TRA/cache").then().statusCode(204);

        // next call must hit the "database" again
        given().when().get("/api/specialties/TRA").then().statusCode(200);

        given()
            .when().get("/api/specialties/stats")
            .then()
                .body("lookupCount", equalTo(2));
    }

    @Test
    @Order(5)
    void putUpdatesCache() {
        String body = """
                {"code":"DER","name":"Dermatology","department":"Internal Medicine"}
                """;

        given()
            .contentType(ContentType.JSON)
            .body(body)
            .when().put("/api/specialties")
            .then()
                .statusCode(200)
                .body("code", equalTo("DER"));

        // reset stats — subsequent GET should NOT increment lookupCount
        given().when().delete("/api/specialties/stats").then().statusCode(204);

        given()
            .when().get("/api/specialties/DER")
            .then()
                .statusCode(200)
                .body("name", equalTo("Dermatology"));

        given()
            .when().get("/api/specialties/stats")
            .then()
                .body("lookupCount", equalTo(0));
    }

    @Test
    @Order(6)
    void notFoundReturns404() {
        given()
            .when().get("/api/specialties/XYZ")
            .then()
                .statusCode(404);
    }
}
