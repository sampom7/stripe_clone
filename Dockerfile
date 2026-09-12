# Two stages so the runtime image doesn't carry Maven or the source.
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

# Copy the wrapper and pom first. As long as dependencies don't change, this layer is
# cached and a code-only rebuild skips the download entirely.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY src/ src/
RUN ./mvnw -B clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Don't run as root.
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
