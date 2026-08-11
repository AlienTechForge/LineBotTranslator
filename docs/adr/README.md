# Architecture Decision Records

ADR 記錄已確立、會限制後續設計的決策。Domain 名稱與不變量以 [CONTEXT.md](../../CONTEXT.md) 為準。

## Workflow

1. 從 [0000-template.md](0000-template.md) 複製新檔，使用下一個四位數編號。
2. `Status` 從 `Proposed` 開始；合併代表 `Accepted`。
3. 決策被取代時不刪檔，改為 `Superseded by ADR-NNNN`。
4. ADR 記錄「為何與取捨」；Implementation 細節留在 code/tests。

## Index

| ADR | Status | Decision |
| --- | --- | --- |
| [0001](0001-provider-execution-module.md) | Superseded | 原多 provider/fallback 決策，由 ADR-0011 取代。 |
| [0002](0002-durable-webhook-ingestion.md) | Accepted | 以 Mongo receipt 與 bounded executor 提供可靠 webhook ingestion。 |
| [0003](0003-persisted-runtime-settings.md) | Accepted | 非敏感 runtime settings 採 versioned persistence 與 deployment fallback。 |
| [0004](0004-provider-attempt-usage-accounting.md) | Accepted | 使用每次 provider attempt 的 usage event 與 pricing snapshot。 |
| [0005](0005-line-intent-rendering-seam.md) | Accepted | 集中 LINE intent parsing、authorization 與 message rendering。 |
| [0006](0006-required-and-optional-dependencies.md) | Accepted | MongoDB 為必要依賴；MinIO/OCR/AI 能力可 disabled/degraded。 |
| [0007](0007-shared-translation-workflow.md) | Accepted | 文字與圖片共用單一 Translation Workflow。 |
| [0008](0008-effective-user-preferences.md) | Accepted | 以 immutable effective User Preferences 集中 precedence 與 validation。 |
| [0009](0009-explicit-image-translation-pipeline.md) | Accepted | 圖片 pipeline 使用 explicit request/result，移除 ThreadLocal state。 |
| [0010](0010-model-aware-bounded-cache.md) | Accepted | 翻譯 cache 有界、model-aware，且只保存安全成功。 |
| [0011](0011-openrouter-single-provider-adapter.md) | Accepted | 只保留 OpenRouter Adapter，模型由 catalog 動態探索與選擇。 |
| [0012](0012-idempotent-translation-actions.md) | Accepted | 翻譯結果 actions 使用 opaque record reference、owner check 與 durable claim。 |
