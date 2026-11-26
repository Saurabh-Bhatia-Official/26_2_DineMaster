# Multi-stage Dockerfile for DineMaster Spring Boot Backend (Root Context)
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /workspace

# Copy root and backend pom files for dependency layer caching
COPY pom.xml .
COPY backend/pom.xml backend/pom.xml

# Cache dependencies
RUN mvn dependency:go-offline -B -pl backend -am || true

# Copy backend source
COPY backend/src backend/src

# Build package without running tests
RUN mvn clean package -DskipTests -pl backend -am

# Runtime image
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S dinemaster && adduser -S dinemaster -G dinemaster

COPY --from=builder /workspace/backend/target/*.jar app.jar
RUN chown -R dinemaster:dinemaster /app

USER dinemaster

EXPOSE 8081 10000

ENV SERVER_PORT=8081
ENV SPRING_PROFILES_ACTIVE=prod

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
