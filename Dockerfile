# syntax=docker/dockerfile:1

FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
COPY prism-db-migrations ./prism-db-migrations
COPY prism-test-support ./prism-test-support
COPY prism-shared ./prism-shared
COPY prism-protocol ./prism-protocol
COPY prism-channel ./prism-channel
COPY prism-routing ./prism-routing
COPY prism-resilience ./prism-resilience
COPY prism-subscription-oauth ./prism-subscription-oauth
COPY prism-quota ./prism-quota
COPY prism-pricing ./prism-pricing
COPY prism-request-log ./prism-request-log
COPY prism-admin ./prism-admin
COPY prism-app ./prism-app
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests -pl prism-app -am package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/prism-app/target/prism-llm-0.1.0-SNAPSHOT.jar app.jar
COPY --from=web /web/dist /app/web/dist
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
