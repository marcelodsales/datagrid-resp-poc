package com.redhat.demo.cache;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

public class RedisTestResource implements QuarkusTestResourceLifecycleManager {

    private GenericContainer<?> redis;

    @Override
    public Map<String, String> start() {
        redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
                .withExposedPorts(6379);
        redis.start();

        return Map.of(
                "datagrid.resp.host", redis.getHost(),
                "datagrid.resp.port", String.valueOf(redis.getMappedPort(6379)),
                "datagrid.resp.tls", "false",
                "datagrid.resp.username", "none",
                "datagrid.resp.password", "none"
        );
    }

    @Override
    public void stop() {
        if (redis != null) {
            redis.stop();
        }
    }
}
