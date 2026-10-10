FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Extra JDBC driver jars (e.g. Oracle, ODBC-JDBC bridges) can be mounted here;
# PropertiesLauncher appends them to the classpath via -Dloader.path.
RUN mkdir -p /app/drivers
COPY target/*.jar app.jar
EXPOSE 8096
ENTRYPOINT ["java", "-Xmx256m", "-Dloader.path=/app/drivers", "-jar", "app.jar"]
