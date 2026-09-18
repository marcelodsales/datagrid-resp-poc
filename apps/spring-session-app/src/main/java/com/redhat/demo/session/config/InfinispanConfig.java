package com.redhat.demo.session.config;

import org.infinispan.commons.marshall.ProtoStreamMarshaller;
import org.infinispan.spring.starter.remote.InfinispanRemoteCacheCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.security.cert.X509Certificate;

@Configuration
public class InfinispanConfig {

    @Value("${app.dg.ssl:false}")
    private boolean ssl;

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public InfinispanRemoteCacheCustomizer cacheCustomizer() {
        return b -> {
            URI cacheConfigUri;
            try {
                cacheConfigUri = new ClassPathResource("sessions-cache-config.xml").getURI();
            } catch (IOException e) {
                throw new IllegalStateException("Cannot load sessions-cache-config.xml", e);
            }

            b.remoteCache("sessions")
             .configurationURI(cacheConfigUri)
             .marshaller(ProtoStreamMarshaller.class);

            if (ssl) {
                try {
                    SSLContext sslContext = SSLContext.getInstance("TLS");
                    sslContext.init(null, new TrustManager[]{trustAllManager()}, null);
                    b.security().ssl().enable()
                     .sslContext(sslContext)
                     .hostnameValidation(false);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to configure SSL", e);
                }
            }
        };
    }

    private static X509TrustManager trustAllManager() {
        return new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] certs, String type) {}
            public void checkServerTrusted(X509Certificate[] certs, String type) {}
        };
    }
}
