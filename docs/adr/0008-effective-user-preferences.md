# ADR-0008: Expose immutable effective user preferences

- Date: 2026-08-11

## Status

Accepted

## Context

Provider、model 與目標語言曾由多個 caller 直接讀取 `UserProfile` nullable fields，再各自套 fallback。Legacy Mongo documents、runtime defaults 與 provider/model compatibility 會因此得到不一致結果。

## Decision

`UserPreferencesModule` 是 validation、precedence、fallback、update 與 persistence 的唯一 owner。其他 Module 只消費 immutable `UserPreferences`，不直接從 `UserProfile` 重建規則。Provider-specific model 以 `modelsByProvider` 表示；legacy missing/invalid fields 由 Module 正規化為 readable effective values。

## Consequences

### Positive

- Text、image、status、profile 與 admin view 使用相同 effective values。
- Preference 規則的 Locality 提升，legacy compatibility 可集中測試。

### Negative / trade-offs

- 所有 preference mutation 都必須經 Module Interface。
- `UserProfile` persistence schema 與 effective `UserPreferences` 必須明確區分。

## Alternatives considered

- Caller 直接讀 Mongo document：拒絕，會重複 precedence 與 validation。
- 在 view 層修補 invalid fields：拒絕，provider execution 仍可能使用不同值。

## Related

- Issue #14
- PR #66
