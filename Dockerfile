# ---- Build stage ----
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Cache dependency resolution separately from source changes.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --version

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# AudioNormalizer shells out to ffmpeg for audio/webm uploads (see AppProperties.ffmpegPath).
RUN apk add --no-cache ffmpeg \
    && addgroup -S qstory && adduser -S qstory -G qstory

COPY --from=build /app/build/libs/*.jar app.jar
USER qstory

EXPOSE 8080
ENV PORT=8080
# Empty by default; set at `docker run`/Railway for env-specific memory tuning, e.g.
# JAVA_OPTS="-Xmx512m -Xss512k" - no rebuild needed. SPRING_PROFILES_ACTIVE (dev/staging/prod,
# see application-*.yml) is a plain env var Spring reads itself, no JAVA_OPTS entry needed for it.
ENV JAVA_OPTS=""
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -q -O- "http://localhost:${PORT}/health" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
