



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
WORKDIR /app
COPY --from=build /app/target/exam-docker.jar exam-docker.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "exam-docker.jar"]
