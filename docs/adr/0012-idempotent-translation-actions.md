# ADR-0012: Idempotent Translation Actions

## Status

Accepted

## Context

LINE Quick Reply 可讓使用者複製譯文、改目標語言或重新翻譯，但 postback data 上限為 300 字元且不應攜帶原文或譯文。Webhook receipt 只能阻止同一 webhook event 的 redelivery；使用者重複點擊會產生不同 event，若直接執行將建立重複紀錄與不可控 provider 成本。

## Decision

可再次翻譯的 action 只傳 allowlisted command、opaque Translation Record ID 與 target language。執行前使用 `recordId + userId` 查詢 Translation Record 以驗證 ownership，再以 `SHA-256(userId, sourceRecordId, targetLanguage)` 作為 `translation_action_claims` 的 deterministic ID，使用 Mongo `_id` 唯一性在呼叫 provider 前取得 durable claim。

Claim 只保存 owner、來源／結果 record ID、target、status 與 timestamps，不保存翻譯內容或 LINE payload。重複 action 若已完成則重用既有結果；處理中或失敗狀態不再次呼叫 provider。

Clipboard action 只在譯文符合 LINE 的 1,000 字元限制時加入。所有 client 都繼續收到完整 TextMessage，未支援 Quick Reply 的 client 不失去主要結果。

## Consequences

- Postback 不暴露翻譯內容，猜測其他 record ID 也會被 owner query 拒絕。
- 同一結果與 target 最多產生一次 Provider Attempt；需要再次翻譯時使用新結果產生的新 action。
- Provider 成功但 claim completion 寫入失敗時，claim 保持 processing，優先避免重複成本而非自動重試。
- 新增小型 durable metadata collection；資料量與使用者實際點擊的結果 actions 成正比。
- LINE Clipboard 超限時不提供不完整複製；完整文字 fallback 仍存在。
