# syntax=docker/dockerfile:1.7

# 第一階段：構建應用程式
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace/app

# 複製 Maven 包裝器和 POM 文件
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# 修正 Maven 包裝器的執行權限
RUN chmod +x ./mvnw

# 下載依賴項（這將被緩存，除非 pom.xml 更改）
RUN --mount=type=cache,target=/root/.m2 ./mvnw dependency:go-offline -B

# 複製源代碼
COPY src src

# 測試由 CI quality gate 執行；映像階段只重現 production package
RUN --mount=type=cache,target=/root/.m2 ./mvnw clean package -B -DskipTests -Pprod

# 第二階段：運行應用程式
FROM eclipse-temurin:17-jre
WORKDIR /app

# 設置時區與容器健康檢查工具
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl tzdata && \
    ln -fs /usr/share/zoneinfo/Asia/Taipei /etc/localtime && \
    dpkg-reconfigure -f noninteractive tzdata && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# 使用固定 UID/GID 的非 root 使用者
RUN groupadd --system --gid 10001 app && \
    useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app && \
    mkdir -p /app/logs && \
    chown -R app:app /app

# 複製構建的 JAR 文件
COPY --from=build --chown=app:app /workspace/app/target/*.jar app.jar

# 設置環境變量
ENV SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=4040

# 暴露端口
EXPOSE 4040

# readiness 失敗時讓部署流程自動回滾
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=6 \
    CMD curl --fail --silent --show-error "http://127.0.0.1:${SERVER_PORT}/actuator/health/readiness" || exit 1

USER app:app

# 運行應用程序
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
