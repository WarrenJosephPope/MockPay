# Multi-stage build: compile with a full JDK, ship on a JRE.
#
# The result is roughly a third the size of a single-stage image, and the final
# layer contains no compiler, no Maven, and no source code — all of which are
# liabilities in something that handles payments.

# --------------------------------------------------------------------------
# Stage 1 — build
# --------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /build

# Copy the wrapper and the POM first, on their own layer. Dependencies change
# far less often than source, so Docker can reuse the cached download layer on
# every subsequent build where only code changed. Copying everything at once
# would re-download the internet on every edit.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -DskipTests package

# --------------------------------------------------------------------------
# Stage 2 — runtime
# --------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

# Run as a non-root user. If the process is ever compromised, the attacker lands
# as `mockpay` with no write access to anything that matters.
RUN addgroup -S mockpay && adduser -S mockpay -G mockpay
USER mockpay

COPY --from=build --chown=mockpay:mockpay /build/target/*.jar app.jar

# Baked at build time so EXPOSE and the image's own healthcheck know the port,
# but still overridable at run time — the ENV below defaults to it, and Compose
# passes GATEWAY_PORT through as an environment variable.
ARG GATEWAY_PORT=8088
ENV GATEWAY_PORT=${GATEWAY_PORT}
EXPOSE ${GATEWAY_PORT}

# Container memory is not the same as JVM heap. Without this the JVM sizes its
# heap from the host's total RAM and gets OOM-killed by the container limit.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

# Compose has its own healthcheck for the app service; this makes the image
# self-describing if it is run outside Compose. $GATEWAY_PORT resolves at run
# time because CMD in shell form goes through /bin/sh.
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:${GATEWAY_PORT}/v1/public/test_instruments >/dev/null 2>&1 || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
