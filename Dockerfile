# Build the shaded eval-runner jar, then run it on a slim JRE.
# The image's entrypoint IS the runner, so container args are the CLI args:
#   docker run ghcr.io/hhagenbuch/agent-evals:0.1.0 /data/dataset.yaml --target http://... --min-pass-rate 0.9
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
# Matches the shaded jar only (the unshaded one is prefixed "original-").
COPY --from=build /src/target/agent-evals-*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
