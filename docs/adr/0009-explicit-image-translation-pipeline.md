# ADR-0009: Use an explicit stateless image translation pipeline

- Date: 2026-08-11

## Status

Accepted

## Context

圖片下載 bytes、storage URL、OCR text 與 translation state 若存在 singleton/ThreadLocal，concurrent LINE events 可能互相污染。MinIO 或 Google Vision failure 也不應被模糊成單一 generic exception。

## Decision

`ImageTranslationPipeline` 使用 immutable/explicit request、downloaded image、storage result、context、failure stage 與 outcome。Pipeline 不在 thread 或 singleton field 保存 request state。MinIO storage 是 non-blocking side effect；configured OCR 不可用時可 fallback 到 AI image recognition；辨識成功後進入 shared Translation Workflow。

Located OCR 必須保留 stable Region ID、provider vertex order、word/symbol polygons、known confidence、block type 與 detected languages。只有 centralized qualification policy 判定為 `TRANSLATE` 的 Region 可進入 versioned structured translation contract；`PRESERVE`／`REJECT` 不得清除原圖。Provider 回應以 exact Region ID set 驗證，禁止以換行或 list index 猜配。

Google Vision（configured OCR）負責 located geometry/language metadata；使用者選定的 OpenRouter Translation Model 只負責翻譯，不被當成 OCR geometry 或獨立 language detector 的來源。沒有 located Regions 的 AI recognition fallback 僅提供純文字翻譯。

Overlay 採 fail-closed。Centralized safety policy 在 renderer 前檢查 kill switch、confidence、geometry、單框／總 mask coverage 與 overlap；renderer 再以 word-polygon union、local rotation transform 與具 glyph coverage 的 Noto font 修改 mask 內像素。任何 mapping、policy、font、renderer 或 translated-image storage 問題均降級為成功的純文字翻譯，不把不安全圖片標為成功。

Thread interruption 必須保留，LINE/OCR streams 必須關閉。

## Consequences

### Positive

- Concurrent request data 保持隔離，failure stage 可測試與穩定呈現。
- Storage/OCR degraded 不必阻止可完成的翻譯。
- OCR false positive、旋轉 bounds 或 provider 格式漂移不再破壞原圖。

### Negative / trade-offs

- Pipeline 需要較多 typed value objects。
- 每個新增 stage 都要定義 failure policy 與 resource lifecycle。
- Fail-closed 可能犧牲部分 overlay；安全時仍保留完整純文字結果。

## Alternatives considered

- 使用 ThreadLocal 傳遞中間 state：拒絕，async/concurrent execution 容易洩漏或錯配。
- MinIO/OCR 任一失敗即終止：拒絕，optional capability 不應破壞核心流程。

## Related

- Issue #19
- PR #65
- Issues #80–#85
