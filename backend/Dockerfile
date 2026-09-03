# ---- build ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

# ---- run ----
FROM eclipse-temurin:17-jre
RUN useradd -m appuser
USER appuser
WORKDIR /app
COPY --from=build /app/target/platform-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
