FROM eclipse-temurin:21-jdk-alpine@sha256:6ea5548706b60ac0a602eaf48af74792cbab012d90e811ca8db6184b16b5c3d6 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
RUN ./mvnw --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre-alpine@sha256:974b08960c5d96694c780e65b2d5705268ab1e1ca1a0dd0caf4ba6c3fe34d699
RUN addgroup -S beacon && adduser -S -G beacon beacon
RUN mkdir -p /var/lib/commonbeacon/transfers && chown beacon:beacon /var/lib/commonbeacon/transfers && chmod 700 /var/lib/commonbeacon/transfers
WORKDIR /app
COPY --from=build --chown=beacon:beacon /workspace/target/commonbeacon-0.0.1-SNAPSHOT.jar app.jar
USER beacon
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
