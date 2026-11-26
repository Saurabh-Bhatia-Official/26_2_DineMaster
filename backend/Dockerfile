# Multi-stage Dockerfile for DineMaster Spring Boot Backend (Backend Context)
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /workspace

COPY pom.xml .
RUN mvn dependency:go-offline -B || true

COPY src src
RUN mvn clean package -DskipTests

# Runtime image
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S dinemaster && adduser -S dinemaster -G dinemaster

COPY --from=builder /workspace/target/*.jar app.jar
RUN chown -R dinemaster:dinemaster /app

USER dinemaster

EXPOSE 8081 10000

ENV SERVER_PORT=8081
ENV SPRING_PROFILES_ACTIVE=prod

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
