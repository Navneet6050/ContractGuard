# Contract Guard application image (DECISIONS.md B-09).
# Build:  docker build -t contract-guard .
# Run:    docker run -p 8080:8080 contract-guard
# The untouched starter-kit receiver has its own image (webhook-receiver/Dockerfile);
# docker-compose.app.yml wires both together with one command.

# ---------- build stage: Maven + JDK 21 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependency layer first so source edits do not re-download the repository
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B package -DskipTests

# ---------- runtime stage: JRE only ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN mkdir -p /app/data /kit/specs /kit/consumers
COPY --from=build /build/target/contractguard-0.1.0.jar app.jar
# Kit fixtures are baked in so the image is self-contained; compose mounts the
# repository copies read-only instead (the mounts win when both are present).
COPY specs /kit/specs
COPY consumers /kit/consumers

# H2 file DB lives under /app/data — mount a volume there for persistence across
# container restarts (docker-compose.app.yml does this).
ENV PORT_APP=8080 \
    SPECS_DIR=/kit/specs \
    CONSUMERS_DIR=/kit/consumers \
    KIT_IMPORT=true \
    DB_URL=jdbc:h2:file:/app/data/contractguard
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
