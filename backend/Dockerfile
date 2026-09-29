FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl tesseract-ocr tesseract-ocr-eng && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/target/meter-monitor-*.jar app.jar
RUN mkdir -p /var/lib/meter-monitor/uploads
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
