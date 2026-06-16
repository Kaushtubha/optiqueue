# ═══════════════════════════════════════════════════════════════════════
# Dockerfile — multi-stage build for OptiQueue
#
# MULTI-STAGE BUILD: two FROM statements, two stages.
#   Stage 1 (builder): has JDK + Maven, builds the fat-jar.
#   Stage 2 (runtime): has only JRE, copies the jar from stage 1.
#
# WHY multi-stage?
#   The final image does NOT contain Maven, source code, or the full JDK.
#   Builder image: ~800MB. Runtime image: ~250MB. Smaller = faster deploys,
#   smaller attack surface (no compiler in prod container = less to exploit).
#
# INTERVIEW Q: "What's the difference between JDK and JRE in Docker?"
#   JDK (eclipse-temurin:21-jdk) = JRE + compiler + tools. Needed to BUILD.
#   JRE (eclipse-temurin:21-jre) = runtime only. Needed to RUN.
#   Never ship a JDK in a production Docker image.
# ═══════════════════════════════════════════════════════════════════════

# ── STAGE 1: BUILD ─────────────────────────────────────────────────────
# maven:3.9.6-eclipse-temurin-21 includes BOTH Maven AND JDK.
# eclipse-temurin:21-jdk-alpine has only JDK — mvn command not found.
# WHY separate Maven image? Maven is only needed at build time,
# never in the final runtime image (Stage 2 uses JRE-only).
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /build

# Copy POM first, then download dependencies.
# WHY separate COPY? Docker layer caching. If only source changes (not pom.xml),
# the `mvn dependency:go-offline` layer is cached and not re-downloaded.
# This saves ~2-3 minutes on every rebuild.
COPY pom.xml .
RUN mvn dependency:go-offline -B --no-transfer-progress 2>/dev/null || true

# Now copy source and build (this layer re-runs only when source changes)
COPY src ./src

# Package: skip tests here (tests run in CI pipeline separately)
# -B = batch mode (no color, no interactive prompts — clean for CI logs)
# --no-transfer-progress = suppress download progress bars in CI logs
RUN mvn package -B --no-transfer-progress -DskipTests

# ── STAGE 2: RUNTIME ───────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: run as non-root user.
# Running as root inside a container is a CWE-250 violation. If the process
# escapes the container (container breakout), it would have root on the host.
# Creating a dedicated user limits blast radius.
RUN addgroup -S optiqueue && adduser -S optiqueue -G optiqueue

WORKDIR /app

# Copy ONLY the fat-jar from the builder stage
COPY --from=builder /build/target/optiqueue-*.jar app.jar

# Give ownership to our non-root user
RUN chown optiqueue:optiqueue app.jar

USER optiqueue

# Document which port the app listens on (informational — doesn't actually expose it)
EXPOSE 8080

# JVM flags explained:
#   -XX:+UseContainerSupport: JVM respects Docker CPU/memory limits (default in Java 11+, explicit for clarity)
#   -XX:MaxRAMPercentage=75.0: Use max 75% of container RAM for heap. Leaves 25% for OS, off-heap, Metaspace.
#   -Djava.security.egd=file:/dev/./urandom: Faster SecureRandom on Linux (avoids /dev/random blocking).
#     The /dev/./urandom path (with the dot) is a workaround for an old JVM bug — still a common practice.
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
