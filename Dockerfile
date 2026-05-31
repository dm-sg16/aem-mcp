# syntax=docker/dockerfile:1

# Base images are Red Hat UBI 9 (Universal Base Image) — RHEL-derived, freely redistributable
# without a Red Hat subscription, and reachable on registry.access.redhat.com from most
# corporate networks where Docker Hub is blocked. Pinned to a specific tag for reproducibility;
# let Dependabot or Renovate manage the bumps. (If your internal mirror only carries a
# different tag, change here and in docs/plans/plan.md Task 11.)
#
# ---- Build stage ----
# ubi9/openjdk-17 ships JDK 17 + Maven, so no separate `maven:*` image is needed.
FROM registry.access.redhat.com/ubi9/openjdk-17:1.20 AS build
# UBI runs as non-root by default; switch to root for the build so /build is writable. The
# runtime stage gets its non-root user back from the runtime base image, so this only affects
# the discarded build layer.
USER 0
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Runtime stage ----
# ubi9/openjdk-17-runtime is a slim JRE-only variant. It already runs as non-root uid 185 with
# gid 0 (OpenShift convention — non-zero uid, root group so files are writable by any process
# in the root group). No USER / groupadd / useradd needed.
FROM registry.access.redhat.com/ubi9/openjdk-17-runtime:1.20
COPY --from=build --chown=185:0 /build/target/aem-readonly-mcp-1.0.0.jar /deployments/app.jar
EXPOSE 8080
# Force the JVM to use the writable tmpfs mounted at /tmp by Compose (see compose.yaml),
# since the root filesystem is read-only in production.
ENV JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/tmp"
ENTRYPOINT ["java", "-jar", "/deployments/app.jar"]
