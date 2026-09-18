package com.redhat.demo.cache;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

@ApplicationScoped
public class CacheEventListener {

    private static final Logger log = Logger.getLogger(CacheEventListener.class);
    private static final String CHANNEL = "cache-events";

    @ConfigProperty(name = "datagrid.resp.host")
    String host;

    @ConfigProperty(name = "datagrid.resp.port", defaultValue = "443")
    int port;

    @ConfigProperty(name = "datagrid.resp.username", defaultValue = "")
    String username;

    @ConfigProperty(name = "datagrid.resp.password", defaultValue = "")
    String password;

    @ConfigProperty(name = "datagrid.resp.tls", defaultValue = "true")
    boolean tls;

    private volatile boolean running = true;
    private volatile Thread subscriberThread;
    private volatile JedisPubSub pubSub;

    void onStart(@Observes StartupEvent ev) {
        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                log.infof("[CACHE EVENT] channel=%s message=%s", channel, message);
            }

            @Override
            public void onSubscribe(String channel, int subscribedChannels) {
                log.infof("Subscribed to channel '%s'", channel);
            }
        };

        subscriberThread = new Thread(() -> {
            while (running && !Thread.currentThread().isInterrupted()) {
                try (Jedis subscriber = createSubscriberConnection()) {
                    log.infof("Subscribing to channel '%s' on %s:%d", CHANNEL, host, port);
                    subscriber.subscribe(pubSub, CHANNEL);
                } catch (Exception e) {
                    if (!running || Thread.currentThread().isInterrupted()) break;
                    log.warnf("Subscriber disconnected, reconnecting in 3s: %s", e.getMessage());
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { break; }
                }
            }
            log.info("Cache event listener stopped");
        }, "cache-event-listener");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    void onStop(@Observes ShutdownEvent ev) {
        running = false;
        if (pubSub != null && pubSub.isSubscribed()) {
            try { pubSub.unsubscribe(); } catch (Exception ignored) {}
        }
        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
    }

    private Jedis createSubscriberConnection() throws Exception {
        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                .autoNegotiateProtocol(false);

        if (username != null && !username.isEmpty() && !"none".equals(username)) {
            builder.user(username);
        }
        if (password != null && !password.isEmpty() && !"none".equals(password)) {
            builder.password(password);
        }

        if (tls) {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{trustAllManager()}, null);
            SSLParameters sslParams = new SSLParameters();
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
