# Build Stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Copy pom.xml and cache dependencies
COPY pom.xml .
RUN mvn dependency:go-offline

# Copy source and build the Fat JAR
COPY src ./src
RUN mvn package -DskipTests

# Runtime Stage
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Copy the generated JAR (Shade plugin usually names it with the original name)
# Using a wildcard is okay, but ensure only one jar is in target/ 
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080 9999
CMD ["java", "-jar", "app.jar"]