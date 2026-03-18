FROM maven:3.9.9-eclipse-temurin-17 AS builder

WORKDIR /build
COPY java /build
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /build/target/meshchat-java-1.0.0.jar /app/app.jar
CMD ["java", "-jar", "/app/app.jar"]
