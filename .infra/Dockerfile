FROM eclipse-temurin:25-jdk-jammy AS build
WORKDIR /src

COPY . .

RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=secret,id=codeartifact_token \
    export CODEARTIFACT_AUTH_TOKEN=$(cat /run/secrets/codeartifact_token) \
    && ./mvnw -s settings.xml -Pexclude-spam-filter -pl service -am -DskipTests package \
    && jar="$(find service/target -maxdepth 1 -type f -name 'TextSecureServer-*.jar' | sort | head -n 1)" \
    && test -n "${jar}" \
    && cp "${jar}" /tmp/TextSecureServer.jar \
    && cp service/target/jib-extra/usr/lib/libfdb_c.x86_64.so /tmp/libfdb_c.so

FROM eclipse-temurin:25-jre-jammy
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
      bash \
      ca-certificates \
      netcat-openbsd \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 signal \
    && useradd --system --uid 10001 --gid 10001 signal \
    && chown -R 10001:10001 /app

COPY --from=build --chown=10001:10001 /tmp/TextSecureServer.jar /app/TextSecureServer.jar
COPY --from=build /tmp/libfdb_c.so /usr/lib/libfdb_c.so

COPY --chown=10001:10001 --chmod=755 .infra/entrypoint.sh /usr/local/bin/signal-server-entrypoint.sh

USER 10001

EXPOSE 8080 8081 50051
ENTRYPOINT ["/usr/local/bin/signal-server-entrypoint.sh"]
