FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/platform-registry-service-*.jar app.jar
RUN mkdir -p /app/data
VOLUME /app/data
EXPOSE 8093
ENTRYPOINT ["java", "-jar", "app.jar"]
