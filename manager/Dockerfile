# Stage 1: Build Java Manager application
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

COPY manager/pom.xml .
RUN mvn dependency:go-offline 
COPY manager/src ./src

# Copy protobuf definition files required for gRPC code generation
COPY proto /proto

RUN mvn clean package -DskipTests

# Stage 2: Minimal runtime image
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=builder /app/target/manager-1.0-SNAPSHOT.jar manager.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "manager.jar"]