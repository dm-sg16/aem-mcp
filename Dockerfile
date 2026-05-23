# syntax=docker/dockerfile:1

# Base images are pinned to specific patch tags so rebuilds are reproducible and CVE exposure is
# explicit. Let Dependabot or Renovate manage the bumps; do not loosen to a floating tag.
# ---- Build stage ----
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:17.0.13_11-jre-jammy
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home /app app
COPY --from=build /build/target/aem-readonly-mcp-1.0.0.jar /app/app.jar
USER app
EXPOSE 8080
# Force the JVM to use the writable /tmp volume mounted by the k8s manifest, since the root
# filesystem is read-only in production (see k8s/deployment.yaml).
ENV JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/tmp"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
