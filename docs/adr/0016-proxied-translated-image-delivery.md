# ADR 0016：翻譯圖片直接輸出，短網址採可選木雷 Adapter

## Status

Accepted

Date: 2026-08-12

## Context

翻譯後 PNG 原本以 MinIO presigned URL 放進 LINE 文字訊息。URL 的 `X-Amz-*`
query 是 private bucket 的臨時讀取能力，不能直接刪除；直接顯示又過長。產品希望
圖片翻譯成功時直接回 LINE Image Message，並讓
管理員控制是否使用同機部署的木雷短網址。

MinIO upload、1 小時 presigned URL 與 24 小時 object retention 已有獨立責任，不在本次
變更範圍。

## Decision

- MinIO 儲存流程維持不變。
- Output layer 只接受內部 pipeline 產生、位於 `translated-images/` 的 HTTPS URL。
- 無 proxy config 時，presigned URL 直接同時作為 LINE original 與 preview URL；不再輸出長網址文字。
- 以 128-bit opaque token 建立短期 `/i/{token}` capability；server-side fetch 禁止 redirect，
  限制 content type、bytes、dimensions 與 pixels。這是需要獨立 preview 時的選配。
- 木雷 Adapter 在 proxy 未配置時可縮短 MinIO presigned URL；僅接受有完整
  AWS V4 query 的 `translated-images/` URL。
- Runtime Setting `shortUrlEnabled` 由已授權管理員透過
  `/admin config short-url [on|off]` 修改。關閉時仍直接輸出圖片，只略過木雷。
- 木雷 API 或回應驗證失敗時 fail-open 到 presigned URL，不影響圖片翻譯結果。
- 木雷 API Token 只由 deployment secret 提供，不保存於 Runtime Settings。

## Consequences

- 使用者收到圖片，而非帶有 S3 query 的文字連結。
- 木雷資料庫會保存 presigned URL；短網址有效期不能超過 S3 簽名的 1 小時。
- 無 proxy 時 original 與 preview 使用同一 URL；超過 LINE preview 限制的圖片可能無法顯示
  preview，此時應配置 proxy 產生縮圖。
- Proxy token registry 為 process-local bounded cache；應用重啟會使尚未過期但尚未由 LINE
  抓取的 proxy URL 失效。LINE 正常會在 reply 後立即抓取；若未來要求跨重啟的可重播連結，
  應另建 Mongo TTL capability store。
- Translate 目前使用 host network，而木雷位於 `1panel-network`；內部 API 連線必須使用
  固定 loopback port，或使用已配置 TLS 的公開木雷 API domain，不能依賴 Docker DNS 名稱。
