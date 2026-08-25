FROM eclipse-temurin:21-jdk-jammy
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*
# Exactly one jar must match this glob: Docker refuses a multi-source ADD to a non-directory
# destination. build.gradle disables the `-plain` library jar for this reason.
ADD ./build/libs/*.jar /app/app.jar
WORKDIR /app
ENTRYPOINT ["java", "-jar", "app.jar"]
