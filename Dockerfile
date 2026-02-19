# --- build stage ---
FROM maven:3-amazoncorretto-25-alpine AS build

RUN apk add --no-cache binutils

WORKDIR /build
COPY pom.xml formatter.xml ./
# cache dependencies
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn package -B -DskipTests

# detect required modules from the shaded JAR and build a minimal JRE
RUN jdeps \
      --ignore-missing-deps \
      --print-module-deps \
      --multi-release 25 \
      target/wen.jar > /tmp/modules.txt && \
    jlink \
      --add-modules $(cat /tmp/modules.txt) \
      --strip-debug \
      --no-man-pages \
      --no-header-files \
      --compress zip-9 \
      --output /jre

# --- runtime stage ---
FROM alpine:3

# Required at runtime:
#   DISCORD_TOKEN          - Bot token
#   DISCORD_APPLICATION_ID - Application ID
#   WEN_CONFIG_B64         - Base64-encoded config.toml

COPY --from=build /jre /opt/jre
COPY --from=build /build/target/wen.jar /app/wen.jar
COPY docker/entrypoint.sh /app/entrypoint.sh

ENV JAVA_HOME=/opt/jre
ENV PATH="${JAVA_HOME}/bin:${PATH}"
ENV TZ=America/Los_Angeles

EXPOSE 8080

WORKDIR /app
RUN chmod +x entrypoint.sh
ENTRYPOINT ["./entrypoint.sh"]
