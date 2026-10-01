FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x mvnw && ./mvnw --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd --system tracker && useradd --system --gid tracker tracker
COPY --from=build --chown=tracker:tracker /workspace/scrapper/target/scrapper.jar /app/app.jar
USER tracker
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
