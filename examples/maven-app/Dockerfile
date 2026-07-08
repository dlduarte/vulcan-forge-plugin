FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/maven-app.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
