FROM eclipse-temurin:17-jre@sha256:1824944ef1bd572d1ff0952afeb2fec7931d77c972c4fbc4dfcdf89f758fb490

WORKDIR /app

COPY aiwei-tools-service.jar /app/aiwei-tools-service.jar

EXPOSE 8095

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Dfile.encoding=UTF-8", "-jar", "/app/aiwei-tools-service.jar"]
