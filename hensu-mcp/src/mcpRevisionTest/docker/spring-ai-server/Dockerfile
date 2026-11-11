# Builds and runs the Spring AI MCP server the revision suite dials.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
COPY --from=build /build/target/server.jar /server.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/server.jar"]
