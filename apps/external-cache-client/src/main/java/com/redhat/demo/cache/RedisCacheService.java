package com.redhat.demo.cache;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import redis.clients.jedis.UnifiedJedis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class RedisCacheService implements CacheService {

    @Inject
    UnifiedJedis jedis;

    private static final String CHANNEL = "cache-events";

    @Override
    public void put(String key, String value) {
        jedis.set(key, value);
        jedis.publish(CHANNEL, "SET\t" + key + "\t" + value);
    }

    @Override
    public String get(String key) {
        return jedis.get(key);
    }

    @Override
    public void delete(String key) {
        jedis.del(key);
        jedis.publish(CHANNEL, "DEL\t" + key);
    }

    @Override
    public Set<String> keys() {
        return jedis.keys("*");
    }

    @Override
    public Map<String, String> info() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("backend", "Data Grid (RESP)");
        info.put("library", "Jedis 8.0.1");
        info.put("protocol", "RESP");
        info.put("status", jedis.ping());
        return info;
    }
}
