# ADR-0005: Centralize LINE intent parsing and message rendering

- Date: 2026-08-11

## Status

Accepted

## Context

文字命令、管理員命令、一般翻譯與 postback 若直接在 Controller switch 中解析，驗證、授權與可見訊息會分散。新增互動會持續擴張 LINE SDK Adapter，並可能在敏感 operation 前漏掉 permission check。

## Decision

`LineIntentParser` 與 `AdminIntentParser` 將 raw input 轉成 typed intent 並集中驗證。`LineInteractionModule` 與 `AdminInteractionModule` 執行 intent；Admin Module 在任何敏感 service call 前重新授權。`LineMessageRenderer` 與 `AdminCardRenderer` 集中產生 LINE messages。

Controller 僅作 LINE event/message 與 domain intent/result 的 Adapter。Postback 只接受 `command=` allowlist 並共用文字命令 grammar。

## Consequences

### Positive

- Text/postback 行為一致，parser/renderer 可用 contract tests 固定。
- Controller Interface 變小，permission invariant 的 Locality 提升。

### Negative / trade-offs

- 新命令需要同時定義 intent、parser contract、execution 與 renderer contract。
- Renderer 仍須遵守 LINE Flex/alt-text/payload 限制。

## Alternatives considered

- 保留 Controller switch：拒絕，解析與 message assembly 會繼續擴張。
- Postback 直接執行任意 data：拒絕，缺少 allowlist 與一致驗證。

## Related

- Issue #21
- PR #69
