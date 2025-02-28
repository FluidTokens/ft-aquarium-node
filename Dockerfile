FROM openjdk:21-jdk-slim
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*
ADD ./build/libs/*.jar /app/app.jar
WORKDIR /app
ENTRYPOINT ["java", "-jar", "app.jar"]
