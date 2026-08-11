# ADR-0001: Centralize AI provider execution and fallback

- Date: 2026-08-11

## Status

Superseded by [ADR-0011](0011-openrouter-single-provider-adapter.md)

## Context

OpenAI 與 Gemini 原本由 callers 直接呼叫，錯誤分類、model 選擇、fallback 與 actual execution metadata 容易分散。Caller 若解析第三方 exception 或記錄 requested provider，會產生錯誤決策與不準確紀錄。

## Decision

建立 `AiProviderExecutionModule` 作為 deep Module；provider-specific Implementation 實作 `AiProviderAdapter` Interface。Module 擁有 Provider Route、可恢復失敗的 fallback、normalized outcome、attempt history，以及 actual provider/model/token/latency metadata。

Safety blocked、無效設定與 unsupported operation 不被視為任意 fallback 許可。Caller 只消費 `AiExecutionOutcome`，不解析 provider exception message。

## Consequences

### Positive

- 新 provider 透過 Adapter Seam 加入，caller 不需要認識 SDK。
- Translation Record、cache 與 usage accounting 取得一致的 actual metadata。

### Negative / trade-offs

- 所有 provider operation 必須映射 normalized outcome。
- Route/fallback policy 變更需更新集中 contract tests。

## Alternatives considered

- 各 service 自行 fallback：拒絕，規則與錯誤分類會漂移。
- 直接傳播第三方 exception：拒絕，會把 SDK 細節洩漏到 domain caller。

## Related

- Issue #13
- PR #61
