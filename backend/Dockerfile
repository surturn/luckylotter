# Multi-stage build — no local JDK/Maven required, everything runs in Docker.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# cache dependencies first
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/lucklotter-backend-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
