# The Dominion in a container: listens on 5050 and keeps its saves on the /data volume.
#   docker build -t undying-dominion .
#   docker run -p 5050:5050 -v dominion-data:/data undying-dominion
# Options after the image name are passed to the server, e.g. --campaign /campaign or --resume crypt.
# Both base images are pinned by digest, so a rebuilt upstream tag can't change this image.

FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /build
# The pom first, so the dependency layer is reused until the pom changes.
COPY server/pom.xml .
RUN mvn -B -q dependency:go-offline
COPY server/src src
# The gate has already run every test against this source.
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
RUN useradd --system --uid 10001 --home-dir /app dominion \
 && mkdir -p /app /data \
 && chown dominion /data
COPY --from=build /build/target/undying-dominion-0.1.0-SNAPSHOT-jar-with-dependencies.jar /app/undying-dominion.jar
USER dominion
WORKDIR /app
VOLUME /data
EXPOSE 5050
ENTRYPOINT ["java", "-jar", "/app/undying-dominion.jar", "-p", "5050", "--db", "/data/undying-dominion.db", "--plain"]
CMD []
