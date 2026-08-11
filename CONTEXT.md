# LINE Bot Translator：Domain Context

> 狀態：Accepted（maintainer 於 2026-08-11 確認）。

本文件是程式碼、文件與後續 Issue 共用的 ubiquitous language。新功能應先使用這裡的詞彙；若產品語意改變，先更新本文件與對應 ADR，再修改 Implementation。

## 產品邊界

本系統接收 LINE webhook，把文字、圖片、命令或 postback 轉成明確 intent，執行翻譯或管理操作，再以 LINE message 回覆。MongoDB 是必要的 durable state；MinIO 與 Google Cloud Vision 可 disabled/degraded；OpenRouter 是唯一 AI Provider，其設定狀態直接影響翻譯 readiness。

目前產品範圍包含文字翻譯、指定目標語言、圖片 OCR/AI 辨識後翻譯、翻譯結果 actions、個人翻譯偏好、管理員卡片、runtime settings、使用量／成本報表與可靠 webhook ingestion。

下列項目明確不在目前範圍，不應從本文件推導為待實作功能：

- 個人資料匯出與刪除
- 語音翻譯與朗讀
- 翻譯評分與修正
- 成本／品質自動路由
- 使用者與群組 quota
- 語言學習模式
- 群組共享詞彙表
- Self-hosted local model Adapter
- LIFF 個人設定入口

## 核心 domain 名詞

| Canonical term | 定義 | 不代表 |
| --- | --- | --- |
| **LINE Interaction** | 一次 LINE 文字、圖片或 postback 輸入及其回覆。 | 不等同一次 AI 呼叫；language detection 與 translation 可各自執行一次 Provider Attempt。 |
| **LINE Intent** | `LineIntentParser` 從文字或 postback 產生的結構化意圖，例如一般翻譯、快速翻譯、使用者命令或管理員命令。 | 不是原始 LINE payload，也不是可任意執行的字串。 |
| **Admin Intent** | `AdminIntentParser` 驗證後的管理操作。敏感操作執行前必須重新授權。 | 不是「解析成功即已授權」。 |
| **Translation Request** | 已正規化、可進入共用 workflow 的翻譯需求；包含 User Profile、原文、可選目標語言、可選單次 style preset、request kind 與開始時間。 | 不是 provider-specific request body。 |
| **Translation Result** | 成功 workflow 的結果；包含來源／目標語言、實際 provider/model、實際 style/version、token metadata、latency 與翻譯文字。 | 不只是一段翻譯後字串。 |
| **Translation Action** | 從 Translation Result 建立的複製、換目標語言、換風格或重新翻譯操作；provider action 只攜帶 opaque Translation Record ID 與 allowlisted target/style，執行時重新驗證 owner 並先取得 durable claim。 | 不可把原文／譯文放進 postback data，也不代表重複點擊可重複計費。 |
| **Translation Style Preset** | 具有穩定 ID、本地化名稱與版本化 provider prompt rule 的 allowlisted 翻譯風格；目前包含 faithful、natural、casual、formal、business、subtitle。 | 不是可由使用者注入的任意 system prompt，也不是成本／品質路由。 |
| **Translation Workflow** | `TranslationWorkflowModule` 擁有的單次流程：Language Detection → resolve User Preferences → 選目標語言 → provider execution → 成功持久化。 | 不包含 LINE webhook 驗簽或 LINE message rendering。 |
| **Translation Request Kind** | 翻譯來源類型：一般文字、快速文字、批次文字或圖片 OCR。 | 不等同 Usage Event 的 TEXT/IMAGE 維度。 |
| **Language Detection** | 判定 Translation Request 的 source language；可由規則與 AI detection 組合。 | 不決定 provider，也不直接寫入使用者偏好。 |
| **Source Language** | 原文被偵測出的語言。 | 不是使用者希望的輸出語言。 |
| **Target Language** | 本次 Translation Request 實際要輸出的語言；可由命令指定或由 User Preferences 推導。 | 不一定等於全域 fallback。 |
| **User Profile** | MongoDB 中的使用者 identity、LINE profile metadata、活動時間與翻譯計數。 | 不應被當成所有設定規則的 Interface。 |
| **User Preferences** | `UserPreferencesModule` 對外提供的 immutable effective preferences：target language、中文目標語言、OpenRouter model、預設 Translation Style Preset 與最近語言。 | 不包含 API keys，也不等同原始 `UserProfile` document。 |
| **Runtime Settings** | 管理員可動態變更、MongoDB versioned persistence 的非敏感全域設定；讀取失敗時使用 deployment defaults。 | 不可保存 credential、token、API key 或 connection secret。 |
| **AI Provider** | 應用程式直接串接的外部 AI gateway；目前且唯一為 OpenRouter。 | OpenRouter catalog 內的模型廠商不是應用程式的直接 provider。 |
| **Provider Adapter** | `AiProviderAdapter` Interface 的唯一 Implementation `OpenRouterService`，封裝 Chat Completions request/response/error。 | 不包含 Google Cloud Vision OCR，也不在 caller 暴露 wire payload。 |
| **AI Model Catalog** | `AiModelCatalog` 提供的 OpenRouter model/capability/pricing snapshot，具有 TTL cache 與 stale fallback。 | 不是寫死的模型 allowlist，也不包含非文字輸出模型。 |
| **Model Selection** | 依 User Preferences → Runtime Settings → deployment default 決定本次 OpenRouter model，並以 catalog 驗證 capability。 | 不是成本／品質自動路由，也不會跨 provider fallback。 |
| **Provider Attempt** | 對 OpenRouter 的一次實際 operation，具有 status、outcome、model 與 latency。 | 不等同整體 Translation Result；一次 workflow 可有 detection 與 translation 等不同 operation。 |
| **AI Execution Outcome** | provider execution Module 對 caller 的 normalized Success 或 Failure。 | Caller 不應解析第三方 exception message 決策。 |
| **Translation Cache** | 以內容 hash、target、provider/model、style、glossary 與 prompt version 建立 identity 的 bounded Caffeine cache。 | 不是產品「翻譯記憶」或永久歷史；failure 不會寫入。 |
| **Translation Record** | 成功 Translation Workflow 的 durable product record；包含原文、譯文、語言、實際 provider/model、實際 style/version、時間與圖片 storage metadata。 | 不可用來推算精確 token/cost。 |
| **Image Translation** | LINE image download → optional storage → OCR 或 AI recognition → 共用 Translation Workflow。 | 圖片存檔失敗不等於圖片翻譯必須失敗。 |
| **Image Storage Result** | MinIO storage 的 stored/not-stored 結果與可選 URL。 | 不可假設每張圖片都有 URL。 |
| **Usage Event** | 每個 Provider Attempt 的 privacy-minimized accounting event，保存 operation、provider/model、status、latency、token、pricing snapshot 與 cost。 | 不保存 user ID、原文、譯文、correlation ID、provider payload 或 secret。 |
| **Pricing Snapshot** | Usage Event 發生時套用的 pricing version、effective date、currency 與 cost。 | 歷史報表不應用最新價格重算。 |
| **Usage Report** | MongoDB aggregation 產生的期間／provider／model／content kind 統計。 | 不應把全部 Translation Record 載入記憶體。 |
| **Webhook Receipt** | 以 `webhookEventId` 建立的 durable TTL processing receipt，保存 claim/status/attempt metadata。 | 不保存訊息內容或 reply token。 |
| **Webhook Claim** | worker 對 receipt 取得的處理租約，避免 redelivery 重複執行 business operation。 | 不代表 LINE 已收到 reply。 |

## 核心不變量

1. LINE webhook 必須先驗證 signature，才可建立 receipt 或處理 event。
2. 同一 `webhookEventId` 不可重複執行 business operation；queue 滿或 receipt store 暫時不可用時回 `503`，交由 LINE redelivery。
3. `LineBotController`、`AdminController` 與 webhook controller 是 Adapter；intent grammar、business execution 與 message assembly 不回流 Controller。
4. Admin Intent 的授權必須發生在任何敏感 `AdminService` 呼叫前；每次 interaction 都重新檢查。
5. Translation Workflow 只消費 immutable effective User Preferences；其他 Module 不直接推測 `UserProfile` 欄位 fallback。
6. Provider caller 只依 normalized outcome 決策；成功結果與 Translation Record 必須使用 OpenRouter 實際回傳的 provider/model metadata，不可用 requested/default 值代替。
7. 只有成功 Translation Workflow 才建立 Translation Record 並增加成功翻譯計數；失敗不可留下假成功紀錄。
8. Translation Cache 只保存可安全重用且 model identity 一致的成功；failure、safety blocked 與 model mismatch 不寫入。
9. Image Translation pipeline 不在 singleton/thread field 保存 request state；MinIO 是 optional side effect，storage degraded 時仍可繼續 OCR/翻譯。
10. MinIO 內部 S3 操作使用 `MINIO_ENDPOINT`；外部 presigned URL 由 `MINIO_PUBLIC_ENDPOINT` 獨立簽署，避免流量被迫繞過 reverse proxy。
11. Runtime Settings 只保存 allowlisted 非敏感欄位，具有 schema version、revision、operator 與 timestamp；Mongo 讀取失敗時回 deployment defaults。
12. 每個 Provider Attempt 產生一個 Usage Event；app 不做跨 provider fallback；accounting failure 採 fail-open，不可改變 provider outcome。
13. Usage Event 與 logs 遵守 data minimization；credential、使用者原文、OCR 結果、signed URL 與第三方完整 payload 不得記錄。
14. Translation Action 必須以 `recordId + userId` 驗證 ownership；同一來源紀錄與 target 的 durable claim 只允許一次 Provider Attempt。
15. Translation Style 只接受 catalog 內的 stable ID；單次 style 不可修改使用者預設，無效或已移除的 stored style 回退 faithful。

## 端到端流程

### 文字與命令

1. `LineWebhookIngestionController` 驗證 signature，交給 `WebhookIngestionModule` claim/dispatch。
2. `LineWebhookEventProcessor` 把 LINE SDK event 送到 `LineBotController` Adapter。
3. `LineIntentParser` 將一般文字、快速翻譯、命令或 postback 轉成 LINE Intent。
4. `LineInteractionModule` 執行 intent；一般翻譯進入 `TranslationService` 與 `TranslationWorkflowModule`，管理命令進入 `AdminInteractionModule`。
5. `LineMessageRenderer` 或 `AdminCardRenderer` 將結果轉成 LINE message。
6. reply Adapter 傳送結果；成功或 poison 狀態寫回 Webhook Receipt。

### Translation Workflow

1. 驗證 Translation Request。
2. 執行 Language Detection。
3. 由 `UserPreferencesModule` resolve effective preferences。
4. 以 explicit target language 或 preferences 決定 Target Language；以單次 preset 或 preferences 決定實際 Translation Style Preset。
5. `CachedTranslationAdapter` 以實際 style ID/version 查安全 cache identity；miss 時呼叫 `AiProviderExecutionModule`。
6. provider execution 經 Model Selection 選出一個 OpenRouter model，執行單一 Provider Attempt 並寫 Usage Event。
7. 成功時建立 Translation Result、Translation Record，更新 User Profile 活動與最近語言；LINE renderer 可用 record ID 建立安全 Translation Actions；失敗回 normalized failure。

### Image Translation

1. 從 LINE blob API 下載圖片。
2. 嘗試 MinIO storage；失敗只產生 not-stored result。
3. 優先使用 configured OCR；不可用時使用 AI Provider image operation。
4. 無可辨識文字時回明確 failure stage；有文字時進入同一 Translation Workflow。
5. Translation Record 只在成功時保存 image URL/storage state；沒有 storage URL 仍可成功翻譯。

### 管理員互動

1. raw command 經 `AdminIntentParser` 產生 Admin Intent 並完成參數驗證。
2. `AdminInteractionModule` 先呼叫 `isAdmin`；未授權立即回 access-denied card。
3. 已授權才執行 stats、broadcast、user、settings 或 usage operation。
4. 所有結果由 `AdminCardRenderer` 產生 Flex card，必要時安全降級為截斷純文字。

## Module 與 Seam

| Module | Interface / input | Implementation responsibility | Depth / Leverage / Locality |
| --- | --- | --- | --- |
| Webhook ingestion | `WebhookIngestionModule.ingest(Event)` | claim、bounded dispatch、poison、reply retry | 將可靠性集中在單一 Seam，所有 LINE event 共用。 |
| LINE interaction | `LineIntent` / `AdminIntent` | parse、validate、authorize、execute、render | Controller 保持淺薄 Adapter；新增互動不擴張 Controller switch。 |
| Translation workflow | `TranslationWorkflowRequest` → `TranslationWorkflowOutcome` | detection、preferences、target、execution、persistence | 文字與圖片共用不變量，避免兩條流程漂移。 |
| User preferences | `UserPreferencesModule` → `UserPreferences` | precedence、validation、persistence、recent languages | 外部只看 immutable Interface，fallback Locality 留在 Module。 |
| Provider execution | `AiProviderAdapter`、`AiExecutionOutcome` | model validation、normalized errors、actual metadata、usage attempt | OpenRouter wire 差異留在唯一 Adapter Implementation。 |
| AI model catalog | `AiModelCatalog` → `AiModelPage` | discovery、capability/pricing parsing、TTL/stale cache、bounded search | 模型變動集中在 catalog Seam；LINE 與 admin 不直接呼叫 provider API。 |
| Runtime settings | `RuntimeSettingsSource` → `RuntimeSettings` | allowlist、versioned persistence、fallback、audit metadata | admin 與 consumers 共用一個 non-secret Seam。 |
| Usage accounting | `AiUsageEventSink`、`UsageQuery` | attempt event、pricing snapshot、Mongo aggregation | accounting fail-open；report 不依賴 Translation Record 掃描。 |
| Image translation | `ImageTranslationRequest` → `ImageTranslationOutcome` | download、storage、recognition、workflow bridge | request state 為 explicit value，optional storage failure 局部化。 |

## Durable data ownership

| Collection | Owner | 可保存 | 不可保存／不可假設 |
| --- | --- | --- | --- |
| `user_profiles` | User Preferences / profile services | LINE profile metadata、preferences、活動與計數 | API secrets；其他 Module 不直接重建 preference precedence。 |
| `translation_records` | Translation Workflow | 成功翻譯內容、語言、actual provider/model、actual style/version、時間、image storage metadata | 精確 token/cost；失敗 attempt。 |
| `translation_action_claims` | Translation Action Module | hashed action ID、owner、來源／結果 record ID、target、style ID、status、timestamps | 原文、譯文、LINE payload、postback data、provider payload 或 secret。 |
| `runtime_settings` | Runtime Settings Module | allowlisted settings、schema/revision、operator/time | credential、token、API key、connection secret。 |
| `ai_usage_events` | Usage Accounting Module | provider attempt metadata、token、pricing/cost snapshot | user ID、原文、譯文、correlation ID、secret。 |
| `webhook_event_receipts` | Webhook ingestion | event ID、status、claim/attempt/time metadata | LINE message content、reply token。 |

## Maintainer 確認清單

- [x] 「User Profile」只代表 identity/activity document；effective 設定統稱「User Preferences」。
- [x] 管理員可動態變更的全域非敏感設定統稱「Runtime Settings」。
- [x] 「Translation Record」是含內容的產品歷史；「Usage Event」是無個資的 provider-attempt accounting，兩者不可互換。
- [x] 「Model Selection」只代表 preference/default precedence 與 capability validation；不代表成本／品質自動路由或跨 provider fallback。
- [x] 上列九項排除功能仍不在目前產品範圍。

本清單已由 maintainer 確認；未來語意變更以 ADR 記錄。
