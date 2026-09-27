# ==============================================================================
# STAGE 1: Dependency Caching Stage
# Leverages Docker layer caching: this stage is only re-run when pom.xml changes.
# ==============================================================================
FROM eclipse-temurin:21-jdk-alpine AS dependencies
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# ==============================================================================
# STAGE 2: Source Compilation & Application Packaging
# Source changes will only invalidate this quick layer; dependencies remain cached!
# ==============================================================================
FROM dependencies AS builder
WORKDIR /app
COPY src ./src
RUN ./mvnw clean package -DskipTests

# ==============================================================================
# STAGE 3: Hardened, Minimal Production Runtime
# - Alpine JRE with minimal attack surface
# - Non-root execution user (appuser:10001)
# - Container-aware JVM memory configuration using percentage-based sizing
# - Graceful shutdown support
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine AS runner
WORKDIR /app

# Create non-root user & group
RUN addgroup -g 10001 -S appgroup && \
    adduser -u 10001 -S appuser -G appgroup

USER 10001:10001

# Copy executable jar from builder stage
COPY --from=builder --chown=10001:10001 /app/target/*.jar app.jar

EXPOSE 8080

# Production JVM Flags:
# 1. -XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=75.0 (Reserves 25% for Metaspace/Thread Stacks/Off-heap)
# 2. -XX:+UseG1GC (Predictable low-pause garbage collector)
# 3. -Djava.security.egd=file:/dev/./urandom (Fast entropy generation)
ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["java", "-jar", "app.jar"]
