FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV PORT=8080
COPY --from=build /workspace/target/render-site-1.0.0.jar /app/app.jar
EXPOSE 8080
CMD ["java", "-jar", "/app/app.jar"]
