# 日誌資料安全政策

本服務的日誌只用於維運、除錯與資安事件調查。應用程式日誌不得成為使用者內容、憑證或第三方 API 回應的副本。

## 允許記錄

- 不可逆的使用者指紋，例如 LINE user ID 的 SHA-256 截短值
- 文字、OCR 結果、模型回應與圖片資料的長度，不包含內容
- AI provider、model、HTTP status、處理時間與功能是否可用
- 已移除帳密、query string 與 fragment 的服務 endpoint
- 例外類型與 cause 類型鏈，不包含例外訊息或 stack trace

## 禁止記錄

- API key、channel token、channel secret、密碼、authorization header 或完整連線字串
- LINE user ID、顯示名稱、群組 ID、原始文字、OCR 結果、翻譯結果或廣播內容
- Base64 圖片、物件儲存 signed URL、第三方 API request/response body
- 可能包含 payload、URL、token 或帳密的例外訊息與 stack trace

## 保留與存取

- Logback 的 rolling files 最多保留 30 天，單檔上限 10 MB。
- 一般日誌總量上限 1 GB，錯誤日誌總量上限 500 MB。
- 日誌只允許維運人員依最小權限原則存取，不得貼到公開 Issue、PR 或聊天內容。
- 匯出供調查前，必須再次掃描並遮蔽 credentials、連線字串及使用者內容。

## 資安事件處理

若敏感資料曾寫入日誌，先部署停止洩漏的修正，再輪替或撤銷所有受影響的 credentials，清除超出保留政策的副本，並記錄受影響期間與修復結果。
