FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S assistant && adduser -S assistant -G assistant
COPY app.jar app.jar
COPY capabilities capabilities
COPY prompts prompts
USER assistant
EXPOSE 8080
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "app.jar"]
