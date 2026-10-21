# syntax=docker/dockerfile:1.7

# 第一階段：構建應用程式
FROM eclipse-temurin:25-jdk AS build
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

# 第二階段：運行應用程式（同一個容器內有兩個程序：Java bot 與 Python image-translator）
# 固定 noble（Ubuntu 24.04）：python3 套件是 3.12，與服務的測試版本一致；不帶 OS 後綴的 25-jre 標籤已指向 Ubuntu 26.04（Python 3.14）。
FROM eclipse-temurin:25-jre-noble
WORKDIR /app

# 設置時區、字型（譯文繪製用）、容器健康檢查工具與 Python 3.12
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        curl tzdata fonts-noto-cjk fonts-noto-core fonts-dejavu-core python3 python3-venv && \
    fc-match "Noto Sans CJK TC" | grep -qi "NotoSansCJK" && \
    python3 -c 'import sys; assert sys.version_info[:2] == (3, 12), sys.version' && \
    ln -fs /usr/share/zoneinfo/Asia/Taipei /etc/localtime && \
    dpkg-reconfigure -f noninteractive tzdata && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# 使用固定 UID/GID 的非 root 使用者；日誌與 image-translator 的資料（budget.json、請求記錄）目錄屬於它
RUN groupadd --system --gid 10001 app && \
    useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app && \
    mkdir -p /app/logs /app/image-translator/data && \
    chown -R app:app /app

# image-translator 的 Python 環境：只裝執行期套件（不含 pytest）；requirements 不變時這一層直接用快取
RUN --mount=type=bind,source=services/image-translator/requirements-runtime.txt,target=/tmp/requirements-runtime.txt \
    --mount=type=cache,target=/root/.cache/pip \
    python3 -m venv /opt/image-translator && \
    /opt/image-translator/bin/pip install --disable-pip-version-check -r /tmp/requirements-runtime.txt

# image-translator 程式（templates 在 app/ 內；測試不放進映像）
COPY --chown=app:app services/image-translator/app /app/image-translator/app

# 啟動腳本與健康檢查
COPY --chown=app:app --chmod=0755 docker/entrypoint.sh /app/entrypoint.sh
COPY --chown=app:app --chmod=0755 docker/healthcheck.sh /app/healthcheck.sh

# 複製構建的 JAR 文件
COPY --from=build --chown=app:app /workspace/app/target/*.jar app.jar

# 設置環境變量；JVM heap 依容器記憶體比例設定上限（留 40% 給 Python 程序與 JVM 以外的記憶體），OOM 時直接結束交由重啟策略恢復。
# 部署時可用同名環境變數覆寫 JAVA_TOOL_OPTIONS。
# image-translator 只綁 127.0.0.1:4041，Java 以 IMAGE_TRANSLATOR_URL 呼叫它；IMAGE_TRANSLATOR_TOKEN 沒設時由 entrypoint 每次啟動產生。
ENV SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=4040 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0 -XX:+ExitOnOutOfMemoryError" \
    IMAGE_TRANSLATOR_URL=http://127.0.0.1:4041 \
    IMAGE_TRANSLATOR_BIND=127.0.0.1 \
    IMAGE_TRANSLATOR_PORT=4041 \
    IMAGE_TRANSLATOR_DATA_DIR=/app/image-translator/data \
    PYTHONUNBUFFERED=1

# 暴露端口
EXPOSE 4040

# bot 的 readiness 與 image-translator 的 /healthz 都要通過（OCR_ENABLED=false 時只檢查 bot）；失敗時讓部署流程自動回滾
HEALTHCHECK --interval=10s --timeout=10s --start-period=60s --retries=6 \
    CMD ["/app/healthcheck.sh"]

USER app:app

# 運行兩個程序：先 image-translator（等到 /healthz 回應），再 Java bot；任一個結束就整個容器結束
ENTRYPOINT ["/app/entrypoint.sh"]
