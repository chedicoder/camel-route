FROM eclipse-temurin:17-jre

WORKDIR /deployments
COPY target/quarkus-app/ /deployments/

# Get your application.properties file from config map
# COPY application.properties /deployments/
ENTRYPOINT ["java", "-Dquarkus.config.locations=/deployments/application.properties", "-jar", "/deployments/quarkus-run.jar"]

