package com.redhat.demo.redis.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.UnifiedJedis;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

@Configuration
public class RedisConfig {

    @Value("${app.redis.host}")
    private String host;

    @Value("${app.redis.port}")
    private int port;

    @Value("${app.redis.username}")
    private String username;

    @Value("${app.redis.password}")
    private String password;

    @Value("${app.redis.ssl}")
    private boolean ssl;

    @Value("${app.cache.cluster}")
    private String cluster;

    @Value("${app.cache.name}")
    private String cacheName;

    @Value("${app.cache.mode}")
    private String cacheMode;

    @Bean
    public UnifiedJedis unifiedJedis() throws Exception {
        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                .user(username)
                .password(password);

        if (ssl) {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAllManager()}, null);
            SSLParameters sslParams = new SSLParameters();
            sslParams.setEndpointIdentificationAlgorithm(null);
            builder.ssl(true)
                   .sslSocketFactory(sslContext.getSocketFactory())
                   .sslParameters(sslParams)
                   .hostnameVerifier((hostname, session) -> true);
        }

        return RedisClient.builder()
                .hostAndPort(host, port)
                .clientConfig(builder.build())
                .build();
    }

    private static X509TrustManager trustAllManager() {
        return new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] certs, String type) {}
            public void checkServerTrusted(X509Certificate[] certs, String type) {}
        };
    }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getUsername() { return username; }
    public String getCluster() { return cluster; }
    public String getCacheName() { return cacheName; }
    public String getCacheMode() { return cacheMode; }
}
