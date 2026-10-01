# syntax=docker/dockerfile:1.7
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY domain ./domain
COPY application ./application
COPY infrastructure ./infrastructure
COPY api ./api
RUN --mount=type=cache,target=/home/gradle/.gradle gradle :api:buildFatJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
WORKDIR /app
COPY --from=build /workspace/api/build/libs/voicecontrol-backend.jar /app/app.jar
# The OpenAPI page is generated into ./docs at startup, so the app user must be able to write there.
RUN mkdir -p /app/docs && chown -R app:app /app/docs
USER 10001
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
