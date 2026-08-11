# ADR-0007: Share one translation workflow across request kinds

- Date: 2026-08-11

## Status

Accepted

## Context

一般文字、快速翻譯、批次文字與圖片 OCR 曾各自執行 language detection、target selection、provider call、persistence 與 counters。重複流程使相同產品規則產生不同結果，failure 也可能留下部分 state。

## Decision

建立 `TranslationWorkflowModule`，接受 presentation-neutral `TranslationWorkflowRequest`，回傳 typed `TranslationWorkflowOutcome`。Module 集中一次 Language Detection、effective User Preferences、Target Language、provider execution、成功 Translation Record、User Profile counters 與 recent activity。

不同入口只負責正規化 request；圖片與文字不各自重建 domain workflow。只有 success 寫入 record/counters，failure 不留下假成功 state。

## Consequences

### Positive

- 文字、快速、批次與圖片共享相同不變量。
- Provider result 與 persistence 可用一組 contract tests 對齊。

### Negative / trade-offs

- Request kind-specific metadata 必須在 typed request 明確表示。
- 新入口需先轉換為 workflow request，不能直接呼叫 repository。

## Alternatives considered

- 每種入口保留獨立 service：拒絕，規則、計數與錯誤處理會漂移。
- 把全部行為塞入 `TranslationService`：拒絕，Interface 過寬且缺乏 presentation-neutral outcome。

## Related

- Issue #17
- PR #62
