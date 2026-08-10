# LINE Bot Translator CI/CD 部署指南

本專案使用 GitHub Actions、GHCR 與 Alien-Server self-hosted runner，不使用 SSH 部署帳號或 PAT。

## 流程行為

- Pull Request 到 `master`：在 GitHub-hosted runner 啟動隔離 MongoDB 與 MinIO，執行 `./mvnw clean verify -B`。
- Push 到 `master`：CI 成功後，由 self-hosted runner 建立並推送 `${commit SHA}` 與 `latest` image，接著部署 SHA image。
- Push `v*` tag：建立 SHA 與版本 tag image，不變更正式容器。
- `workflow_dispatch`：可手動重跑；只有從 `master` 執行時會部署。

部署會先保留目前容器。新容器通過 `/actuator/health/readiness` 後才移除舊容器；失敗或腳本中斷時會恢復舊容器。

`/actuator/health/liveness` 只代表程序存活；`/actuator/health/readiness` 會檢查 LINE 設定、MongoDB、MinIO、OCR 與 AI provider 設定。必要依賴失敗時回 `DOWN`/HTTP 503；MinIO 或 OCR 等 optional dependency 失敗時回 `DEGRADED`/HTTP 200，因此不會造成部署 rollback。回應只公開 component status，不公開 details。

## 測試與 quality gate

`./mvnw clean verify -B` 是 PR 與 `master` 的必要 gate：

- Surefire 執行 `*Tests` focused suite，涵蓋文字/圖片翻譯、provider failure、使用者偏好、管理員授權與 signed LINE webhook routing。
- Failsafe 執行 `*IntegrationTests`，對隔離 MongoDB 與 MinIO 驗證 unavailable、recovery 與實際讀寫流程。
- `verify` lifecycle 會 compile、執行兩層測試並建立 Spring Boot JAR；workflow 另行確認 packaged JAR 存在。
- provider、LINE reply、OCR 與 storage 邊界在 focused tests 使用 mocks/adapters，不讀 production Secrets，也不呼叫付費 API。
- 測試失敗時 Actions 會保留 Surefire 與 Failsafe reports 7 天；成功時不建立多餘 artifact。

## GitHub Repository 設定

在 `Settings -> Actions -> General -> Workflow permissions` 啟用 `Read and write permissions`。Workflow 使用內建 `GITHUB_TOKEN` 推送 GHCR，不需要額外 PAT。

Self-hosted runner 必須：

- 指派給 `AlienTechForge/LineBotTranslator`。
- 具有 `self-hosted`、`Linux`、`X64` labels。
- 版本至少為 `2.327.1`，才能執行目前 Node 24 版 Actions。
- 已安裝 Docker，且 runner service 使用者可執行 Docker。

## Actions Secrets

必要：

- `LINE_BOT_CHANNEL_TOKEN`
- `LINE_BOT_CHANNEL_SECRET`
- `MONGODB_URI`
- `MONGODB_DATABASE`
- `OPENAI_API_KEY` 或 `GEMINI_API_KEY`，依 `AI_DEFAULT_PROVIDER` 而定
- `GOOGLE_CREDENTIALS_JSON`，當 `OCR_ENABLED=true` 時必要

依功能設定：

- `OPENAI_MODEL_NAME`、`OPENAI_AVAILABLE_MODELS`、`OPENAI_API_URL`
- `GEMINI_MODEL_NAME`、`GEMINI_AVAILABLE_MODELS`
- `OCR_ENABLED`、`AI_DEFAULT_PROVIDER`
- `ADMIN_USERS`
- `MINIO_ENDPOINT`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`、`MINIO_BUCKET_NAME`
- `LANGUAGE_DETECTION_USE_AI`、`LANGUAGE_DETECTION_AI_PROVIDER`、`LANGUAGE_DETECTION_DEFAULT_CHINESE`
- `APP_BROADCAST_TEST_MODE`

Secrets 只作為容器環境變數傳入，不會寫入 image、Repository 或 Actions artifact。Google service account JSON 會以權限 `0400` 儲存在 Docker volume，掛載至 `/run/secrets/linebot.json`。

## MongoDB 憑證輪替

`.github/workflows/rotate-mongodb-credential.yml` 提供可回滾的人工輪替流程：

1. 暫存 `MONGODB_ROTATION_OLD_URI`、`MONGODB_ROTATION_USERNAME` 與 `MONGODB_ROTATION_AUTH_DB`，先以 `audit` 模式確認 incident evidence、GitHub Secret、MongoDB 認證及本機 root 控制路徑一致。
2. 產生新密碼與 URI，暫存 `MONGODB_ROTATION_NEW_URI`、`MONGODB_ROTATION_OLD_PASSWORD`、`MONGODB_ROTATION_NEW_PASSWORD`，並將正式 `MONGODB_URI` 更新為新 URI。
3. 以 `rotate` 模式執行。流程會在 MongoDB container 內更新 app user、確認舊密碼遭拒、新密碼可用，再使用相同 immutable image 重建 app container。
4. 若認證驗證或部署失敗，腳本會恢復舊 MongoDB 密碼；`scripts/deploy.sh` 會恢復舊 app container。
5. 成功後立即刪除所有 `MONGODB_ROTATION_*` 暫存 Secrets，並再次確認正式 readiness 與舊密碼拒絕結果。

輪替值不可放入 workflow inputs、Actions logs、issue、commit 或 artifact。MongoDB container 必須使用 `MONGO_INITDB_ROOT_USERNAME` / `MONGO_INITDB_ROOT_PASSWORD`，若同時執行多個 MongoDB container，需在 dispatch 時明確填入 container name。

## Actions Variables

建議值：

| Variable | Default | 說明 |
| --- | --- | --- |
| `CONTAINER_NAME` | `linebot-translator` | 正式容器名稱 |
| `DOCKER_NETWORK` | `host` | 與既有 MongoDB/MinIO 相容的 Docker network |
| `SERVER_PORT` | `4040` | 容器內服務 port |
| `HOST_PORT` | `4040` | 非 host network 時的宿主機 port |
| `HEALTH_TIMEOUT_SECONDS` | `120` | 等待 readiness 的最長秒數 |
| `MONGODB_CONNECT_TIMEOUT_MS` | `3000` | Mongo socket 連線 timeout |
| `MONGODB_READ_TIMEOUT_MS` | `5000` | Mongo socket 讀取 timeout |
| `MONGODB_SERVER_SELECTION_TIMEOUT_MS` | `3000` | 每次操作等待可用 Mongo 節點的上限 |
| `MONGODB_HEARTBEAT_FREQUENCY_MS` | `5000` | Mongo background topology recheck 間隔 |
| `MONGODB_MIN_HEARTBEAT_FREQUENCY_MS` | `500` | Mongo background recheck 的最小間隔 |
| `MINIO_ENABLED` | `true` | 是否啟用 optional MinIO 圖片儲存 |
| `MINIO_RETRY_INTERVAL_MS` | `30000` | MinIO outage 後再次 probe 的間隔 |
| `MINIO_CONNECT_TIMEOUT_MS` | `3000` | MinIO 連線 timeout |
| `MINIO_WRITE_TIMEOUT_MS` | `5000` | MinIO 上傳 timeout |
| `MINIO_READ_TIMEOUT_MS` | `5000` | MinIO API 讀取 timeout |

## 手動部署

伺服器已有 `.env`、`linebot.json` 且已登入 GHCR 時，可在 checkout 根目錄執行：

```bash
./run-on-server.sh
```

自動部署的實際入口是 `scripts/deploy.sh`；`docker-compose.yml` 只保留為手動除錯用途。
