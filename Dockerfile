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
#
# We use Ubuntu Noble (24.04 LTS, glibc) here — NOT Alpine — because the
# PR-workflow E2E test runners (Playwright + Cypress) ship browser binaries
# that are dynamically linked against glibc and do not work on musl-based
# distros. Noble also gives us a newer toolchain (gcc 13, Python 3.12, …)
# than the previous Jammy base.
FROM eclipse-temurin:21-jre-noble

ENV DEBIAN_FRONTEND=noninteractive \
    PIP_BREAK_SYSTEM_PACKAGES=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    CYPRESS_CACHE_FOLDER=/home/appuser/.cache/Cypress \
    PLAYWRIGHT_BROWSERS_PATH=/home/appuser/.cache/ms-playwright \
    NPM_CONFIG_PREFIX=/usr/local \
    NODE_PATH=/usr/local/lib/node_modules

# ---------------------------------------------------------------------------
# Base utilities + AI-agent build toolchain
# (Java/Maven, Python, Go, C/C++, Ruby — Node.js, .NET, k6 and Rust are
# installed below from upstream channels to get current versions and avoid
# the patchy Ubuntu repos.)
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
# Node.js 22 LTS (NodeSource) — required by Playwright, Cypress and the
# scaffolded `npx` commands the PR-workflow tools issue.
# ---------------------------------------------------------------------------
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    rm -rf /var/lib/apt/lists/* && \
    node --version && npm --version

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
# k6 (Grafana APT repo) — used by the PR-workflow `k6` test framework.
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
# App user — UID/GID 1000 are conventional but some base images (Ubuntu 23.04+,
# certain Eclipse Temurin variants) ship a pre-existing `ubuntu` account at
# 1000:1000. Remove that placeholder before recreating our own `appuser` so
# the build does not fail with `groupadd: GID '1000' already exists`.
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
    mkdir -p /app /app/prompts; \
    chown -R appuser:appgroup /app /home/appuser

# ---------------------------------------------------------------------------
# Playwright + Cypress — installed GLOBALLY under /usr/local/lib/node_modules
# so that the per-PR scaffolded `playwright.config.ts` can resolve the bare
# specifier `@playwright/test` (and Cypress respectively) via NODE_PATH
# WITHOUT requiring an `npm install` inside every PR workspace.
#
# `playwright install-deps` (run as root) installs the APT packages Playwright
# needs (fonts, libnss, libasound, …). `playwright install` (run as appuser)
# then drops the browser binaries into PLAYWRIGHT_BROWSERS_PATH inside the
# user's home so the JVM (running as appuser) can find them at run time.
# ---------------------------------------------------------------------------
RUN npm install -g --omit=optional \
        @playwright/test@1.60.0 \
        playwright@1.60.0 \
        cypress@15 && \
    playwright install-deps chromium && \
    rm -rf /var/lib/apt/lists/* && \
    chown -R appuser:appgroup /home/appuser

USER appuser
RUN playwright install chromium && \
    cypress install

# ---------------------------------------------------------------------------
# Rust (optional, mirrors previous image)
# ---------------------------------------------------------------------------
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal
ENV PATH="/home/appuser/.cargo/bin:${PATH}"

USER root

# JAVA_HOME for Maven invocations done by the build-validation agents.
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Go path
ENV GOPATH=/home/appuser/go
ENV PATH="${GOPATH}/bin:/usr/lib/go/bin:${PATH}"

WORKDIR /app
COPY --from=build --chown=appuser:appgroup /app/target/*.jar app.jar

# Copy prompts directory - these serve as defaults
# They can be overridden by mounting a volume at runtime
COPY --chown=appuser:appgroup prompts/ /app/prompts/

# Verify prompts exist (fail build if missing)
RUN test -f /app/prompts/default.md && echo "Prompts verified"

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -sf http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["/usr/bin/tini", "--", "java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar", \
    "--spring.profiles.active=docker"]


