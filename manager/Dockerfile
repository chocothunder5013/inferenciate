# Build Stage
# We use a Maven image based on Eclipse Temurin
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
# Cache dependencies
RUN mvn dependency:go-offline

COPY src ./src
RUN mvn package -DskipTests

# Runtime Stage
# We use the Eclipse Temurin JRE (lighter than JDK)
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080 9999
CMD ["java", "-jar", "app.jar"]
