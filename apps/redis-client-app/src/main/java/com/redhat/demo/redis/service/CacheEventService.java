package com.redhat.demo.redis.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.UnifiedJedis;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class CacheEventService {

    private static final Logger log = LoggerFactory.getLogger(CacheEventService.class);
    public static final String CHANNEL = "cache-events";

    private final UnifiedJedis jedis;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private volatile Thread subscriberThread;
    private volatile JedisPubSub pubSub;

    @Value("${app.redis.host}")
    private String host;

    @Value("${app.redis.port}")
    private int port;

    @Value("${app.redis.username}")
    private String username;

    @Value("${app.redis.password}")
    private String password;

    @Value("${app.redis.ssl:false}")
    private boolean ssl;

    public CacheEventService(UnifiedJedis jedis) {
        this.jedis = jedis;
    }

    private volatile boolean running = true;

    @PostConstruct
    public void startSubscriber() {
        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                broadcast(message);
            }
        };

        subscriberThread = new Thread(() -> {
            while (running && !Thread.currentThread().isInterrupted()) {
                try (Jedis subscriber = createSubscriberConnection()) {
                    log.info("Subscribing to channel '{}' on {}:{}", CHANNEL, host, port);
                    subscriber.subscribe(pubSub, CHANNEL);
                } catch (Exception e) {
                    if (!running || Thread.currentThread().isInterrupted()) break;
                    log.warn("Subscriber disconnected, reconnecting in 3s: {}", e.getMessage());
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { break; }
                }
            }
            log.info("Subscriber thread stopped");
        }, "cache-event-subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    @PreDestroy
    public void stopSubscriber() {
        running = false;
        if (pubSub != null && pubSub.isSubscribed()) {
            try { pubSub.unsubscribe(); } catch (Exception ignored) {}
        }
        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
    }

    public void publish(String eventType, String key, String value) {
        String message = eventType + "\t" + key;
        if (value != null) {
            message += "\t" + value;
        }
        jedis.publish(CHANNEL, message);
    }

    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (Exception e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    public List<Map<String, Object>> snapshot() {
        Set<String> keys = jedis.keys("*");
        List<Map<String, Object>> result = new ArrayList<>();
        for (String key : keys) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", key);
            String type = jedis.type(key);
            entry.put("type", type);
            if ("string".equals(type)) {
                entry.put("value", jedis.get(key));
            } else if ("hash".equals(type)) {
                entry.put("value", jedis.hgetAll(key).toString());
            } else {
                entry.put("value", "[" + type + "]");
            }
            long ttl = jedis.ttl(key);
            entry.put("ttl", ttl);
            result.add(entry);
        }
        return result;
    }

    private void broadcast(String message) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("cache-event").data(message));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }

    private Jedis createSubscriberConnection() throws Exception {
        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                .user(username)
                .password(password)
                .autoNegotiateProtocol(false);
        if (ssl) {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{trustAllManager()}, null);
            javax.net.ssl.SSLParameters sslParams = new javax.net.ssl.SSLParameters();
            sslParams.setEndpointIdentificationAlgorithm(null);
            builder.ssl(true)
                   .sslSocketFactory(ctx.getSocketFactory())
                   .sslParameters(sslParams)
                   .hostnameVerifier((hostname, session) -> true);
        }
        return new Jedis(host, port, builder.build());
    }

    private static X509TrustManager trustAllManager() {
        return new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] c, String t) {}
            public void checkServerTrusted(X509Certificate[] c, String t) {}
        };
    }
}
