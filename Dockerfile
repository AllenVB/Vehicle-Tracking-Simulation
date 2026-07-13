# Multi-stage build shared by every service. The MODULE build arg selects which
# module's jar becomes the runtime image.
#   docker build --build-arg MODULE=vts-ingestion-service .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Copy the POMs first so dependency resolution is cached across code changes.
COPY pom.xml .
COPY vts-common/pom.xml vts-common/
COPY vts-simulator/pom.xml vts-simulator/
COPY vts-ingestion-service/pom.xml vts-ingestion-service/
COPY vts-processing-service/pom.xml vts-processing-service/
COPY vts-stream-analytics/pom.xml vts-stream-analytics/
COPY vts-notification-service/pom.xml vts-notification-service/
COPY vts-api-gateway/pom.xml vts-api-gateway/
COPY vts-scheduler-service/pom.xml vts-scheduler-service/
RUN mvn -q -B dependency:go-offline || true
COPY . .
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre AS runtime
ARG MODULE
WORKDIR /app
COPY --from=build /app/${MODULE}/target/${MODULE}-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
