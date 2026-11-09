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
COPY prism-shared ./prism-shared
COPY prism-protocol ./prism-protocol
COPY prism-channel ./prism-channel
COPY prism-routing ./prism-routing
COPY prism-resilience ./prism-resilience
COPY prism-subscription-oauth ./prism-subscription-oauth
COPY prism-quota ./prism-quota
COPY prism-pricing ./prism-pricing
COPY prism-app ./prism-app
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests -pl prism-app -am package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# 智商检测的视觉判分器需要无头浏览器才能逐帧推进 CSS @keyframes。
# 固定 Ubuntu 22.04 以匹配 Chrome 的 deb 依赖；x86_64 镜像预装 Chrome，
# 其他架构或下载失败时镜像不含浏览器，视觉判分会返回“需人工查看”。
# util-linux 提供 setpriv：容器以 root 运行时用它把浏览器降到 nobody。
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl fonts-noto-cjk util-linux \
    && if [ "$(uname -m)" = "x86_64" ]; then \
         if curl -fL --retry 3 --retry-delay 2 --max-time 300 \
              https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb \
              -o /tmp/chrome.deb; then \
           apt-get install -y --no-install-recommends /tmp/chrome.deb \
             && rm -f /tmp/chrome.deb; \
         else \
           echo "Chrome 下载失败：镜像不含无头浏览器，IQ vision 将返回需人工查看"; \
         fi; \
       else \
         echo "跳过 Chrome 安装：视觉判分将返回需人工查看"; \
       fi \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/prism-app/target/prism-llm-0.1.0-SNAPSHOT.jar app.jar
COPY --from=web /web/dist /app/web/dist
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
