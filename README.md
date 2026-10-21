# LINE Bot 翻譯機器人

這是一個基於 Spring Boot 開發的 LINE Bot 翻譯機器人，透過 OpenRouter 統一 API 使用多種模型進行文字翻譯，並以 Google Cloud Vision API 進行圖片文字識別與翻譯。

共同 domain 名稱與架構決策請先閱讀 [Domain glossary 與系統脈絡](CONTEXT.md) 及 [Architecture Decision Records](docs/adr/README.md)。

## 功能特點

- **自動語言檢測**：自動識別輸入文字的語言，並選擇適當的目標語言進行翻譯
- **多語言翻譯**：支持多種語言之間的翻譯，包括中文、英文、日文、韓文等
- **圖片文字識別**：使用 OCR 技術識別圖片中的文字並翻譯
- **處理中提示**：一對一聊天室執行文字或圖片翻譯時顯示 bounded LINE loading animation；群組與命令安全略過
- **翻譯結果操作**：支援複製譯文、改用常用目標語言及具冪等保護的重新翻譯 Quick Reply
- **用戶偏好設定**：允許用戶在對話中搜尋、指定 OpenRouter 模型與默認翻譯語言
- **最近活動**：保留最近使用語言與有限筆翻譯活動，供狀態與偏好使用
- **管理員統計**：為管理員提供系統使用統計信息

## 技術架構

- **後端框架**：Spring Boot 4.1（Java 17+）
- **數據庫**：MongoDB
- **翻譯引擎**：OpenRouter Chat Completions API
- **OCR 技術**：Google Cloud Vision API
- **圖片翻譯服務**：[`services/image-translator/`](services/image-translator/README.md)（Python／FastAPI），與 Java bot 放在同一個 image、同一個容器，見下方「單一容器」
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
TRANSLATION_GLOSSARY_VERSION=none
TRANSLATION_ACTION_CLAIM_LEASE=PT5M

# Webhook 非同步處理、去重與 LINE 回覆重試
WEBHOOK_CORE_THREADS=2
WEBHOOK_MAX_THREADS=4
WEBHOOK_QUEUE_CAPACITY=100
WEBHOOK_RECEIPT_TTL=P7D
WEBHOOK_PROCESSING_LEASE=PT5M
WEBHOOK_REPLY_MAX_ATTEMPTS=3
WEBHOOK_REPLY_RETRY_BACKOFF=PT1S

# MongoDB 資料保留（ISO-8601 duration）；留空或 PT0S 停用該集合的保留
TRANSLATION_RECORD_RETENTION=P90D
TRANSLATION_ACTION_CLAIM_RETENTION=P7D
AI_USAGE_EVENT_RETENTION=P730D

# reply token 過期時改用 push 傳送答案（push 會消耗官方帳號每月訊息額度）
LINE_PUSH_FALLBACK_ENABLED=true
LINE_PUSH_FALLBACK_DAILY_LIMIT=100

# OpenRouter 配置（API key 的名稱固定為 OPEN_ROUTE_API_KEY）
OPEN_ROUTE_API_KEY=your_openrouter_api_key
OPEN_ROUTE_MODEL_NAME=openai/gpt-4o-mini
OPEN_ROUTE_API_URL=https://openrouter.ai/api/v1
# 啟動完成後在背景預先載入 OpenRouter 模型清單，第一次查詢不必等候；設為 false 則維持首次查詢時才載入
OPEN_ROUTE_CATALOG_WARMUP=true

# Google Cloud Vision API 配置 (OCR)
# 指向 Google service account JSON；正式環境改由 GOOGLE_CREDENTIALS_JSON secret 傳入（見 deployment-guide.md）
GOOGLE_APPLICATION_CREDENTIALS=./linebot.json
GOOGLE_VISION_TIMEOUT_MS=60000

# 應用程式配置
OCR_ENABLED=true
# 翻譯圖片後是否再附上辨識文字、譯文與快速操作（管理員可用 /admin config image-text 動態切換）
IMAGE_TEXT_REPLY_ENABLED=true

# 可選 MinIO 圖片儲存；停用或不可用時圖片翻譯仍可繼續
MINIO_ENABLED=true
MINIO_ENDPOINT=http://127.0.0.1:9000
MINIO_PUBLIC_ENDPOINT=https://s3.example.com
MINIO_ACCESS_KEY=your_minio_access_key
MINIO_SECRET_KEY=your_minio_secret_key
MINIO_BUCKET_NAME=linebot-images
MINIO_REGION=us-east-1

# 管理員配置
ADMIN_USERS=U123456789abcdef,U987654321abcdef

# 選填：圖片管理頁 /admin/images 的 Bearer token；留空（預設）則不註冊該頁面與 API
ADMIN_WEB_TOKEN=
```

上列只是常用設定；應用程式綁定的完整環境變數、預設值與用途見 [`.env.example`](.env.example)，正式部署的傳遞方式見 [deployment-guide.md](deployment-guide.md)。選填的 `OPEN_ROUTE_HTTP_REFERER`、`OPEN_ROUTE_APP_TITLE` 在本機 `.env` 留空即不送出對應的 OpenRouter 標頭；GitHub Actions 部署路徑中空白變數會回到預設值，請設為 `none` 才會不送出，未設定則使用預設值。

`ADMIN_USERS` 只是管理員清單的 bootstrap seed：MongoDB `runtime_settings` 尚未保存管理員清單時才會使用。管理員以 `/admin add`、`/admin remove` 做的變更會保存在 MongoDB，重啟或重新部署後仍有效；清單一旦保存，之後修改 `ADMIN_USERS` 不會覆蓋它。最後一位管理員無法被移除。

設定 `ADMIN_WEB_TOKEN` 後，可開啟 `https://<host>/admin/images`，輸入該 token 檢視、篩選並刪除機器人存入 MinIO 的圖片；使用者原圖不會自動刪除，這個頁面是人工清理的入口。部署細節見 [deployment-guide.md](deployment-guide.md)。

翻譯快取採 `expire-after-write` TTL；超過 `TRANSLATION_CACHE_MAX_ENTRIES` 時，Caffeine 會依使用頻率與近期性淘汰項目。失敗、safety blocked、fallback、provider 與規劃路由不符，以及未通過目標語言驗證（中文目標會檢查繁簡字集）或 structured schema 驗證的結果不會寫入；實際使用的 style preset ID、prompt version 與 glossary version 都納入 cache identity。語言偵測結果另由 `LanguageDetectionCache` 記住，沿用相同的 TTL 與容量上限，`unknown` 不寫入。

Webhook 先驗證 LINE signature，再以 `webhookEventId` 建立 MongoDB TTL receipt 並交給 bounded executor。重送事件不重複處理；queue 已滿或 receipt store 暫時不可用時回傳 `503`，讓 LINE 稍後 redeliver。receipt 僅保存事件 ID、時間與處理狀態，不保存訊息內容或 reply token。

`translation_records`（預設 90 天）、`translation_action_claims`（7 天）與 `ai_usage_events`（730 天）以 MongoDB TTL index 自動清除舊資料；啟動完成後由 `RetentionIndexInitializer` 建立或更新索引，保留期可用上列 `*_RETENTION` 調整（`user_profiles` 與 `runtime_settings` 不在此範圍）。舊紀錄過期後，翻譯結果的 postback 會回覆「找不到可操作的翻譯結果」，管理員統計的總量只涵蓋仍保留的紀錄。

reply token 約一分鐘後失效，圖片翻譯可能超過這段時間。LINE 以 `Invalid reply token` 拒絕 reply 時，機器人會把同一則答案改用 push 傳給原本的使用者、群組或聊天室，其他 4xx 不受影響。push 會消耗官方帳號的每月訊息額度（reply 免費），因此每日最多備援 `LINE_PUSH_FALLBACK_DAILY_LIMIT` 則（以 UTC+8 計日，重啟後歸零），也可用 `LINE_PUSH_FALLBACK_ENABLED=false` 完全關閉；結果計入 `line.push.fallback{result=sent|skipped_limit|failed}`。群組與聊天室的 push 可能依成員人數計入額度，請依官方帳號方案調整每日上限。管理員可用 `/admin quota` 查看每月額度、已用量、剩餘量與今日備援次數；每日首次發現本月用量達 80% 時會記錄一筆 WARN。

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

### 單一容器

GHCR 上只有一個 image（`ghcr.io/alientechforge/linebot-translator`），伺服器上只有一個容器 `linebot-translator`，裡面有兩個程序：Java bot（`SERVER_PORT`，預設 4040）與 Python 圖片翻譯服務 image-translator（預設只綁 `127.0.0.1:4041`，Java 以 `IMAGE_TRANSLATOR_URL` 呼叫）。`/app/entrypoint.sh` 先啟動 image-translator 並等它的 `/healthz` 回應，再啟動 Java；任一個程序結束，另一個會被停止、整個容器結束並由 Docker 的 restart policy 重啟。令牌 `IMAGE_TRANSLATOR_TOKEN` 沒設定時每次啟動自動產生，兩個程序共用。`OCR_ENABLED=false` 時不啟動 image-translator。

| 環境變數 | 預設 | 說明 |
| --- | --- | --- |
| `ANTHROPIC_API_KEY` | 無 | `OCR_ENABLED=true` 時必填；image-translator 的規劃與視覺 judge |
| `IMAGE_TRANSLATOR_TOKEN` | 每次啟動產生 | Java 與 image-translator 共用的令牌（image-translator 內部叫 `SERVICE_TOKEN`） |
| `IMAGE_TRANSLATOR_URL` | `http://127.0.0.1:4041` | Java 呼叫 image-translator 的位址 |
| `IMAGE_TRANSLATOR_BIND` | `127.0.0.1` | image-translator 的綁定位址；`0.0.0.0` 會在 host network 的主機上公開 4041 埠 |
| `IMAGE_TRANSLATOR_TIMEOUT` | `PT4M` | Java 等待 image-translator 回應的逾時 |
| `CLAUDE_MODEL`、`CLAUDE_JUDGE_MODEL` | `claude-sonnet-5-5` | 規劃與視覺 judge 的模型 |
| `OPENROUTER_IMAGE_MODEL` | `google/gemini-3-pro-image` | 影像模型 |
| `OPENROUTER_MONTHLY_BUDGET_USD`、`ANTHROPIC_MONTHLY_BUDGET_USD` | `15`、`80` | 每月預算（USD），`0` 不設上限 |
| `SEND_MIN_USABILITY`、`V1_DEADLINE_S` | `0.60`、`210` | 送圖的最低 usability；單一請求的整體時限（秒） |
| `LAB_TOKEN` | 空 | `/lab` 診斷頁的令牌 |

- **資料**：`budget.json`（本月預算用量）與請求記錄在 `/app/image-translator/data`，部署腳本把 Docker volume `linebot-image-translator-data` 掛在那裡；`docker-compose.yml` 用 `./image-translator-data`（先 `chown 10001:10001`）。
- **兩個 health endpoint**：`http://127.0.0.1:4040/actuator/health/readiness`（bot）與 `http://127.0.0.1:4041/healthz`（image-translator，附本月預算數字）。容器的 `HEALTHCHECK` 兩個都檢查（`OCR_ENABLED=false` 時只檢查 bot）：`docker inspect --format '{{.State.Health.Status}}' linebot-translator`。
- **lab 頁面**：`/lab` 與 `/v1` 在同一個 4041 埠。建議用 SSH 通道（`ssh -L 4041:127.0.0.1:4041 <伺服器>`，開 `http://127.0.0.1:4041/lab/`）；要直接從外面開就設 `IMAGE_TRANSLATOR_BIND=0.0.0.0` 與 `LAB_TOKEN`，並用防火牆限制來源。**host network 下這會直接公開 4041 埠。**
- 完整說明（流程、設定表、記憶體、日誌前綴）見 [deployment-guide.md](deployment-guide.md)；服務本身見 [`services/image-translator/README.md`](services/image-translator/README.md)。

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

### 翻譯結果操作

- 支援 Quick Reply 的 LINE iOS/Android client 會顯示複製、常用目標語言與重新翻譯操作。
- Clipboard action 依 LINE 限制只在譯文不超過 1,000 字元時提供；主訊息永遠保留完整可讀結果。
- 換語言／重新翻譯 postback 只傳 opaque record ID 與目標語言；不傳原文或譯文，且重複點擊不會再次呼叫 provider。

### 命令列表

- `/help` - 顯示幫助信息
- `/about` - 關於此機器人
- `/models [關鍵字]` - 列出或搜尋 OpenRouter 可用模型（最多顯示前 20 個）
- `/model [完整模型 slug]` - 指定使用模型；`/setmodel` 為相同功能的相容別名
- `/styles` - 列出忠實、自然、口語、正式、商務與字幕精簡風格
- `/style [風格 ID]` - 設定個人預設翻譯風格
- `/translate-style [風格 ID] [文字]` - 僅本次翻譯套用指定風格，不修改預設值
- `/外文翻譯 [語言]` - 設置一般文字的默認目標語言
- `/中文翻譯 [語言]` - 設置中文文字的默認目標語言
- `/lang` - 顯示語言選擇菜單
- `/status` - 查看目前偏好與翻譯統計
- `/profile` - 查看用戶資料

### 管理員命令

- `/admin stats` - 顯示系統統計信息
- `/admin today` - 顯示今日統計信息
- `/admin quota` - 顯示官方帳號每月訊息額度、已用量與今日 push 備援次數
- `/admin users` - 顯示最近活躍用戶
- `/admin config` - 查看或變更非敏感 runtime settings
- `/admin usage` - 查看 provider attempt 使用量與成本報表
- `/admin add [用戶 ID]` - 新增管理員（保存於 MongoDB，重啟後仍有效）
- `/admin remove [用戶 ID]` - 移除管理員（保存於 MongoDB，無法移除最後一位管理員）
- `/admin config model [完整模型 slug]` - 設置全域 OpenRouter 默認模型
- `/admin config image-text [on|off]` - 切換翻譯圖片後是否附上辨識文字、譯文與快速操作（預設 on，部署預設值 `IMAGE_TEXT_REPLY_ENABLED`）

## 貢獻與支持

歡迎提出問題和建議，或者提交 Pull Request 來改進這個項目。
