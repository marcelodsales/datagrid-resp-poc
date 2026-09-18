FROM docker.io/redis:7 AS redis-bin

FROM registry.access.redhat.com/ubi9/openjdk-25:latest AS builder
USER 0
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn package -DskipTests -q

FROM registry.access.redhat.com/ubi9/openjdk-25-runtime:latest
USER 0
COPY --from=redis-bin /usr/local/bin/redis-cli /usr/local/bin/redis-cli
USER 185
COPY --from=builder /build/target/*.jar /deployments/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/deployments/app.jar"]
