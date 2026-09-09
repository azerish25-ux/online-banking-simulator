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
# HTTP probe over bash's /dev/tcp; no curl/wget needed in the JRE image.
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /api/health HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q '200' <&3"]
ENTRYPOINT ["java", "-jar", "app.jar"]
