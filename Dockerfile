# syntax=docker/dockerfile:1

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies separately from sources.
COPY pom.xml ./
RUN mvn -B -ntp -q dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -q -DskipTests package

# ---------- runtime ----------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN groupadd --system qa && useradd --system --gid qa --home /app qa \
    && mkdir -p /app/data && chown -R qa:qa /app

COPY --from=build /app/target/qa-kb-0.1.0.jar /app/app.jar

USER qa
EXPOSE 8787
ENV PORT=8787 \
    TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
