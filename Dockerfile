FROM eclipse-temurin:17-jdk AS build

WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

RUN chmod +x mvnw

COPY src/ src/

ENV MAVEN_OPTS="-Djava.net.preferIPv4Stack=true"

RUN ./mvnw -B -U clean package -DskipTests


FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

RUN mkdir -p /data/uploads

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]