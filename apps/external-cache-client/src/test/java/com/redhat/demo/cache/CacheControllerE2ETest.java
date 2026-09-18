package com.redhat.demo.cache;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@Tag("e2e")
@QuarkusTest
@TestProfile(CacheControllerE2ETest.DataGridProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CacheControllerE2ETest {

    public static class DataGridProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "datagrid.resp.host", "respcache.apps.ocp4.masales.cloud",
                    "datagrid.resp.port", "443",
                    "datagrid.resp.username", "app-healthcare",
                    "datagrid.resp.password", "h34lthc4r3!",
                    "datagrid.resp.tls", "true"
            );
        }
    }

    @Test
    @Order(1)
    void putEntry() {
        given()
            .contentType(ContentType.TEXT)
            .body("e2e-value")
        .when()
            .put("/api/cache/e2e-key")
        .then()
            .statusCode(200)
            .body("key", is("e2e-key"))
            .body("value", is("e2e-value"))
            .body("action", is("stored"));
    }

    @Test
    @Order(2)
    void getEntry() {
        given()
        .when()
            .get("/api/cache/e2e-key")
        .then()
            .statusCode(200)
            .body("key", is("e2e-key"))
            .body("value", is("e2e-value"));
    }

    @Test
    @Order(3)
    void deleteEntry() {
        given()
        .when()
            .delete("/api/cache/e2e-key")
        .then()
            .statusCode(200)
            .body("action", is("deleted"));

        given()
        .when()
            .get("/api/cache/e2e-key")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(4)
    void info() {
        given()
        .when()
            .get("/api/cache/info")
        .then()
            .statusCode(200)
            .body("backend", is("Data Grid (RESP)"))
            .body("protocol", is("RESP"))
            .body("status", is("PONG"));
    }
}
