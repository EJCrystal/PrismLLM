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
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
ENV HTTP_PROXY=http://host.docker.internal:7897 \
    HTTPS_PROXY=http://host.docker.internal:7897 \
    NO_PROXY="localhost,127.0.0.1,::1,postgres,host.docker.internal,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16"
ENV JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=host.docker.internal -Dhttp.proxyPort=7897 -Dhttps.proxyHost=host.docker.internal -Dhttps.proxyPort=7897 -Dhttp.nonProxyHosts=localhost|127.*|[::1]|postgres|host.docker.internal|10.*|172.16.*|172.17.*|172.18.*|172.19.*|172.20.*|172.21.*|172.22.*|172.23.*|172.24.*|172.25.*|172.26.*|172.27.*|172.28.*|172.29.*|172.30.*|172.31.*|192.168.*"
WORKDIR /app
COPY --from=build /app/target/llm-gateway-0.1.0-SNAPSHOT.jar app.jar
COPY --from=web /web/dist /app/web/dist
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
