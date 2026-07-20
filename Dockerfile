FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app

# Cache Maven dependencies in a separate layer
COPY pom.xml .
RUN apk add --no-cache maven && \
    mvn dependency:go-offline -B

# Build the application (source changes don't bust the dependency cache)
COPY src ./src
RUN mvn clean package -DskipTests -o

# --- Runtime image ---
# Playwright Noble (Ubuntu 24.04 LTS) provides pre-configured Chromium/Node
# dependencies and a modern glibc baseline (2.39) that ensures backward
# compatibility when copying binaries from older base images (e.g. Bookworm).
FROM mcr.microsoft.com/playwright:v1.60.0-noble

ENV DEBIAN_FRONTEND=noninteractive \
    PIP_BREAK_SYSTEM_PACKAGES=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    CYPRESS_CACHE_FOLDER=/opt/cypress-cache \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    NPM_CONFIG_PREFIX=/usr/local \
    NODE_PATH=/usr/local/lib/node_modules

# ---------------------------------------------------------------------------
# App user
# Claim UID/GID 1000 early before apt packages are installed.
#
# TODO(security): Consider failing the build if UID/GID 1000 is already taken
# instead of deleting the existing user, to avoid breaking base image assumptions.
# ---------------------------------------------------------------------------
RUN set -eux; \
    if getent passwd 1000 >/dev/null; then \
        existing_user="$(getent passwd 1000 | cut -d: -f1)"; \
        userdel -r "$existing_user" 2>/dev/null || userdel "$existing_user"; \
    fi; \
    if getent group 1000 >/dev/null; then \
        existing_group="$(getent group 1000 | cut -d: -f1)"; \
        groupdel "$existing_group" || true; \
    fi; \
    groupadd -g 1000 appgroup; \
    useradd -m -u 1000 -g appgroup -s /bin/bash appuser; \
    mkdir -p /app /app/prompts /opt/cypress-cache; \
    chown -R appuser:appgroup /app /home/appuser /opt/cypress-cache

# ---------------------------------------------------------------------------
# Base utilities + AI-agent build toolchain
# ---------------------------------------------------------------------------
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl wget git bash gnupg lsb-release apt-transport-https \
        unzip xz-utils tini \
        universal-ctags \
        maven \
        python3 python3-pip python3-venv \
        golang-go \
        gcc g++ make cmake \
        ruby ruby-bundler \
    && rm -rf /var/lib/apt/lists/*

# ---------------------------------------------------------------------------
# Eclipse Temurin 21 JDK (Adoptium Official Repo)
# ---------------------------------------------------------------------------
RUN wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor | tee /etc/apt/trusted.gpg.d/adoptium.gpg > /dev/null && \
    echo "deb https://packages.adoptium.net/artifactory/deb noble main" | tee /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends temurin-21-jdk && \
    rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# ---------------------------------------------------------------------------
# .NET 10 SDK (Microsoft package feed)
# ---------------------------------------------------------------------------
RUN wget -q https://packages.microsoft.com/config/ubuntu/24.04/packages-microsoft-prod.deb \
        -O /tmp/ms.deb && \
    dpkg -i /tmp/ms.deb && rm /tmp/ms.deb && \
    apt-get update && apt-get install -y --no-install-recommends dotnet-sdk-10.0 && \
    rm -rf /var/lib/apt/lists/* && \
    dotnet --info

# ---------------------------------------------------------------------------
# k6 (Grafana APT repo)
# ---------------------------------------------------------------------------
RUN gpg -k && \
    gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
        --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69 && \
    echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
        > /etc/apt/sources.list.d/k6.list && \
    apt-get update && apt-get install -y --no-install-recommends k6 && \
    rm -rf /var/lib/apt/lists/* && \
    k6 version

# ---------------------------------------------------------------------------
# Python test runner used by the PR-workflow `pytest` framework.
# ---------------------------------------------------------------------------
RUN pip3 install --no-cache-dir pytest requests && \
    pytest --version

# ---------------------------------------------------------------------------
# Playwright + Cypress — installed GLOBALLY under /usr/local/lib/node_modules
# The Playwright base image already has browsers at /ms-playwright, so we
# don't need to run install-deps or playwright install.
# ---------------------------------------------------------------------------
ENV CI=1
RUN npm install -g --omit=optional \
        @playwright/test@1.60.0 \
        playwright@1.60.0 \
        cypress@15 && \
    cypress install && \
    chown -R appuser:appgroup /opt/cypress-cache

# ---------------------------------------------------------------------------
# Rust
# Copied from the official image to avoid `curl | sh` supply chain risks.
# The 'bookworm' tag is used as its glibc baseline (2.36) safely predates
# Noble's (2.39), guaranteeing backward compatibility.
# ---------------------------------------------------------------------------
ENV RUSTUP_HOME=/opt/rustup \
    CARGO_HOME=/opt/cargo \
    PATH="/opt/cargo/bin:${PATH}"
COPY --from=rust:1-slim-bookworm --chown=appuser:appgroup /usr/local/cargo /opt/cargo
COPY --from=rust:1-slim-bookworm --chown=appuser:appgroup /usr/local/rustup /opt/rustup

USER root

# Go path
ENV GOPATH=/home/appuser/go
ENV PATH="${GOPATH}/bin:/usr/lib/go/bin:${PATH}"

WORKDIR /app
COPY --from=build --chown=appuser:appgroup /app/target/*.jar app.jar
COPY --chown=appuser:appgroup prompts/ /app/prompts/

RUN test -f /app/prompts/default.md && echo "Prompts verified"

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -sf http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["/usr/bin/tini", "--", "java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar", \
    "--spring.profiles.active=docker"]
