# ADR-0017: Translate images through the image-translator service

- Date: 2026-10-21

## Status

Accepted

## Context

Java 端的圖片管線（Google Vision OCR、區塊切分與資格判斷、方向與直書偵測、直書排版、覆寫圖片的渲染器與其安全政策）是 ADR-0009 的主要內容。它只能在原圖上蓋文字，遇到海報、手寫、菜單或低品質照片就保守地放棄，也沒有辦法用圖片模型重畫整張圖或驗證結果。`services/image-translator`（Python）已經能做這些：Mode A（Vision 字框 → Claude 規劃 → 擦除 → 繪製）、Mode B（圖片模型）、judge 品質判定、A 再 B 的策略、每月預算與一份固定的 `/v1` JSON 契約。部署上它與機器人放在同一個容器、只綁 `127.0.0.1`（`IMAGE_TRANSLATOR_URL`），以 `X-Service-Token` 認證。

## Decision

- 機器人下載並驗證 LINE 圖片、存下原圖（gallery 用），之後的辨識、翻譯、繪製與判定全部交給服務：`ImageTranslatorClient`（OkHttp，自有 instance：連線 5 秒、讀寫逾時 `IMAGE_TRANSLATOR_TIMEOUT`，預設 4 分鐘）送 multipart `POST /v1/translations`，目標語言是使用者的偏好語言，`request_id` 是 LINE message id；有圖時再 `GET` 服務給的 `image.path`。服務重複收到同一個 `request_id` 回 409，機器人回通用失敗、不再翻一次。
- 回覆只有圖片。`tier: warned` 時圖片後多一則警語；`text_only` 改回譯文；`no_text`、`error`（額度用完與其他原因）與服務的 HTTP 失敗各有自己的回覆。圖片取不回、太大（先改 JPEG）或存不進 storage 時，只要服務給了譯文就退回文字。圖片的文字回覆（`imageTextReplyEnabled`）預設關閉，管理員仍可開。
- `OCR_ENABLED` 與管理員的 `ocrEnabled` 維持為圖片翻譯的總開關。圖片翻譯的 Translation Record 帶服務回報的 mode、tier、verdict、usability 與費用；管理員統計顯示本月費用。
- `imageTranslator` health indicator（`GET /healthz`，3 秒）只放在 liveness 群組：服務停擺不應讓機器人停止回覆文字訊息，所以不在 readiness。圖片翻譯開啟時，正式環境必須有 `IMAGE_TRANSLATOR_URL` 與 `IMAGE_TRANSLATOR_TOKEN`（令牌由容器 entrypoint 產生）。
- Java 端的 Vision OCR、區塊切分、方向與直書偵測、覆寫渲染與其設定、`google-cloud-vision` 相依套件一併移除，不留註解掉的版本。ADR-0009 中不屬於 OCR 與覆寫的部分（explicit、stateless 的 request／result，thread interruption 保留，storage 為 optional side effect）繼續適用於新的 `ImageTranslationPipeline`。

## Consequences

### Positive

- 圖片品質的演進（模型、版面、判定）在服務裡進行，不必再改 Java；Java 端只剩一個客戶端、一條對應表與回覆。
- 費用與預算由服務統一計算，機器人只記錄與顯示。
- 服務停擺只影響圖片翻譯；文字翻譯不受影響。

### Negative / trade-offs

- 一次圖片翻譯最久約 4 分鐘（A 再 B），仍落在 webhook 5 分鐘的處理租約內，但使用者要等；reply token 會過期，回覆多半走 push 備援。
- 機器人與服務必須同一個容器發佈；令牌錯誤或服務沒起來時圖片翻譯回「服務暫時無法使用」。
- 圖片的結果不經共用 Translation Workflow，翻譯快取與 AI usage event 不涵蓋它（費用以 Translation Record 的 `imageCostUsd` 記錄）。

## Alternatives considered

- 保留 Java 覆寫管線作為服務失敗時的備援：拒絕，兩套管線要同時維護，且備援結果品質與主要路徑差距過大。
- 服務與機器人分成兩個容器：拒絕，部署與網路邊界變複雜；擁有者要一個映像、一個容器。
- 把服務放進 readiness：拒絕，會讓一個選配功能的故障擋下所有文字訊息。

## Related

- ADR-0006、ADR-0009（部分取代）、ADR-0016
- `services/image-translator/README.md`（`/v1` API 契約）
