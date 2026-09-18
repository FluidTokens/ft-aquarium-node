# FluidTokens Aquarium Node.
#
# ⛔ THIS IMAGE IS BUILT BY CI FROM A JAR THAT HAS ALREADY BEEN TESTED, and that ordering is the
# point: .github/workflows/docker-build.yml runs the full suite BEFORE `bootJar` and before the
# registry login, so a red tree can never become `:latest`. Operators PULL this image; building it
# yourself is a verification path, not the normal one (docs/deploying.md § Building it yourself).
#
# ⚠ NOT MULTI-STAGE, DELIBERATELY. Building the jar inside Docker would leave no `.git` in the build
# context, and build.gradle derives the node's own provenance (commit / commitShort / dirty) by
# shelling out to git. Every such image would report `commit=unknown` on /healthcheck -- which is
# exactly the state that endpoint exists to distinguish from a known one. Keep the jar built outside,
# where git is real.

FROM eclipse-temurin:21-jre-jammy

# ⚠ JRE, NOT JDK. The old base was `21-jdk-jammy`, which ships a compiler, jlink, jcmd and the rest
# of the toolchain into a long-running container that holds a funded wallet mnemonic in its
# environment. None of it is used at runtime.
# curl is kept: the compose healthcheck calls it.
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# ⛔ NOT ROOT. A process that reads WALLET_MNEMONIC from its environment and talks to the internet
# should not also be uid 0 inside its container. 8080 is above 1024, so no privileged bind is needed
# and nothing here requires escalation.
RUN groupadd --system --gid 10001 aquarium && \
    useradd --system --uid 10001 --gid aquarium --home-dir /app --shell /usr/sbin/nologin aquarium

WORKDIR /app

# COPY, not ADD: ADD additionally unpacks archives and fetches URLs, neither of which is wanted for
# a jar. Exactly one jar must match this glob -- Docker refuses a multi-source COPY to a non-directory
# destination -- which is why build.gradle disables the `-plain` library jar.
COPY --chown=aquarium:aquarium ./build/libs/*.jar /app/app.jar

USER aquarium

EXPOSE 8080

LABEL org.opencontainers.image.title="FluidTokens Aquarium Node" \
      org.opencontainers.image.source="https://github.com/FluidTokens/ft-aquarium-node" \
      org.opencontainers.image.licenses="Apache-2.0"

ENTRYPOINT ["java", "-jar", "app.jar"]
