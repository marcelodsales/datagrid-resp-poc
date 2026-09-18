package com.redhat.demo.redis.controller;

import com.redhat.demo.redis.config.RedisConfig;
import com.redhat.demo.redis.service.CacheEventService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import redis.clients.jedis.UnifiedJedis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class CacheController {

    private final UnifiedJedis jedis;
    private final RedisConfig config;
    private final CacheEventService eventService;

    public CacheController(UnifiedJedis jedis, RedisConfig config, CacheEventService eventService) {
        this.jedis = jedis;
        this.config = config;
        this.eventService = eventService;
    }

    // ── Connection info ─────────────────────────────────────────────

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cluster", config.getCluster());
        result.put("cache", config.getCacheName());
        result.put("mode", config.getCacheMode());
        result.put("backend_host", config.getHost());
        result.put("backend_port", config.getPort());
        result.put("backend_user", config.getUsername());
        try {
            String pong = jedis.ping();
            result.put("status", "connected");
            result.put("ping", pong);
        } catch (Exception e) {
            result.put("status", "disconnected");
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ── String operations (SET / GET / DEL / TTL) ───────────────────

    @PutMapping("/strings/{key}")
    public Map<String, Object> setString(
            @PathVariable String key,
            @RequestBody String value,
            @RequestParam(required = false) Long ttlSeconds) {
        boolean existed = jedis.exists(key);
        if (ttlSeconds != null && ttlSeconds > 0) {
            jedis.setex(key, ttlSeconds, value);
        } else {
            jedis.set(key, value);
        }
        eventService.publish("SET", key, value);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", "SET");
        result.put("key", key);
        result.put("ttl_seconds", ttlSeconds);
        result.put("overwritten", existed);
        return result;
    }

    @GetMapping("/strings/{key}")
    public ResponseEntity<Map<String, Object>> getString(@PathVariable String key) {
        String value = jedis.get(key);
        if (value == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("value", value);
        result.put("ttl", jedis.ttl(key));
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/strings/{key}")
    public Map<String, Object> deleteKey(@PathVariable String key) {
        long deleted = jedis.del(key);
        eventService.publish("DEL", key, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", "DEL");
        result.put("key", key);
        result.put("deleted", deleted > 0);
        return result;
    }

    @GetMapping("/strings/{key}/ttl")
    public Map<String, Object> getTtl(@PathVariable String key) {
        long ttl = jedis.ttl(key);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("ttl", ttl);
        return result;
    }

    @DeleteMapping("/strings/{key}/getdel")
    public ResponseEntity<Map<String, Object>> getAndDelete(@PathVariable String key) {
        String value = jedis.getDel(key);
        if (value == null) {
            return ResponseEntity.notFound().build();
        }
        eventService.publish("GETDEL", key, value);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", "GETDEL");
        result.put("key", key);
        result.put("value", value);
        return ResponseEntity.ok(result);
    }

    // ── Hash operations (HSET / HGET / HGETALL) ─────────────────────

    @PutMapping("/hashes/{key}")
    public Map<String, Object> setHash(
            @PathVariable String key,
            @RequestBody Map<String, String> fields) {
        jedis.hset(key, fields);
        eventService.publish("HSET", key, fields.toString());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", "HSET");
        result.put("key", key);
        result.put("fields_count", fields.size());
        return result;
    }

    @GetMapping("/hashes/{key}")
    public ResponseEntity<Map<String, Object>> getHash(@PathVariable String key) {
        Map<String, String> fields = jedis.hgetAll(key);
        if (fields == null || fields.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("fields", fields);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/hashes/{key}/{field}")
    public ResponseEntity<Map<String, Object>> getHashField(
            @PathVariable String key,
            @PathVariable String field) {
        String value = jedis.hget(key, field);
        if (value == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("field", field);
        result.put("value", value);
        return ResponseEntity.ok(result);
    }

    // ── Key listing ─────────────────────────────────────────────────

    @GetMapping("/keys")
    public Map<String, Object> listKeys(
            @RequestParam(defaultValue = "*") String pattern) {
        Set<String> keys = jedis.keys(pattern);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pattern", pattern);
        result.put("count", keys.size());
        result.put("keys", keys);
        return result;
    }

    // ── Counter operations (INCR / DECR) ────────────────────────────

    @PostMapping("/counters/{key}/incr")
    public Map<String, Object> increment(@PathVariable String key) {
        long val = jedis.incr(key);
        eventService.publish("SET", key, String.valueOf(val));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", "INCR");
        result.put("key", key);
        result.put("value", val);
        return result;
    }

    @PostMapping("/counters/{key}/decr")
    public Map<String, Object> decrement(@PathVariable String key) {
        long val = jedis.decr(key);
        eventService.publish("SET", key, String.valueOf(val));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", "DECR");
        result.put("key", key);
        result.put("value", val);
        return result;
    }
}
