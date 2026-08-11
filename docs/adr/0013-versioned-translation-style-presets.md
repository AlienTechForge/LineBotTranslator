# ADR-0013: Versioned Translation Style Presets

## Status

Accepted

## Context

翻譯風格會改變 provider output。若只將風格當顯示文字或 deployment variable，使用者偏好、單次操作、cache identity 與歷史紀錄可能各自漂移；允許任意 prompt 又會形成 system prompt injection 邊界。

## Decision

以 `TranslationStylePreset` allowlist 定義 stable ID、本地化名稱、prompt version 與 server-owned prompt rule。預設為 `faithful`；目前 catalog 為 faithful、natural、casual、formal、business、subtitle。無效、舊版或已移除的 stored ID 在讀取時安全回退預設；寫入時只接受現有 stable ID。

User Preferences 保存個人預設 ID。Translation Request 可攜帶 request-scoped preset ID，優先於個人預設，但不得修改 User Profile。實際使用的 preset ID/version 進入 OpenRouter system instruction、Translation Cache identity、Translation Result 與 Translation Record。

LINE `/style` 更新預設，`/translate-style` 與 result `restyle` action 只套用一次。Restyle postback 只攜帶 opaque record ID 與 allowlisted preset ID，並沿用 ADR-0012 的 ownership check 與 durable claim。

## Consequences

- Prompt 行為變更必須 bump preset prompt version，cache 不會錯用舊語意。
- 使用者輸入不可成為 style system instruction；只能選 allowlisted ID。
- 舊資料不需 migration 即可讀取，缺少或無效 style 視為 faithful。
- 新增 preset 或調整 prompt 屬產品行為變更，需更新 catalog、contract tests 與文件。
