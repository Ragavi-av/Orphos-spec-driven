FROM eclipse-temurin:21-jre
# curl is required by this container's healthcheck (docker-compose.app.yml
# uses "curl -fsS http://localhost:8094/actuator/health") — not present on
# the -alpine variant, so this uses the Debian-based image + apt instead
# (same pattern as mail-insight-service's Dockerfile).
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8094
ENTRYPOINT ["java", "-Xmx340m", "-jar", "app.jar"]
