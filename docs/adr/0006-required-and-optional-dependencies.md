# ADR-0006: Distinguish required and optional runtime dependencies

- Date: 2026-08-11

## Status

Accepted

## Context

MongoDB、MinIO、OCR 與 AI providers 的 outage impact 不相同。把所有 dependency 失敗都當成 process startup failure，會讓 optional image storage/OCR 影響文字翻譯；全部忽略則會在必要 state 不可用時接收無法可靠處理的 traffic。

## Decision

MongoDB 是 required dependency，readiness 不可用時阻擋 traffic。MinIO 是 optional image-storage side effect，可為 disabled 或 degraded；storage failure 不阻止 OCR/translation。OCR 與 provider configuration 以 explicit health component 顯示 configured/disabled/degraded，AI 全部不可用時 readiness 反映 translation capability 不可用。

Production configuration validator 驗證必要 LINE/Mongo settings；health endpoint 與 startup summary 不輸出 secret。

## Consequences

### Positive

- Optional capability outage 被隔離，核心翻譯可持續運作。
- Deploy health gate 可根據真正必要的能力 rollback。

### Negative / trade-offs

- Health status 不只 UP/DOWN，維運需理解 disabled 與 degraded。
- 新 dependency 必須明確決定 required/optional 及其 failure policy。

## Alternatives considered

- 所有 dependency fail-fast：拒絕，MinIO outage 不應中止文字翻譯。
- 所有 dependency fail-open：拒絕，Mongo outage 會破壞 webhook idempotency 與 durable state。

## Related

- Issue #5
- PR #56
- `docs/line-sdk-10-migration.md`
- `docs/logging-data-policy.md`
