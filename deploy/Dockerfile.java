ARG MAVEN_IMAGE=mirror.gcr.io/library/maven:3.9.9-eclipse-temurin-8@sha256:e20e4fc05fb5480c884a1f750c362ed0e91aca15cec5736e12796713710ec9b3
ARG BACKEND_RUNTIME_IMAGE=himitzh/hoj_backend@sha256:66db67e00f2d0f33c742aaf64315e2145a2b02a9e20b0b03050f3764467cae8e
ARG JUDGESERVER_RUNTIME_IMAGE=himitzh/hoj_judgeserver@sha256:29adf09477ea2840d7ecdec09a886e96ac126844782365b38ed97d098009ee0b
FROM ${MAVEN_IMAGE} AS build
WORKDIR /src
ENV MAVEN_OPTS="-Djava.net.preferIPv4Stack=true -Dhttps.protocols=TLSv1.2"
COPY hoj-springboot/ ./
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -B -ntp -U package

FROM build AS edu-tests
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -B -ntp -U -pl DataBackup -am -DskipTests=false -Dtest=EduClassServiceTest,EduCourseServiceTest,CourseIconStoreTest -Dsurefire.failIfNoSpecifiedTests=false test

# Keep upstream runtime tools and judge sandbox; replace the application jars.
FROM ${BACKEND_RUNTIME_IMAGE} AS backend
ARG SOURCE_REVISION=uncommitted
LABEL org.opencontainers.image.revision=${SOURCE_REVISION} hoj.build=source
COPY --from=build /src/DataBackup/target/hoj-backend-4.6.jar /app.jar

FROM ${JUDGESERVER_RUNTIME_IMAGE} AS judgeserver
ARG SOURCE_REVISION=uncommitted
LABEL org.opencontainers.image.revision=${SOURCE_REVISION} hoj.build=source
COPY --from=build /src/JudgeServer/target/hoj-judgeServer-4.6.jar /judge/server/app.jar
