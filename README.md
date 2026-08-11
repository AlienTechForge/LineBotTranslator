# LINE Bot 翻譯機器人

這是一個基於 Spring Boot 開發的 LINE Bot 翻譯機器人，透過 OpenRouter 統一 API 使用多種模型進行文字翻譯，並以 Google Cloud Vision API 進行圖片文字識別與翻譯。

共同 domain 名稱與架構決策請先閱讀 [Domain glossary 與系統脈絡](CONTEXT.md) 及 [Architecture Decision Records](docs/adr/README.md)。

## 功能特點

- **自動語言檢測**：自動識別輸入文字的語言，並選擇適當的目標語言進行翻譯
- **多語言翻譯**：支持多種語言之間的翻譯，包括中文、英文、日文、韓文等
- **圖片文字識別**：使用 OCR 技術識別圖片中的文字並翻譯
- **處理中提示**：一對一聊天室執行文字或圖片翻譯時顯示 bounded LINE loading animation；群組與命令安全略過
- **用戶偏好設定**：允許用戶在對話中搜尋、指定 OpenRouter 模型與默認翻譯語言
- **最近活動**：保留最近使用語言與有限筆翻譯活動，供狀態與偏好使用
- **管理員統計**：為管理員提供系統使用統計信息

## 技術架構

- **後端框架**：Spring Boot 4.1（Java 17+）
- **數據庫**：MongoDB
- **翻譯引擎**：OpenRouter Chat Completions API
- **OCR 技術**：Google Cloud Vision API
- **消息平台**：LINE Messaging API（LINE Bot SDK for Java 10.1）
- **架構文件**：[Domain glossary 與系統脈絡](CONTEXT.md)、[Architecture Decision Records](docs/adr/README.md)

## 環境配置

在運行本項目前，請確保您已經設置了以下環境變數：

```
# 伺服器配置
SERVER_PORT=4040

# LINE Bot 配置
LINE_BOT_CHANNEL_TOKEN=your_line_bot_channel_token
LINE_BOT_CHANNEL_SECRET=your_line_bot_channel_secret

# MongoDB 配置
MONGODB_URI=mongodb://localhost:27017/linebot_translator
MONGODB_DATABASE=linebot_translator

# 有界翻譯快取與安全失效版本
TRANSLATION_CACHE_TTL=PT30M
TRANSLATION_CACHE_MAX_ENTRIES=1000
TRANSLATION_STYLE=neutral
TRANSLATION_GLOSSARY_VERSION=none
TRANSLATION_PROMPT_VERSION=translation-v1

# Webhook 非同步處理、去重與 LINE 回覆重試
WEBHOOK_CORE_THREADS=2
WEBHOOK_MAX_THREADS=4
WEBHOOK_QUEUE_CAPACITY=100
WEBHOOK_RECEIPT_TTL=P7D
WEBHOOK_PROCESSING_LEASE=PT5M
WEBHOOK_REPLY_MAX_ATTEMPTS=3
WEBHOOK_REPLY_RETRY_BACKOFF=PT1S

# OpenRouter 配置（API key 的名稱固定為 OPEN_ROUTE_API_KEY）
OPEN_ROUTE_API_KEY=your_openrouter_api_key
OPEN_ROUTE_MODEL_NAME=openai/gpt-4o-mini
OPEN_ROUTE_API_URL=https://openrouter.ai/api/v1

# Google Cloud Vision API 配置 (OCR)
GOOGLE_CLOUD_VISION_API_KEY=your_google_cloud_vision_api_key

# 應用程式配置
OCR_ENABLED=true

# 可選 MinIO 圖片儲存；停用或不可用時圖片翻譯仍可繼續
MINIO_ENABLED=true
MINIO_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=your_minio_access_key
MINIO_SECRET_KEY=your_minio_secret_key
MINIO_BUCKET_NAME=linebot-images

# 管理員配置
ADMIN_USERS=U123456789abcdef,U987654321abcdef
```

翻譯快取採 `expire-after-write` TTL；超過 `TRANSLATION_CACHE_MAX_ENTRIES` 時，Caffeine 會依使用頻率與近期性淘汰項目。失敗、safety blocked 與 model mismatch 不會寫入；變更 style、glossary 或 prompt version 會自然切換到新的 cache identity。

Webhook 先驗證 LINE signature，再以 `webhookEventId` 建立 MongoDB TTL receipt 並交給 bounded executor。重送事件不重複處理；queue 已滿或 receipt store 暫時不可用時回傳 `503`，讓 LINE 稍後 redeliver。receipt 僅保存事件 ID、時間與處理狀態，不保存訊息內容或 reply token。

## 建立與運行

使用 Maven 建立專案：

```bash
mvn clean package
```

運行應用程式：

```bash
java -jar target/linebot-translator-0.0.1-SNAPSHOT.jar
```

或使用 Spring Boot Maven 插件：

```bash
./mvnw spring-boot:run
```

## 部署

正式部署由 `.github/workflows/ci-cd.yml` 執行 Maven quality gate，建立並推送 immutable GHCR image，再由 self-hosted runner 執行 `scripts/deploy.sh`、readiness 檢查與失敗 rollback。也可在其他支援 Java 17 或 Docker 的環境自行部署。

維運日誌必須遵守 [日誌資料安全政策](docs/logging-data-policy.md)，不得記錄 credentials、使用者原文、OCR 結果、signed URL 或第三方 API payload。

## 使用指南

### 文本翻譯

- **自動翻譯**：直接發送文字，機器人會自動檢測來源語言，再依個人偏好與 runtime defaults 決定目標語言

- **指定翻譯**：使用特定格式指定翻譯語言
    - 格式 1：`翻譯成[語言] [文字]`，例如：`翻譯成日文 你好`
    - 格式 2：`翻譯成[語言代碼] [文字]`，例如：`翻譯成ja 你好`
    - 格式 3：`快速翻譯:[語言代碼] [文字]`，例如：`快速翻譯:en 你好`

### 圖片翻譯

- 直接將包含文字的圖片發送給機器人，它會自動識別並翻譯圖中的文字

### 命令列表

- `/help` - 顯示幫助信息
- `/about` - 關於此機器人
- `/models [關鍵字]` - 列出或搜尋 OpenRouter 可用模型（最多顯示前 20 個）
- `/model [完整模型 slug]` - 指定使用模型；`/setmodel` 為相同功能的相容別名
- `/外文翻譯 [語言]` - 設置一般文字的默認目標語言
- `/中文翻譯 [語言]` - 設置中文文字的默認目標語言
- `/lang` - 顯示語言選擇菜單
- `/status` - 查看目前偏好與翻譯統計
- `/profile` - 查看用戶資料

### 管理員命令

- `/admin stats` - 顯示系統統計信息
- `/admin today` - 顯示今日統計信息
- `/admin users` - 顯示最近活躍用戶
- `/admin config` - 查看或變更非敏感 runtime settings
- `/admin usage` - 查看 provider attempt 使用量與成本報表
- `/admin config model [完整模型 slug]` - 設置全域 OpenRouter 默認模型

## 貢獻與支持

歡迎提出問題和建議，或者提交 Pull Request 來改進這個項目。
