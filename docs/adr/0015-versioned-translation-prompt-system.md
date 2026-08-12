# ADR-0015: Versioned Translation Prompt System

## Status

Accepted

## Context

圖片翻譯曾只把 raw `zh-TW` 放進 user JSON，system prompt 未描述 target script；文字、圖片 OCR 與語言偵測 prompt 也散落於 service。模型可輸出簡體而通過 schema，圖片 region 又缺少版面與群組上下文。

## Decision

所有 AI Prompt 由 server-owned `TranslationPromptFactory` 產生，禁止 caller 拼接 system prompt。文字翻譯、圖片 structured 翻譯、OCR extraction 與 language detection 使用各自獨立、版本化模板。

翻譯模板用具名 placeholder 注入 canonical BCP 47 target locale、英文語言名稱、required script、locale rule、style ID/version/rule。`zh-TW` 明確要求 Traditional Chinese、Taiwan orthography/terminology 並禁止 Simplified Chinese；`zh-CN` 採對稱規則。

圖片 request 使用 `image-regions-v2`，提供 reading order、group identity、geometry、max lines、max characters 與 compact-label hint。模型可用整張圖片內的 regions 作語意上下文，但必須維持 exact Region ID mapping。回應除 schema/protected-token 驗證外，還需通過 target-locale script validator；不合規只允許一次 repair，失敗後安全降級且不得 cache。

Prompt 或 schema 行為改變必須 bump version；cache identity 必須包含文字／圖片 prompt version 與 structured schema version。

## Consequences

- `zh-TW` 與 `zh-CN` 有明確且對稱的 script/locale 契約。
- 圖片模型可使用同圖上下文，但不能破壞 Region ID mapping。
- 不合 target locale 的 structured response 不會 cache，最多 repair 一次。
- Prompt catalog、schema 與相關 contract tests 必須一起維護。
