package com.redhat.demo.session;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
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
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SpringSessionTest {

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
    void newSessionIsCreated() {
        given()
            .when().get("/api/session")
            .then()
                .statusCode(200)
                .body("sessionId", notNullValue())
                .body("attributes", notNullValue());
    }

    @Test
    @Order(2)
    void setAndGetAttribute() {
        Response putResponse = given()
            .contentType(ContentType.TEXT)
            .body("Dr. Garcia")
            .when().put("/api/session/attributes/doctor")
            .then()
                .statusCode(200)
                .body("key", equalTo("doctor"))
                .body("value", equalTo("Dr. Garcia"))
                .extract().response();

        String sessionCookie = putResponse.getCookie("SESSION");

        given()
            .cookie("SESSION", sessionCookie)
            .when().get("/api/session/attributes/doctor")
            .then()
                .statusCode(200)
                .body("value", equalTo("Dr. Garcia"));
    }

    @Test
    @Order(3)
    void sessionPersistsAcrossRequests() {
        Response r1 = given()
            .contentType(ContentType.TEXT)
            .body("Cardiology")
            .when().put("/api/session/attributes/department")
            .then().statusCode(200)
            .extract().response();

        String sessionCookie = r1.getCookie("SESSION");

        given()
            .cookie("SESSION", sessionCookie)
            .contentType(ContentType.TEXT)
            .body("patient-12345")
            .when().put("/api/session/attributes/patientId")
            .then().statusCode(200);

        given()
            .cookie("SESSION", sessionCookie)
            .when().get("/api/session")
            .then()
                .statusCode(200)
                .body("attributes.department", equalTo("Cardiology"))
                .body("attributes.patientId", equalTo("patient-12345"));
    }

    @Test
    @Order(4)
    void differentClientsGetDifferentSessions() {
        String id1 = given()
            .when().get("/api/session")
            .then().statusCode(200)
            .extract().path("sessionId");

        String id2 = given()
            .when().get("/api/session")
            .then().statusCode(200)
            .extract().path("sessionId");

        org.junit.jupiter.api.Assertions.assertNotEquals(id1, id2);
    }

    @Test
    @Order(5)
    void invalidateRemovesSession() {
        Response r = given()
            .contentType(ContentType.TEXT)
            .body("temp-value")
            .when().put("/api/session/attributes/temp")
            .then().statusCode(200)
            .extract().response();

        String sessionCookie = r.getCookie("SESSION");

        given()
            .cookie("SESSION", sessionCookie)
            .when().delete("/api/session")
            .then()
                .statusCode(200)
                .body("invalidated", notNullValue());
    }

    @Test
    @Order(6)
    void afterInvalidateNewSessionHasNoAttributes() {
        Response r = given()
            .contentType(ContentType.TEXT)
            .body("will-be-lost")
            .when().put("/api/session/attributes/data")
            .then().statusCode(200)
            .extract().response();

        String sessionCookie = r.getCookie("SESSION");

        given()
            .cookie("SESSION", sessionCookie)
            .when().delete("/api/session")
            .then().statusCode(200);

        given()
            .when().get("/api/session")
            .then()
                .statusCode(200)
                .body("attributes", not(hasKey("data")));
    }
}
