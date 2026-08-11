# ADR 0014：分離 MinIO 內部與外部 endpoint

## Status

Accepted

Date: 2026-08-11

## Context

Production 的 MinIO 與應用位於同一台 Alien-Server。MinIO S3 API 綁定
`127.0.0.1:9000`，外部則經 `https://s3.azndev.com` 的 Nginx 與 Cloudflare。
單一 endpoint 會迫使 bucket check、upload 與 health probe 繞外部代理；若改用 localhost，
產生的 presigned URL 又無法讓 LINE 使用者從外網存取。

## Decision

- `MINIO_ENDPOINT` 只供 bucket check、bucket creation 與 object upload 使用。
- `MINIO_PUBLIC_ENDPOINT` 只建立第二個 MinIO client，用來離線簽署外部 URL。
- 兩個 client 共用 credentials、timeouts 與 bucket name。
- `MINIO_REGION` 必須明確設定，讓 public client 產生 URL 時不需向外部 endpoint 探測 region。
- 缺少 public endpoint 時回退到 internal endpoint，維持單 endpoint 部署相容性。
- S3 失敗 log 只記錄 allowlisted error code 與 HTTP status，不輸出 credential、request body 或 URL query。

## Consequences

- Production 可直連 localhost，不需更動 MinIO container 或 Docker network。
- Presigned URL 仍使用公開 HTTPS hostname，且簽章中的 Host 正確。
- Deploy 必須提供 `MINIO_PUBLIC_ENDPOINT`；本機與測試可讓兩者相同。
