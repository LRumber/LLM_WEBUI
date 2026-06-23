FROM eclipse-temurin:21-jre-jammy

RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app app

WORKDIR /app
COPY target/lm-platform-backend-*.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
