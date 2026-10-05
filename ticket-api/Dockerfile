# Build the jar first:  mvn -q -DskipTests package   (scripts/up.sh does this)
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home app
COPY target/ticket-service.jar /app/ticket-service.jar
USER 10001
EXPOSE 8443
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/ticket-service.jar"]
