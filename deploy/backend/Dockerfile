# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace
COPY . .

RUN chmod +x gradlew \
    && ./gradlew --no-daemon --console=plain :backend:ktor:installDist

FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 oposicontrol \
    && useradd --system --uid 10001 --gid oposicontrol \
        --home-dir /opt/oposicontrol --shell /usr/sbin/nologin oposicontrol

WORKDIR /opt/oposicontrol
COPY --from=builder --chown=oposicontrol:oposicontrol \
    /workspace/backend/ktor/build/install/ktor/ ./

USER 10001:10001

ENV HOST=0.0.0.0 \
    PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=4 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/health/live >/dev/null || exit 1

ENTRYPOINT ["/opt/oposicontrol/bin/ktor"]
