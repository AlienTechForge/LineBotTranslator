# ADR-0002: Use durable idempotent webhook ingestion

- Date: 2026-08-11

## Status

Accepted

## Context

LINE webhook 可能 redeliver、亂序或在 reply 階段失敗。同步處理與無界工作佇列會造成 timeout、重複翻譯或資源耗盡；只用記憶體去重則無法跨 restart。

## Decision

驗證 LINE signature 後，以 `webhookEventId` 建立 MongoDB TTL Webhook Receipt。`WebhookIngestionModule` 取得 claim 後送入 bounded executor；duplicate 不重做 business operation。Queue 滿或 receipt store 暫時不可用時回 `503` 讓 LINE redeliver。Business processing 與 reply retry 分離，poison outcome 持久化。

Receipt 僅保存 event ID、claim/status/attempt/time metadata，不保存 message content 或 reply token。

## Consequences

### Positive

- Restart 後仍可避免 duplicate business execution。
- Backpressure 明確；不以無界 queue 隱藏 overload。

### Negative / trade-offs

- MongoDB 是 webhook processing 的必要依賴。
- Delivery 是 idempotent processing contract，不宣稱跨 LINE/network 的理論 exactly-once delivery。

## Alternatives considered

- In-memory deduplication：拒絕，restart 會遺失狀態。
- 收到 event 立即 `200` 再盡力處理：拒絕，queue/store outage 會靜默遺失。

## Related

- Issue #20
- PR #64
