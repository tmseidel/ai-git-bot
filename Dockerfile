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
FROM eclipse-temurin:21-jre-alpine

# Install build tools for AI agent validation
# This allows the agent to compile and validate code in various languages
# Includes base utilities, Java/Maven/Gradle, Node.js/npm, Python, Go,
# C/C++, Ruby, and the .NET SDK for restore/build/test/format validation.
RUN apk add --no-cache \
    ca-certificates curl git bash \
    maven openjdk21-jdk \
    nodejs npm \
    python3 py3-pip \
    go \
    gcc g++ make cmake \
    ruby ruby-bundler \
    dotnet10-sdk && \
    addgroup -g 1000 appgroup && adduser -u 1000 -G appgroup -D appuser && \
    mkdir -p /app /app/prompts && chown -R appuser:appgroup /app

# Install Rust for appuser (optional, can be removed if not needed)
USER appuser
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal
ENV PATH="/home/appuser/.cargo/bin:${PATH}"

USER root

# Set JAVA_HOME for Maven
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Set Go path
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

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar", \
    "--spring.profiles.active=docker"]
