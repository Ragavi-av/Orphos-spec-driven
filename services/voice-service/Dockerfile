FROM eclipse-temurin:21-jre
# curl is used by the container healthcheck in docker-compose.app.yml.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8099
ENTRYPOINT ["java","-jar","/app/app.jar"]
