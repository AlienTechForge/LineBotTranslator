# LINE Bot Translator CI/CD 部署指南

本專案使用 GitHub Actions、GHCR 與 Alien-Server self-hosted runner，不使用 SSH 部署帳號或 PAT。

## 流程行為

- Pull Request 到 `master`：在 GitHub-hosted runner 啟動隔離 MongoDB，執行 `./mvnw clean verify -B`。
- Push 到 `master`：CI 成功後，由 self-hosted runner 建立並推送 `${commit SHA}` 與 `latest` image，接著部署 SHA image。
- Push `v*` tag：建立 SHA 與版本 tag image，不變更正式容器。
- `workflow_dispatch`：可手動重跑；只有從 `master` 執行時會部署。

部署會先保留目前容器。新容器通過 `/actuator/health/readiness` 後才移除舊容器；失敗或腳本中斷時會恢復舊容器。

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

## Actions Variables

建議值：

| Variable | Default | 說明 |
| --- | --- | --- |
| `CONTAINER_NAME` | `linebot-translator` | 正式容器名稱 |
| `DOCKER_NETWORK` | `host` | 與既有 MongoDB/MinIO 相容的 Docker network |
| `SERVER_PORT` | `4040` | 容器內服務 port |
| `HOST_PORT` | `4040` | 非 host network 時的宿主機 port |
| `HEALTH_TIMEOUT_SECONDS` | `120` | 等待 readiness 的最長秒數 |

## 手動部署

伺服器已有 `.env`、`linebot.json` 且已登入 GHCR 時，可在 checkout 根目錄執行：

```bash
./run-on-server.sh
```

自動部署的實際入口是 `scripts/deploy.sh`；`docker-compose.yml` 只保留為手動除錯用途。
