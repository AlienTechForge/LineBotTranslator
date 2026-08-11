# ADR-0010: Use a model-aware bounded translation cache

- Date: 2026-08-11

## Status

Accepted

## Context

只以原文／目標語言建立 cache key 會在 provider、model、style、glossary 或 prompt 改變後回傳錯誤語意；無界 cache 會持續成長。Failure 或 fallback result 被 cache 也會隱藏 provider recovery。

## Decision

使用 Caffeine bounded cache Adapter，採 expire-after-write TTL 與 maximum entries。Cache identity 包含 source digest、target、planned/actual provider/model、style、glossary version 與 prompt version。只保存 direct、route-matched success；failure、safety blocked、fallback 與 route mismatch 不寫入。

Metrics 只使用 low-cardinality hit/miss/write/eviction tags，不含使用者原文。

## Consequences

### Positive

- Output-affecting dimension 變更自然失效，memory 使用有界。
- Provider recovery 不會被 fallback/failure cache 遮蔽。

### Negative / trade-offs

- Cache key 維度增加時必須同步 properties、tests 與部署 variables。
- Source digest 可避免直接保存原文於 key/metric，但仍需遵守 data policy。

## Alternatives considered

- Annotation-based unbounded cache：拒絕，缺少容量與完整 identity 控制。
- Cache 所有成功（含 fallback）：拒絕，會延長 degraded route 的可見時間。

## Related

- Issue #18
- PR #63
