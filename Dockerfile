# =============================================================================
# MailFlow Backend — Multi-stage Docker build
# Stage 1: Build with Maven (maven image includes Maven + JDK 21)
# Stage 2: Minimal JRE runtime
# =============================================================================

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# Cache dependency layer separately from source code
COPY pom.xml .
RUN mvn dependency:go-offline -B --no-transfer-progress || true

# Copy source and build — tests run in CI, skip here
COPY src ./src
RUN mvn clean package -DskipTests -B --no-transfer-progress

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Non-root user for security
RUN addgroup -S mailflow && adduser -S mailflow -G mailflow

WORKDIR /app

COPY --from=builder /app/target/mailflow-backend-*.jar app.jar

RUN mkdir -p /app/uploads && chown mailflow:mailflow /app/uploads

USER mailflow

EXPOSE 8080

ENV JAVA_OPTS="-Xss512k -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
