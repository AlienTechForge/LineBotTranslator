# ADR-0004: Account usage per provider attempt with pricing snapshots

- Date: 2026-08-11

## Status

Accepted

## Context

Translation Record 缺少可靠 token metadata；以文字長度猜 token、用最新價格重算歷史或把所有紀錄載入記憶體，會產生不準確成本與擴展問題。一次 LINE Interaction 也可能先做 AI language detection，再做 translation，因此不能只按 interaction 計數。

## Decision

每個 Provider Attempt 產生一個 privacy-minimized Usage Event。Event 保存 operation、content kind、actual provider/model、status/outcome、latency、token metadata，以及事件當下的 pricing version、effective date、currency 與 cost snapshot。

報表以 MongoDB aggregation 依 day/month/provider/model/content kind 計算。Accounting sink 採 fail-open；失敗不可改變 provider outcome。Event 不保存 user ID、原文、譯文、correlation ID 或 secret。

## Consequences

### Positive

- Detection、translation 與 image operation 可分別對帳。
- 歷史成本不受後續價格調整影響；報表不掃描全部 Java object。

### Negative / trade-offs

- 舊 Translation Record 無 actual token metadata，因此不做不可靠回填。
- Pricing catalog 必須持續 versioning 並記錄 effective date。

## Alternatives considered

- 從文字長度估 token：拒絕，不能作精確 accounting。
- 每次查詢套最新價格：拒絕，會改寫歷史語意。

## Related

- Issue #22
- PR #68
