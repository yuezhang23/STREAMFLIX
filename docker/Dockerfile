# Generic multi-stage build for every Spring Boot service.
# Selected per service via the MODULE build arg; build context is the repo root so the
# parent POM and the shared `common` module are available to the reactor build.

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Copy all POMs first so dependency resolution layers cache across source changes.
COPY pom.xml .
COPY common/pom.xml common/
COPY api-gateway/pom.xml api-gateway/
COPY user-service/pom.xml user-service/
COPY video-service/pom.xml video-service/
COPY recommendation-service/pom.xml recommendation-service/
COPY analytics-service/pom.xml analytics-service/

ARG MODULE
# Warm the local repo (best-effort; `common` isn't built yet so this may partially fail).
RUN mvn -q -B -pl ${MODULE} -am dependency:go-offline -DskipTests || true

# Now bring in sources and build just the target module + its dependencies.
COPY common/src common/src
COPY ${MODULE}/src ${MODULE}/src
RUN mvn -q -B -pl ${MODULE} -am -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
ARG MODULE
COPY --from=build /build/${MODULE}/target/*.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
