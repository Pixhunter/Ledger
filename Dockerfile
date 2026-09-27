FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY api ./api
COPY src ./src

RUN chmod +x gradlew && ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /workspace/build/install/Ledger/ ./
COPY api ./api
COPY config ./config
COPY db ./db

EXPOSE 8081

ENTRYPOINT ["./bin/Ledger"]
