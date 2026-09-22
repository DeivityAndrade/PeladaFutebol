FROM node:24-alpine AS frontend
WORKDIR /app
RUN npm install --global pnpm@11.19.0
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY frontend/ ./
RUN pnpm build

FROM maven:3.9.11-eclipse-temurin-21 AS backend
WORKDIR /app/backend
COPY backend/pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /app/dist/pelada/browser /app/frontend/dist/pelada/browser
RUN mvn -B -ntp package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S pelada && adduser -S pelada -G pelada
COPY --from=backend /app/backend/target/pelada-1.0.0.jar app.jar
USER pelada
ENV PORT=8080 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -Xss512k"
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
