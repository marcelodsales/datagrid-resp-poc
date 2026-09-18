package com.redhat.demo.cache;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.UnifiedJedis;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

@ApplicationScoped
public class JedisProducer {

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

    @Produces
    @ApplicationScoped
    public UnifiedJedis unifiedJedis() {
        var configBuilder = DefaultJedisClientConfig.builder();

        if (username != null && !username.isEmpty() && !"none".equals(username)) {
            configBuilder.user(username);
        }
        if (password != null && !password.isEmpty() && !"none".equals(password)) {
            configBuilder.password(password);
        }

        if (tls) {
            SSLParameters sslParams = new SSLParameters();
            sslParams.setEndpointIdentificationAlgorithm(null);
            configBuilder.ssl(true)
                    .sslSocketFactory(trustAllSslContext().getSocketFactory())
                    .sslParameters(sslParams)
                    .hostnameVerifier((hostname, session) -> true);
        }

        return RedisClient.builder()
                .hostAndPort(host, port)
                .clientConfig(configBuilder.build())
                .build();
    }

    public void close(@Disposes UnifiedJedis jedis) {
        jedis.close();
    }

    private SSLContext trustAllSslContext() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, null);
            return ctx;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
