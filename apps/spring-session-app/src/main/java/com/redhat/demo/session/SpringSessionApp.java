package com.redhat.demo.session;

import org.infinispan.spring.remote.session.configuration.EnableInfinispanRemoteHttpSession;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableInfinispanRemoteHttpSession
public class SpringSessionApp {

    public static void main(String[] args) {
        SpringApplication.run(SpringSessionApp.class, args);
    }
}
