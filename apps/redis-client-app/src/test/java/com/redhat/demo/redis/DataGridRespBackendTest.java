package com.redhat.demo.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DataGridRespBackendTest extends AbstractRespTest {

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
        registry.add("app.redis.host", infinispan::getHost);
        registry.add("app.redis.port", () -> infinispan.getMappedPort(11222));
        registry.add("app.redis.username", () -> USER);
        registry.add("app.redis.password", () -> PASSWORD);
    }
}
