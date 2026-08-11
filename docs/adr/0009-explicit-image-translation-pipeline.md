# ADR-0009: Use an explicit stateless image translation pipeline

- Date: 2026-08-11

## Status

Accepted

## Context

圖片下載 bytes、storage URL、OCR text 與 translation state 若存在 singleton/ThreadLocal，concurrent LINE events 可能互相污染。MinIO 或 Google Vision failure 也不應被模糊成單一 generic exception。

## Decision

`ImageTranslationPipeline` 使用 immutable/explicit request、downloaded image、storage result、context、failure stage 與 outcome。Pipeline 不在 thread 或 singleton field 保存 request state。MinIO storage 是 non-blocking side effect；configured OCR 不可用時可 fallback 到 AI image recognition；辨識成功後進入 shared Translation Workflow。

Thread interruption 必須保留，LINE/OCR streams 必須關閉。

## Consequences

### Positive

- Concurrent request data 保持隔離，failure stage 可測試與穩定呈現。
- Storage/OCR degraded 不必阻止可完成的翻譯。

### Negative / trade-offs

- Pipeline 需要較多 typed value objects。
- 每個新增 stage 都要定義 failure policy 與 resource lifecycle。

## Alternatives considered

- 使用 ThreadLocal 傳遞中間 state：拒絕，async/concurrent execution 容易洩漏或錯配。
- MinIO/OCR 任一失敗即終止：拒絕，optional capability 不應破壞核心流程。

## Related

- Issue #19
- PR #65
