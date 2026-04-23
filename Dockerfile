FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy

RUN groupadd -r mcpuser && useradd -r -g mcpuser -m mcpuser
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app

COPY --from=build /app/target/3gpp-mcp-server-2.0.0.jar /app/app.jar

RUN mkdir -p /home/mcpuser/.3gpp-kb && chown -R mcpuser:mcpuser /home/mcpuser /app
USER mcpuser

EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=10s --start-period=300s --retries=3 \
  CMD ["/bin/sh", "-c", "curl -fsS http://localhost:3000/ready >/dev/null || exit 1"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
