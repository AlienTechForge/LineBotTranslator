# ADR-0011: Use OpenRouter as the single AI Provider Adapter

- Date: 2026-08-11

## Status

Accepted

## Context

應用程式原本直接維護 OpenAI 與 Gemini 兩套 SDK、設定、model allowlist、錯誤分類與跨 provider fallback。這使 caller 與部署必須理解多組 credential，新增或淘汰模型也需要改 code。產品需要在 LINE 對話中搜尋並指定 OpenRouter 可用模型，但不需要成本／品質自動路由或 self-hosted model Adapter。

## Decision

OpenRouter 是應用程式唯一 AI Provider。`OpenRouterService` 是 `AiProviderAdapter` 的唯一 Implementation，使用 Chat Completions API 執行文字翻譯、文字生成與 image-capable model operation。應用程式不保留 direct OpenAI/Gemini connector、SDK、credential 或跨 provider fallback。

`OpenRouterModelCatalog` 實作 `AiModelCatalog` Seam，從 `/api/v1/models` 探索 text-output models，解析 capability/pricing，提供 TTL cache 與 stale/default fallback。Model Selection 依 User Preferences → Runtime Settings → deployment default 決定 model，並在 catalog boundary 驗證 slug 與 operation capability。

LINE 使用 `/models [keyword]` 搜尋、`/model <slug>` 指定個人 model；管理員使用 `/admin config model <slug>` 修改全域 default。Production credential 固定使用 GitHub Secret `OPEN_ROUTE_API_KEY`，deploy 在替換現有容器前呼叫 `/models` 驗證。

## Consequences

### Positive

- Provider Integration 由多套淺 Adapter 收斂為單一 deep Module，caller 不再認識 vendor SDK。
- Model catalog 可隨 OpenRouter 更新，不需為每個 model 發版。
- User Preferences、runtime settings、usage pricing 與 LINE commands 共用同一 catalog Seam。
- 部署只有一組 AI credential，且 preflight 失敗不會動到現有容器。

### Negative / trade-offs

- OpenRouter 成為文字翻譯的單一外部依賴；app 不提供跨 provider fallback。
- Catalog 暫時不可用時只能使用 stale snapshot 或 deployment default。
- OpenRouter 內部如何選擇上游 provider 不由本應用程式控制。

## Alternatives considered

- 保留 direct OpenAI/Gemini 作 fallback：拒絕，重新引入多 credential、SDK 與 routing complexity。
- 寫死 OpenRouter model allowlist：拒絕，模型生命週期變更會迫使頻繁發版。
- 成本／品質自動選模：拒絕，不在目前產品範圍。
- Self-hosted local model Adapter：拒絕，不在目前產品範圍。

## Related

- PR #71
- PR #72
- Supersedes ADR-0001
