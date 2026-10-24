# Build stage: cache dependencies, skip test compilation for the image
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY bazi-spring/pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY bazi-spring/src ./src
RUN mvn -q -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/bazi-spring-1.0.0.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
