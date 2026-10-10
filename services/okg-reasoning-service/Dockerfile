FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8088
ENTRYPOINT ["java", "-Xmx400m", "--add-opens", "java.base/java.lang=ALL-UNNAMED", "-jar", "app.jar"]
