# Build and run the Spring Boot app on Java 21 (build.gradle.kts targets Java 21 bytecode).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --version
COPY src ./src
# Compile in-process and drop daemon leftovers: the image snapshot fails on vanished Kotlin daemon run files otherwise.
RUN ./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process bootJar -x test && rm -rf /root/.kotlin

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar /app/app.jar
# Favour fast startup over peak throughput on small CPU allocations.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:TieredStopAtLevel=1"
EXPOSE 8080
CMD ["java", "-jar", "/app/app.jar"]
