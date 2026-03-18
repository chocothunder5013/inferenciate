# Stage 1: Build the Java App (Heavy)
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# Pull from the manager/ prefix now
COPY manager/pom.xml .
RUN mvn dependency:go-offline 
COPY manager/src ./src

# COPY THE PROTO FOLDER! 
# Placed at /proto, so Maven looking for /app/../proto finds it instantly.
COPY proto /proto

RUN mvn clean package -DskipTests

# Stage 2: Run the Java App (Lightweight)
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=builder /app/target/manager-1.0-SNAPSHOT.jar manager.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "manager.jar"]