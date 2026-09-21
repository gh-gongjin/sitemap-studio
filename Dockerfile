FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build
# .mvn/maven.config passes --settings .mvn/settings.xml relative to the workdir
COPY .mvn/ .mvn/
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests package \
    && cp target/sitemap-studio-*.jar /build/app.jar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 1001 sitemap \
    && adduser -S -u 1001 -G sitemap sitemap \
    && mkdir -p /app/data \
    && chown -R sitemap:sitemap /app
WORKDIR /app
COPY --from=builder /build/app.jar /app/app.jar
USER sitemap
EXPOSE 8080
ENV JAVA_OPTS=""
VOLUME /app/data
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
