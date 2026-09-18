package com.redhat.demo.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedisBackendTest extends AbstractRespTest {

    static final String PASSWORD = "test-password";

    static GenericContainer<?> redis = new GenericContainer<>("docker.io/bitnami/redis:latest")
            .withExposedPorts(6379)
            .withEnv("REDIS_PASSWORD", PASSWORD);

    @BeforeAll
    static void startContainer() {
        redis.start();
    }

    @AfterAll
    static void stopContainer() {
        redis.stop();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("app.redis.host", redis::getHost);
        registry.add("app.redis.port", () -> redis.getMappedPort(6379));
        registry.add("app.redis.username", () -> "default");
        registry.add("app.redis.password", () -> PASSWORD);
    }
}
