# ADR-0003: Persist versioned non-secret runtime settings

- Date: 2026-08-11

## Status

Accepted

## Context

管理員需在不中斷服務的情況下調整預設語言、OpenRouter default model 與 OCR flag。只改 singleton field 會在 restart 遺失；把任意環境設定寫入資料庫則可能擴大 secret exposure。

## Decision

以 `RuntimeSettingsModule` 與 `RuntimeSettingsSource` Seam 管理 allowlisted 非敏感設定。Mongo document 保存 schema version、revision、operator 與 timestamp；讀取／寫入使用 explicit typed fields，不用 reflection。啟動或 Mongo 讀取失敗時使用 deployment defaults。

Credential、token、API key 與 connection secret 永遠不進 Runtime Settings。

## Consequences

### Positive

- Admin 更新跨 restart 保留，consumers 共用一致 snapshot。
- Audit metadata 可追蹤變更來源，secret 邊界清楚。

### Negative / trade-offs

- 新 setting 需明確新增 key、validation、persistence 與 migration contract。
- Mongo 暫時失敗時可能使用 deployment defaults，health/logs 必須呈現 degraded state。

## Alternatives considered

- 直接修改 Spring bean field：拒絕，不 durable 且難驗證。
- 保存整份 environment：拒絕，會混入 secrets 與未治理欄位。

## Related

- Issue #15
- PR #67
