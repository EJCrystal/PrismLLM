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
COPY prism-plugin-sdk ./prism-plugin-sdk
COPY prism-plugin-host ./prism-plugin-host
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
RUN --mount=type=cache,id=prism-maven,target=/root/.m2,sharing=locked mvn -B -DskipTests -pl prism-app -am package

# Build the independently versioned official package using only the provided SDK.
# It is not a reactor module or an application dependency.
FROM maven:3.9-eclipse-temurin-21 AS connectivity
WORKDIR /extension
COPY pom.xml /sdk/pom.xml
COPY prism-plugin-sdk /sdk/prism-plugin-sdk
COPY extensions/connectivity-diagnostic ./
RUN --mount=type=cache,id=prism-maven,target=/root/.m2,sharing=locked \
    mvn -B --no-transfer-progress -f /sdk/pom.xml -N install \
    && mvn -B --no-transfer-progress -f /sdk/prism-plugin-sdk/pom.xml -DskipTests install \
    && mvn -B --no-transfer-progress -f /extension/pom.xml -DskipTests verify

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app/plugin-state /app/plugins
COPY --from=build /app/prism-app/target/prism-llm-0.1.0-SNAPSHOT.jar app.jar
COPY --from=web /web/dist /app/web/dist
COPY --from=connectivity /extension/target/catalog.json /extension/target/prism-connectivity-1.0.0.prismplugin /app/bundled-plugins/
ENV PLUGIN_CATALOG_DIR=/app/bundled-plugins \
    PLUGIN_INSTALL_DIR=/app/plugin-state
VOLUME ["/app/plugin-state"]
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
