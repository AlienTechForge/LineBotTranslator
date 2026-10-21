# LINE Bot Translator CI/CD 部署指南

本專案使用 GitHub Actions、GHCR 與 Alien-Server self-hosted runner，不使用 SSH 部署帳號或 PAT。

## 流程行為

- Pull Request 到 `master`：在 GitHub-hosted runner 啟動隔離 MongoDB 與 MinIO，執行 `./mvnw clean verify -B`；另一個 job 用 Python 3.12 執行 `services/image-translator` 的 `pytest`。
- Push 到 `master`：兩個 job 都成功後，由 self-hosted runner 建立並推送 `${commit SHA}` 與 `latest` image，接著部署 SHA image。
- Push `v*` tag：建立 SHA 與版本 tag image，不變更正式容器。
- `workflow_dispatch`：可手動重跑；只有從 `master` 執行時會部署。

部署會先保留目前容器。新容器的 Docker health check 通過後才移除舊容器（bot 的 `/actuator/health/readiness`，以及 `OCR_ENABLED` 不是 `false` 時 image-translator 的 `/healthz`，見下一節）；失敗或腳本中斷時會恢復舊容器。

在停止舊容器前，部署腳本會用 `OPEN_ROUTE_API_KEY` 呼叫 OpenRouter `/models`，只檢查 HTTP 狀態且不輸出回應內容。credential 無效時部署立即停止。隔離的 local smoke 可設 `VALIDATE_OPENROUTER_REMOTE=false`，正式 workflow 不應關閉。

`/actuator/health/liveness` 代表程序存活，並包含 `imageTranslator`（Java 對 image-translator 的 `GET /healthz`，3 秒逾時；`OCR_ENABLED=false` 或 `IMAGE_TRANSLATOR_ENABLED=false` 時為 `DISABLED`）；`/actuator/health/readiness` 會檢查 LINE 設定、MongoDB、MinIO 與 OpenRouter 設定，**不含** image-translator：它停擺時機器人仍要能回覆文字訊息。必要依賴失敗時回 `DOWN`/HTTP 503；MinIO 等 optional dependency 失敗時回 `DEGRADED`/HTTP 200，因此不會造成部署 rollback。回應只公開 component status，不公開 details。

Image 預設 `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=60.0 -XX:+ExitOnOutOfMemoryError`：heap 上限依容器記憶體（未設 `--memory` 時為主機記憶體）計算，OOM 時程序直接結束並由 `--restart unless-stopped` 重啟；比先前的 75% 低，是因為同一個容器裡還有 image-translator 的 Python 程序（見下一節）。與 MongoDB/MinIO 共用小主機時，可用 Actions Variable `JAVA_TOOL_OPTIONS` 改成固定 `-Xmx`。

## 單一容器：Java bot 與 image-translator

同一個 image、同一個容器裡有兩個程序，沒有第二個 image、第二個容器，也沒有它們之間的網路：

| 程序 | 內容 | 位址 |
| --- | --- | --- |
| Java bot | `java -jar /app/app.jar`（Spring Boot） | `SERVER_PORT`（預設 4040） |
| image-translator | `/opt/image-translator/bin/python -m app`（Python 3.12 venv，程式在 `/app/image-translator`，原始碼在 `services/image-translator/`） | 預設只綁 `127.0.0.1:4041`；Java 以 `IMAGE_TRANSLATOR_URL` 呼叫它 |

啟動與結束由 `/app/entrypoint.sh`（原始碼 `docker/entrypoint.sh`）負責：

1. `IMAGE_TRANSLATOR_TOKEN` 沒設（或是空字串，例如沒設定的 Secret）時，每次啟動用 `/dev/urandom` 產生 32 bytes（64 個十六進位字元）。Java 讀 `IMAGE_TRANSLATOR_TOKEN`，Python 服務拿到同一個值當 `SERVICE_TOKEN`；令牌不會寫進日誌。要自己用 `curl` 打 `/v1` 時，請把 Secret `IMAGE_TRANSLATOR_TOKEN` 設成固定值，否則每次重啟都會換。
2. 先啟動 image-translator，最多等 30 秒讓 `GET /healthz` 回應，才啟動 Java；等不到就整個容器以失敗結束（部署腳本會顯示它的日誌並回滾）。
3. 任一個程序結束，另一個會收到 SIGTERM（20 秒後仍在跑就 SIGKILL），容器以先結束的那一個的結束碼離開，Docker 的 restart policy 會把兩個一起重啟。`docker stop` 的 SIGTERM／SIGINT 會轉給兩個程序（部署腳本給 30 秒）。
4. `OCR_ENABLED=false` 時不啟動 image-translator，health check 也只看 bot。
5. 容器日誌是兩個程序合在一起：entrypoint 的行以 `[entrypoint]` 開頭，Python 的行以 `[image-translator]` 開頭，Java 的行不變。

### 第一次部署前

- 設定 Actions Secret `ANTHROPIC_API_KEY`（`OCR_ENABLED` 為 `true` 時；沒有它 `scripts/deploy.sh` 會在動任何容器之前停止，不會中斷現有服務）。
- 如果伺服器上還跑著單獨的 lab 容器 `overlay-lab`（`services/image-translator/docker-compose.yml`，它把 4041 埠發佈在主機上），先停掉它：`docker compose -f services/image-translator/docker-compose.yml down`。否則新容器（host network）的 image-translator 綁不到 `127.0.0.1:4041`，容器啟動失敗、部署回滾，日誌會顯示 `could not bind`。

### 健康檢查

Image 的 `HEALTHCHECK`（`/app/healthcheck.sh`，每 10 秒一次，啟動寬限 60 秒）兩個都要通過：

- bot：`curl http://127.0.0.1:4040/actuator/health/readiness`（內容見上文）。
- image-translator：`curl http://127.0.0.1:4041/healthz`，不需要令牌，回 `{"status": "ok", "mock": false, "version": "...", "budget": {...}}`。`budget` 是本月（Asia/Taipei）已花費與上限（`openrouter_spent_usd`、`openrouter_budget_usd`、`anthropic_spent_usd`、`anthropic_budget_usd`；上限 0 表示不設上限）。

`OCR_ENABLED=false` 時第二項略過。略過的判斷看的是容器環境裡的 `OCR_ENABLED`（或明確設定的 `IMAGE_TRANSLATOR_HEALTH=skip`）：health check 是另外啟動的程序，看不到 entrypoint 在執行時才 export 的變數。

在主機上查看：

```bash
docker inspect --format '{{.State.Health.Status}}' linebot-translator   # starting / healthy / unhealthy
docker exec linebot-translator /app/healthcheck.sh                      # 失敗時印出是哪一項，exit 1
curl -s http://127.0.0.1:4041/healthz                                    # host network 時可直接從主機打
docker logs --tail 100 linebot-translator
```

### 資料磁碟區

`scripts/deploy.sh` 把 Docker volume `linebot-image-translator-data`（`IMAGE_TRANSLATOR_DATA_VOLUME` 可改名）掛在 `/app/image-translator/data`。`budget.json`（本月預算用量）與 `/v1` 的請求記錄放在這裡，部署每次都會換新容器，沒有這個磁碟區預算用量會在每次部署後歸零。手動用 `docker-compose.yml` 時對應的是 `./image-translator-data`，需要先 `mkdir -p image-translator-data && chown 10001:10001 image-translator-data`。

### 設定

下列設定由 Actions Secrets／Variables 傳入（預設值就是 workflow 內的值）；`scripts/deploy.sh` 只傳這份清單上的設定給容器。`OCR_ENABLED` 為 `true` 時 `ANTHROPIC_API_KEY` 必填（沒有它部署會在動容器之前就停止）。

| 設定 | 預設 | 說明 |
| --- | --- | --- |
| `ANTHROPIC_API_KEY`（Secret） | 無 | Mode A 規劃與視覺 judge 用；`OCR_ENABLED=true` 時必填 |
| `IMAGE_TRANSLATOR_TOKEN`（Secret） | 每次啟動產生 | Java 與 image-translator 共用的令牌；留空由 entrypoint 產生 |
| `LAB_TOKEN`（Secret） | 空 | `/lab` 診斷頁的令牌；沒設就不設防，所以只在綁 `127.0.0.1` 時可以不設 |
| `IMAGE_TRANSLATOR_URL` | `http://127.0.0.1:4041` | Java 呼叫 image-translator 的位址 |
| `IMAGE_TRANSLATOR_BIND` | `127.0.0.1` | image-translator 的綁定位址，見「開啟 lab 頁面」 |
| `IMAGE_TRANSLATOR_TIMEOUT` | `PT4M` | Java 等待 `/v1/translations` 的讀取逾時；要大於 `V1_DEADLINE_S`（服務端整體時限 210 秒） |
| `CLAUDE_MODEL` | `claude-sonnet-5-5` | Mode A 規劃模型（服務本身未設定時的預設是 `claude-opus-5-5`） |
| `CLAUDE_JUDGE_MODEL` | `claude-sonnet-5-5` | 視覺 judge 模型 |
| `OPENROUTER_IMAGE_MODEL` | `google/gemini-3-pro-image` | Mode B 的影像模型 |
| `OPENROUTER_MONTHLY_BUDGET_USD` | `15` | OpenRouter 每月現金預算（USD）；`0` 不設上限；用完後只跳過 Mode B |
| `ANTHROPIC_MONTHLY_BUDGET_USD` | `80` | Anthropic 每月預算（USD）；`0` 不設上限；用完後 `/v1` 回 `status: error`、`reason: anthropic_budget` |
| `SEND_MIN_USABILITY` | `0.60` | 最好的那張圖的 judge usability 低於這個值就不送圖，改傳譯文 |
| `V1_DEADLINE_S` | `210` | 單一 `/v1/translations` 請求的整體時限（秒） |

這些與 `OPEN_ROUTE_API_KEY`（image-translator 也用它呼叫 OpenRouter）、`GOOGLE_APPLICATION_CREDENTIALS`（部署腳本放進容器的 Vision 憑證）都是 Java 與 Python 共用的；服務的完整設定見 [`services/image-translator/README.md`](services/image-translator/README.md)。

### 開啟 lab 頁面

`/lab` 是 image-translator 的診斷頁（上傳圖片、比較 Mode A／Mode B），跟 `/v1` 在同一個 4041 埠。預設綁 `127.0.0.1`，從外面連不到。

- 建議：不改綁定，用 SSH 通道：`ssh -L 4041:127.0.0.1:4041 <伺服器>`，然後開 `http://127.0.0.1:4041/lab/`。
- 要直接從外面開：設定 Variable `IMAGE_TRANSLATOR_BIND=0.0.0.0` 與 Secret `LAB_TOKEN`（建議 `openssl rand -hex 32`），重新部署後開 `http://<伺服器>:4041/lab/?token=<LAB_TOKEN>`。

**警告：容器用 host network，`IMAGE_TRANSLATOR_BIND=0.0.0.0` 會直接在主機上公開 4041 埠**，能連到這台主機的人都能開 `/lab`（頁面會用真實金鑰呼叫 Anthropic 與 OpenRouter，花的是預算）、讀 `/healthz` 的預算數字。務必同時設 `LAB_TOKEN`，並用防火牆限制來源，或放在 HTTPS reverse proxy 後面；用完請把 `IMAGE_TRANSLATOR_BIND` 改回 `127.0.0.1`。`/v1` 另外要 `X-Service-Token`，`LAB_TOKEN` 不能用在 `/v1`。

### 記憶體

JVM heap 上限是容器記憶體的 60%（`MaxRAMPercentage=60.0`），其餘留給 Python 程序與 JVM 的非 heap 記憶體。閒置時實測（容器 1 GiB 上限、真實 Spring Boot jar、mock 模式）：Java RSS 約 410 MB、Python 約 85 MB；翻譯圖片時 Python 的用量會上升（沒有在負載下量過），建議給容器（或沒設 `--memory` 時的主機）至少 2 GB 可用記憶體。

## 測試與 quality gate

`./mvnw clean verify -B` 是 PR 與 `master` 的必要 gate：

- Surefire 執行 `*Tests` focused suite，涵蓋文字/圖片翻譯、provider failure、使用者偏好、管理員授權與 signed LINE webhook routing。
- Failsafe 執行 `*IntegrationTests`，對隔離 MongoDB 與 MinIO 驗證 unavailable、recovery、readiness 與實際讀寫流程。
- 分層規則：`*Tests` 必須在沒有任何基礎設施的機器上以 `./mvnw test` 全數通過；任何需要 MongoDB 或 MinIO 的測試（包含斷言 readiness `mongo` component 為 `UP` 的 health endpoint 測試）都命名為 `*IntegrationTests`，交由 Failsafe 執行。
- `verify` lifecycle 會 compile、執行兩層測試並建立 Spring Boot JAR；workflow 另行確認 packaged JAR 存在。
- 「Verify production image runtime」步驟會建置正式 image，確認字型（`fc-match "Noto Sans CJK TC"`）、`/app/app.jar` 與 `/app/entrypoint.sh` 存在，並在 image 內只啟動 Python 那一半（`LAB_MOCK=1`）：確認 `cv2`、`PIL`、`anthropic`、`google.cloud.vision`、`fastapi` 都能匯入，`/healthz` 回應，且一張合成 PNG 的 `/v1/translations` 回 `status: image`。Java 那一半需要 MongoDB，不在這裡啟動。
- 獨立的 `Image translator tests` job 以 Python 3.12（`actions/setup-python`）安裝 `services/image-translator/requirements.txt`（執行期套件 `requirements-runtime.txt` 加上 `pytest`）並執行 `pytest -q`；`publish` 需要它與 Maven job 都成功。映像本身只安裝 `requirements-runtime.txt`，不含 `pytest`。
- provider、LINE reply、圖片翻譯服務（以 `MockWebServer` 取代）與 storage 邊界在 focused tests 使用 mocks/adapters，不讀 production Secrets，也不呼叫付費 API。
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
- `OPEN_ROUTE_API_KEY`
- `GOOGLE_CREDENTIALS_JSON`，當 `OCR_ENABLED` 為 `true`（預設值，見 Actions Variables）時必要
- `ANTHROPIC_API_KEY`，當 `OCR_ENABLED` 為 `true` 時必要（image-translator 的規劃與視覺 judge；沒設定時部署腳本在動容器之前就停止）

依功能設定：

- `IMAGE_TRANSLATOR_TOKEN`：Java 與 image-translator 共用的令牌；留空時每次容器啟動自動產生（見「單一容器」）
- `LAB_TOKEN`：`/lab` 診斷頁的令牌，只有要把 `/lab` 開給外部時才需要

- `ADMIN_USERS`：管理員清單的 bootstrap seed。管理員以 `/admin add`、`/admin remove` 變更後，清單保存於 MongoDB `runtime_settings`；清單一旦保存，修改此環境變數不會覆蓋它
- `ADMIN_WEB_TOKEN`，啟用 `/admin/images` 圖片管理頁時才需要
- `MINIO_ENDPOINT`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`、`MINIO_BUCKET_NAME`
- `DWZ_API_TOKEN`，當管理員啟用木雷短網址時必要

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
| `IMAGE_PROXY_PUBLIC_BASE_URL` | 選填 | 需要獨立 preview proxy 時才設；留空時直接使用 MinIO/木雷 URL 傳送 LINE 圖片。 |
| `IMAGE_PROXY_CONTENT_CACHE_MAX_BYTES` | `67108864` | `/i/{token}` proxy 快取保留的 original + preview 位元組上限（64 MiB）；取代已不再生效的 `IMAGE_PROXY_CONTENT_CACHE_ENTRIES`。 |
| `IMAGE_TRANSLATION_MAX_OUTPUT_BYTES` | `10485760` | 產生圖片的大小上限（LINE 原圖限制 10 MB）；image-translator 回傳的 PNG 超過時改輸出 JPEG，仍超過則改回文字譯文。 |
| `IMAGE_TRANSLATION_DELIVERY_TARGET_BYTES` | `2097152` | 產生圖片的交付大小目標（2 MiB）。LINE 要在自己的逾時內從部署主機（MinIO 或 proxy）取回原圖，大張 PNG 可能取不到；不透明圖片的 PNG 超過此值時，改輸出 JPEG（品質 0.92，再 0.85），取第一個不超過目標者，都不符合則維持 PNG（仍受上限約束）。透明圖片一律維持 PNG（超過上限時改回文字譯文）。設為不小於 `IMAGE_TRANSLATION_MAX_OUTPUT_BYTES` 的值即停用。 |
| `IMAGE_TEXT_REPLY_ENABLED` | `false` | 翻譯後的圖片之外是否再附上辨識文字、譯文與快速操作的部署預設值（預設只回傳圖片）；管理員可用 `/admin config image-text [on\|off]` 動態切換。 |
| `DWZ_API_BASE_URL` | 短網址啟用時必填 | 木雷 API base。host network 可用固定 loopback port；也可用公開 HTTPS domain。 |
| `DWZ_SHORT_DOMAIN` | 選填 | 指定木雷短域名；留空使用木雷預設 domain。 |
| `DWZ_WORKSPACE_ID` | 選填 | 木雷 workspace ID。 |
| `SHORT_URL_ENABLED` | `false` | Mongo 尚無 persisted 值時的木雷短網址預設開關。 |
| `SERVER_PORT` | `4040` | 容器內服務 port |
| `HOST_PORT` | `4040` | 非 host network 時的宿主機 port |
| `HEALTH_TIMEOUT_SECONDS` | `120` | 等待 readiness 的最長秒數 |
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=60.0 -XX:+ExitOnOutOfMemoryError` | 容器 JVM 參數；覆寫時應保留 heap 上限與 OOM 結束設定，並為同一容器的 Python 程序留出記憶體 |
| `IMAGE_TRANSLATOR_URL`、`IMAGE_TRANSLATOR_BIND`、`IMAGE_TRANSLATOR_TIMEOUT`、`CLAUDE_MODEL`、`CLAUDE_JUDGE_MODEL`、`OPENROUTER_IMAGE_MODEL`、`OPENROUTER_MONTHLY_BUDGET_USD`、`ANTHROPIC_MONTHLY_BUDGET_USD`、`SEND_MIN_USABILITY`、`V1_DEADLINE_S` | 見「單一容器」的設定表 | image-translator 與 Java 呼叫它的設定 |
| `OPEN_ROUTE_MODEL_NAME` | `openai/gpt-4o-mini` | OpenRouter 全域預設模型 slug |
| `OPEN_ROUTE_API_URL` | `https://openrouter.ai/api/v1` | OpenRouter API base URL |
| `OPEN_ROUTE_HTTP_REFERER` | `https://github.com/AlienTechForge/LineBotTranslator` | 送往 OpenRouter 的 `HTTP-Referer` 標頭。未設定使用預設值；設為 `none`（不分大小寫）則不送出此標頭（空白變數與未設定相同，會回到預設值）。本機 `.env` 留空也不送出。 |
| `OPEN_ROUTE_APP_TITLE` | `LineBotTranslator` | 送往 OpenRouter 的 `X-OpenRouter-Title` 標頭；規則同 `OPEN_ROUTE_HTTP_REFERER`，設為 `none` 則不送出。 |
| `OPEN_ROUTE_CATALOG_TTL` | `PT15M` | OpenRouter 模型 catalog 快取存活時間 |
| `OPEN_ROUTE_CATALOG_WARMUP` | `true` | 啟動完成後是否在背景預先載入 OpenRouter 模型 catalog；設為 `false` 則維持首次查詢時才載入。GitHub 變數留空會回到預設值。 |
| `LANGUAGE_DETECTION_USE_AI` | `true` | 是否優先使用 AI 偵測來源語言；設為 `false` 則只用本地規則偵測。GitHub 變數留空會回到預設值。 |
| `LANGUAGE_DETECTION_DEFAULT_CHINESE` | `zh-tw` | 偵測到中文時回報的來源語言代碼；GitHub 變數留空會回到預設值。 |
| `APP_BROADCAST_TEST_MODE` | `false` | 設為 `true` 時管理員廣播只模擬、不實際推送訊息；GitHub 變數留空會回到預設值。 |
| `TRANSLATION_ACTION_CLAIM_LEASE` | `PT5M` | 翻譯 action claim 處理中的租約；逾期仍未完成時由下一次點擊原子接手重試，需長於一次翻譯的最長耗時 |
| `MONGODB_CONNECT_TIMEOUT_MS` | `3000` | Mongo socket 連線 timeout |
| `MONGODB_READ_TIMEOUT_MS` | `5000` | Mongo socket 讀取 timeout |
| `MONGODB_SERVER_SELECTION_TIMEOUT_MS` | `3000` | 每次操作等待可用 Mongo 節點的上限 |
| `MONGODB_HEARTBEAT_FREQUENCY_MS` | `5000` | Mongo background topology recheck 間隔 |
| `MONGODB_MIN_HEARTBEAT_FREQUENCY_MS` | `500` | Mongo background recheck 的最小間隔 |
| `TRANSLATION_RECORD_RETENTION` | `P90D` | `translation_records` 保留期（ISO-8601 duration）。設為 `PT0S` 停用；GitHub 變數留空會回到預設值。 |
| `TRANSLATION_ACTION_CLAIM_RETENTION` | `P7D` | `translation_action_claims` 保留期；超過後同一按鈕再次點擊會視為新操作。 |
| `AI_USAGE_EVENT_RETENTION` | `P730D` | `ai_usage_events` 保留期，約 24 個月；用量與成本報表只涵蓋仍保留的事件。 |
| `MINIO_ENABLED` | `true` | 是否啟用 optional MinIO 圖片儲存 |
| `MINIO_RETRY_INTERVAL_MS` | `30000` | MinIO outage 後再次 probe 的間隔 |
| `MINIO_CONNECT_TIMEOUT_MS` | `3000` | MinIO 連線 timeout |
| `MINIO_WRITE_TIMEOUT_MS` | `5000` | MinIO 上傳 timeout |
| `MINIO_READ_TIMEOUT_MS` | `5000` | MinIO API 讀取 timeout |
| `LINE_PUSH_FALLBACK_ENABLED` | `true` | reply token 過期時是否改用 push 傳送答案；push 消耗官方帳號每月訊息額度，reply 不消耗 |
| `LINE_PUSH_FALLBACK_DAILY_LIMIT` | `100` | 每日（UTC+8）最多備援 push 次數，重啟後歸零；可用 `/admin quota` 查看額度與今日用量 |
| `OCR_ENABLED` | `true` | 圖片翻譯的總開關（沿用這個名稱）的部署預設值；設為 `false` 則不啟動 image-translator，圖片訊息回「圖片翻譯功能目前已停用」，部署也不再需要 `GOOGLE_CREDENTIALS_JSON` 與 `ANTHROPIC_API_KEY`。管理員可用 `/admin config ocr [on\|off]` 動態切換。GitHub 變數留空會回到預設值。 |
| `GOOGLE_VISION_TIMEOUT_MS` | `60000` | 已不再使用：Java 端的 Vision OCR 已移除（image-translator 自己呼叫 Vision）。部署腳本仍會傳入，下次整理 workflow 時移除 |

### MongoDB 資料保留

應用程式啟動完成後，`RetentionIndexInitializer` 為 `translation_records`（索引 `translation_record_created_at`）、`translation_action_claims`（`translation_action_claim_ttl`）與 `ai_usage_events`（`ai_usage_event_ttl`）建立或更新 TTL index，重複啟動不會改變結果；MongoDB 約每分鐘清除一次過期文件。已存在的同鍵非 TTL 索引（例如統計查詢索引）在 MongoDB 5.1 以上以 `collMod` 就地轉為 TTL，較舊版本會改為刪除後重建；CI 使用 `mongo:7`。

保留期設為 `PT0S`（環境變數留空亦同）只代表不再建立或更新索引，已建立的 TTL index 不會被移除，需要時請手動 `dropIndex`。數值無法解析或建立失敗只記錄 WARN，不影響啟動。`webhook_event_receipts` 沿用 `WEBHOOK_RECEIPT_TTL`；`user_profiles` 與 `runtime_settings` 不設保留期。

### 同機木雷連線

Translate production 預設使用 `DOCKER_NETWORK=host`，因此不能用 `dwz-server:8080` 解析
`1panel-network` 內的容器。二選一：

1. 使用木雷公開 HTTPS domain 作為 `DWZ_API_BASE_URL`。
2. 把木雷 Compose 的隨機 host port `- "8080"` 改為未使用的固定
   loopback port。例如 host `18080` 對應 container `8080`：

   ```yaml
   ports:
     - "127.0.0.1:18080:8080"
   ```

   Translate 設定 `DWZ_API_BASE_URL=http://127.0.0.1:18080`。改前可用
   `ss -ltnp | grep :18080` 確認 port 未被佔用。

木雷需先在後台建立 Bearer API Token。`DWZ_SHORT_DOMAIN` 可留空使用預設
domain。啟用指令：`/admin config short-url on`；停用使用 `off`。

## 圖片管理頁

使用者原圖會永久保留在 MinIO，不會自動刪除。設定 Secret `ADMIN_WEB_TOKEN`（建議 `openssl rand -hex 32`）後，開啟 `https://<host>/admin/images`，在登入欄輸入該 token（只存在該分頁的 `sessionStorage`，不會出現在 URL），即可依類型與日期篩選、預覽並批次刪除圖片。所有 `/admin/images/api/*` 請求都必須帶 `Authorization: Bearer <token>`，錯誤時回 `401`；頁面本身只是不含任何資料的靜態外殼（瀏覽器導覽無法附加 Authorization header），因此不需要 token。未設定或留空時不會註冊任何 `/admin/images` 路由（回 `404`）。更換 token 需重新部署；請經由 HTTPS reverse proxy 對外提供，並避免把 token 貼進 Issue、PR 或日誌。

## 手動部署

伺服器已有 `.env`、`linebot.json` 且已登入 GHCR 時，可在 checkout 根目錄執行：

```bash
./run-on-server.sh
```

自動部署的實際入口是 `scripts/deploy.sh`；`docker-compose.yml` 只保留為手動除錯用途。兩者啟動的都是同一個容器（Java bot 與 image-translator 兩個程序）；`run-on-server.sh` 讀 `.env` 時，`ANTHROPIC_API_KEY`（`OCR_ENABLED` 為 `true` 時必填）與其他選填設定也寫在 `.env`，名稱見上文「單一容器」的設定表。
