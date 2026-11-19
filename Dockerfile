



## BUILD STAGE
#FROM maven:3.9.4-amazoncorretto-20-debian AS build
#WORKDIR /app
##COPY pom.xml .
#COPY . .
#RUN mvn clean package -DskipTests
#
## RUNTIME STAGE
#FROM openjdk:21-jdk-slim
#WORKDIR /app
#COPY --from=build /app/target/exam-docker.jar exam-docker.jar
#EXPOSE 8080
#ENTRYPOINT ["java", "-jar", "exam-docker.jar"]




# BUILD STAGE
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# RUNTIME STAGE
FROM eclipse-temurin:21-jre

# Install fontconfig and fonts required for PDF generation (AWT/Flying Saucer)
RUN apt-get update && apt-get install -y fontconfig fonts-dejavu-core && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /app/target/exam-docker.jar exam-docker.jar
EXPOSE 8080
# Java in a container uses only 1/4 of the memory for its heap by default (512 MB of Render Standard's 2 GB),
# and on one CPU the simplest stop-everything garbage collector. Under a full exam hall that heap fills and the
# server stalls. Give the heap 65% (the rest is for threads, classes and the OS), use G1, and restart cleanly
# instead of limping on if memory ever does run out. JAVA_OPTS (Render environment) can override these.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=65 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar exam-docker.jar"]
