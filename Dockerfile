# ── Build stage ────────────────────────────────────────────────────────────────
# Debian-based builder: Vaadin production build downloads Node.js binaries which are
# not available for Alpine (musl) ARM64. The runtime stage stays Alpine (small image).
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# Cache dependencies separately from source code
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f pom.xml dependency:go-offline -q 2>/dev/null || true

COPY src ./src
# -Pproduction pre-bundles the Vaadin frontend (Node.js downloaded automatically by the plugin).
# Cache ~/.vaadin so Node.js is not re-downloaded on every build.
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/root/.vaadin \
    mvn -f pom.xml package -DskipTests -Pproduction -q

# ── Runtime stage ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Volumes: data (SQLite), obsidian vault, plain notes, config override
VOLUME ["/data", "/obsidian", "/notes", "/config"]

COPY --from=builder /build/target/coach-bot.jar app.jar

# Additive override: /config/application.yml is merged ON TOP of built-in defaults.
# If the volume is not mounted, Spring silently ignores it (optional:).
# Values in /config/ win over classpath, so you only need to specify what you change.
ENV SPRING_CONFIG_ADDITIONAL_LOCATION="optional:file:/config/"
ENV DATA_DIR="/data"
ENV NOTES_DIR="/notes"

# Non-root user
RUN addgroup -S coachbot && adduser -S coachbot -G coachbot
USER coachbot

ENTRYPOINT ["java", "-jar", "app.jar"]
