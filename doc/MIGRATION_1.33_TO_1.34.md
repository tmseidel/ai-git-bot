# Migration Guide: AI-Git-Bot 1.33 → 1.34

> **Target audience:** operators upgrading an existing 1.33.x deployment to the 1.34.0
> release, and contributors building AI-Git-Bot from source.

## Java 25 is required

From 1.34 on, AI-Git-Bot is compiled for **Java 25** (previously Java 21).

| Who | What to do |
|---|---|
| Docker image users | Nothing. The image now runs on `eclipse-temurin:25-jre-noble` and still uses Ubuntu 24.04 (Noble), so the OS packages are unchanged. |
| Images built `FROM tmseidel/ai-git-bot` | Rebuild. The `java` in your image is now Java 25. |
| Running the JAR directly (`java -jar`) | Upgrade the JVM to 25 first. Older JVMs fail at startup with `UnsupportedClassVersionError` (class file version 69). |
| Building from source | Use JDK 25 and Maven 3.9+. The Maven enforcer rule rejects older JDKs. See [Local Development](LOCAL_DEVELOPMENT.md#prerequisites). |
