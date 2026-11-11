FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY backend backend
RUN mvn -s /workspace/.mvn/settings.xml -f backend/pom.xml -B -ntp -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system assistant && useradd --system --gid assistant assistant
COPY --from=build /workspace/backend/assistant-boot/target/assistant-boot-0.1.0-SNAPSHOT.jar app.jar
COPY capabilities capabilities
COPY prompts prompts
USER assistant
EXPOSE 8080
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "app.jar"]
