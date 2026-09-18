FROM registry.access.redhat.com/ubi9/openjdk-21:latest AS builder
USER 0
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn package -DskipTests -q

FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:latest
COPY --from=builder /build/target/*.jar /deployments/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/deployments/app.jar"]
