FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml ./
COPY src ./src

RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

COPY --from=build /app/target/truststore-tui-1.0.0-jar-with-dependencies.jar /app/truststore-tui.jar

ENTRYPOINT ["java", "-jar", "/app/truststore-tui.jar"]
