# image-translator：LINE bot 的圖片翻譯服務

> 流程：一則 LINE 圖片訊息 → Java bot 下載並檢查圖片 → 用 HTTP 呼叫這個服務（跑在 Java bot 的同一個容器裡，只綁 `127.0.0.1`；映像與啟動方式見 repository 根目錄的 `Dockerfile` 與 `docker/entrypoint.sh`）→ 服務回傳翻好的圖片（或說它做不到）→ Java 存檔並傳給使用者。**回覆只有圖片。**
> 這個目錄原本是 `tools/overlay-lab`（離線對照實驗）。第 7 輪起它是正式的圖片翻譯服務：`/v1` 是正式 API；原本的實驗頁面留在 `/lab`（用 `LAB_TOKEN` 保護），診斷用。正式映像（與 Java bot 同一個容器）與部署腳本的接線在根目錄的 `Dockerfile`、`docker/`、`scripts/deploy.sh`；Java bot 呼叫它的部分是另一個 PR。

目標語言大多是繁體中文（`zh-TW`），來源多半是外文圖片（菜單、海報、文件、招牌、手寫），但 bot 支援的任何語言組合都可能進來。

## 正式 API（`/v1`）

### 認證與狀態檢查

- 每個 `/v1/*` 請求都要帶標頭 `X-Service-Token`，值等於環境變數 `SERVICE_TOKEN`。沒帶回 **401**，帶錯回 **403**；**沒設 `SERVICE_TOKEN` 時 `/v1` 一律回 503**（`reason: service_token_missing`，服務沒設定好），絕不會變成不設防。令牌只收標頭，不收查詢字串。
- `GET /healthz` 不需要令牌，回 `{"status": "ok", "mock": bool, "version": "1.0.0", "budget": {...}}`，沒有任何金鑰或令牌（`budget` 的內容見「預算」）。
- 令牌與請求內容不會出現在 log：log 只有請求 id、狀態與數字（見「隱私」）。

### `POST /v1/translations`（multipart，同步）

| 欄位 | 必填 | 說明 |
| --- | --- | --- |
| `image` | 是 | 檔案：`image/jpeg`、`image/png`、`image/webp`，最大 20 MB，其餘限制同 `imaging.load_image`（可解碼、60 MP 以下） |
| `target_lang` | 是 | BCP-47 語言碼（`zh-TW`、`en`、`ja` …），格式 `^[A-Za-z]{2,8}([-_][A-Za-z0-9]{1,8}){0,3}$`；已知的碼與常見寫法會被正規化（見「語言」），不認識的碼照用並在 `warnings` 提醒 |
| `request_id` | 否 | `^[A-Za-z0-9_-]{1,64}$`；沒給就產生 32 個十六進位字元。記錄還在保留期限內時再送同一個 id 回 409 |
| `policy` | 否 | `a_then_b`（預設）、`a_only`、`b_only`、`both`（A 與 B 都跑，挑最好的）；見「策略」 |
| `mode_b_model` | 否 | 覆蓋 Mode B 的影像模型；驗證同 lab 的模型 id（`^[A-Za-z0-9._:/@+-]{1,120}$`）。沒給用 `OPENROUTER_IMAGE_MODEL`（`google/gemini-3-pro-image`） |

回應 200 的 JSON（`schema_version: 1`；下面是 `LAB_MOCK=1` 對一張 640×480 的合成 PNG 的真實輸出，時間欄位為固定值）：

```json
{
  "request_id": "example-0001",
  "schema_version": 1,
  "target_lang": "zh-TW",
  "status": "image",
  "tier": "clean",
  "reason": "a_pass",
  "mode": "A",
  "verdict": "pass",
  "usability": 1.0,
  "coverage": 1.0,
  "image": {
    "path": "/v1/translations/example-0001/image.png",
    "width": 640,
    "height": 480,
    "bytes": 18117
  },
  "text": {
    "source_language": "en",
    "recognized_text": "TONKATSU SET MENU NT$120 紅燒肉飯店 LUNCH SPECIAL",
    "translated_text": "Pork Cutlet Set Menu\nNT$120\nBraised pork rice shop\nToday's special lunch"
  },
  "judge": {
    "summary": "Mock judge: a canned answer, nothing was actually looked at.",
    "altered_numbers": [],
    "untranslated_major": [],
    "legibility_major": []
  },
  "costs": {
    "anthropic_usd": 0.0,
    "openrouter_usd": 0.0,
    "total_usd": 0.0
  },
  "budget": {
    "month": "2026-10",
    "openrouter_spent_usd": 0.0,
    "openrouter_budget_usd": 15.0,
    "anthropic_spent_usd": 0.0,
    "anthropic_budget_usd": 80.0
  },
  "timings_ms": {
    "ocr": 9,
    "mode_a": 97,
    "mode_b": null,
    "judge": 0,
    "total": 118
  },
  "attempts": [
    {
      "mode": "A",
      "verdict": "pass",
      "usability": 1.0,
      "cost_usd": 0.0,
      "elapsed_ms": 97,
      "error": null,
      "skipped": null,
      "selected": true
    }
  ],
  "warnings": []
}
```

欄位：

- `status`：`image`（有圖可送）、`text_only`（改傳譯文）、`no_text`（圖上沒有字）、`error`（做不出來）。`tier`：`clean` 或 `warned`（只有 `status: image` 才有，其餘是 `null`）。`reason` 與 `mode`（`A`／`B`，採用的是哪一個）見下表。`target_lang` 是實際使用的語言碼（正規化之後）。
- `verdict`、`usability`、`coverage`：被採用的那一張圖的 judge 判定（`pass`、`numbers+score` …，見「判定規則」）、可用度與完成度（0–1）；沒有圖時是 `null`。`text_only` 也帶著，說明為什麼不送圖。
- `image`：`{"path", "width", "height", "bytes"}`，只有 `status: image` 才有（否則 `null`）；`path` 用同一個令牌 GET 就是 PNG。
- `text.source_language`：Vision 偵測到的來源語言（`ja`、`en` …，偵測不出來是 `null`）；`text.recognized_text`：Vision 對來源圖的全文（保留換行）；`text.translated_text`：Mode A 規劃出來的 unit **依規劃給的閱讀順序、每個 unit 一行**（翻譯的 unit 用譯文，`keep` 的 unit〔價格、數字、品牌名〕用原文，unit 自己的換行保留；譯文是空的就顯示原文）。Mode A 的規劃從來沒有回答（沒跑、或在規劃前就失敗）時是 `null`。Mode A 的規劃有回答、之後才在畫圖時失敗，`translated_text` 仍然有。
- `judge`：被採用那張圖的 judge 摘要 `{"summary", "altered_numbers", "untranslated_major", "legibility_major"}`（後三者是 judge 紀錄裡的項目：被改的數字、`major` 的沒翻文字、`major` 的亂碼）；judge 沒有答案時是 `null`。
- `costs`：這個請求花的錢 `{"anthropic_usd", "openrouter_usd", "total_usd"}`（Mock 模式一律 0）。`budget`：寫入這個請求之後本月的用量與上限（見「預算」）。
- `timings_ms`：`ocr`（來源圖 Vision）、`mode_a`、`mode_b`（含各自的 judge；沒跑的是 `null`）、`judge`（所有 judge 的合計）、`total`。
- `attempts`：每個模式一筆 `{"mode", "verdict", "usability", "cost_usd", "elapsed_ms", "error", "skipped", "selected"}`。`error` 是失敗原因（已遮掉金鑰）；`skipped` 是這個模式沒跑的原因（`policy_a_only`、`policy_b_only`、`openrouter_budget`、`anthropic_budget`、`deadline`）；A 通過而 B 根本沒必要跑時，不會有 B 那一筆。
- `warnings`：給人看的提醒（最多 30 則）：未知語言、缺字型、右到左文字、價格未知、Mode B 為什麼被跳過、某個模式失敗、judge 提到但不計入 Mode A 判定的版面問題、Mode A 的排版提醒（例如多少個直排欄太窄而保持原樣）。

### `status`、`tier`、`reason` 一覽

| `status` | `tier` | `reason` | 意思 |
| --- | --- | --- | --- |
| `image` | `clean` | `a_pass` | Mode A 的判定是 `pass`（意味著 usability ≥ 0.80），直接用，Mode B 不跑 |
| `image` | `clean` 或 `warned` | `b_better` | Mode B 的結果勝過 Mode A 的圖（`verify.choose_attempt`，平手給 A） |
| `image` | `warned` | `a_better` | A 沒過、B 跑了，但 A 的圖還是比較好（或平手） |
| `image` | `clean` 或 `warned` | `a_failed` | Mode A 沒做出圖（例外、Vision 失敗），採用 Mode B 的圖 |
| `image` | `warned` | `b_failed` | A 的圖沒過，Mode B 失敗，只好用 A 的圖 |
| `image` | `warned` | `policy_a_only` | A 的圖沒過，但 `policy=a_only` 不准跑 B |
| `image` | `clean` 或 `warned` | `policy_b_only` | `policy=b_only`：沒跑 Mode A，採用 Mode B 的圖 |
| `image` | `warned` | `openrouter_budget` | A 的圖沒過，但本月 OpenRouter 預算用完，跳過 Mode B |
| `image` | `warned` | `anthropic_budget` | A 的圖沒過，但 Mode A 之後本月 Anthropic 預算用完了（Mode B 的 judge 需要它），跳過 Mode B |
| `image` | `warned` | `deadline` | A 的圖沒過，但剩下的時間不到 `MODE_B_MIN_REMAINING_S`，跳過 Mode B |
| `text_only` | `null` | `below_threshold` | 最好的那張圖 usability 低於 `SEND_MIN_USABILITY`（0.60）：Java 改傳 `text.translated_text` |
| `text_only` | `null` | `unverified` | 有圖，但 judge 沒有答案（沒金鑰、API 錯誤…）：不敢送，改傳譯文 |
| `no_text` | `null` | `no_text` | Vision 在來源圖上找不到任何字 |
| `error` | `null` | `both_failed` | Mode A 與 Mode B 都失敗 |
| `error` | `null` | `a_failed` | `policy=a_only` 且 Mode A 失敗（B 不准跑） |
| `error` | `null` | `b_failed` | `policy=b_only` 且 Mode B 失敗（A 沒跑） |
| `error` | `null` | `openrouter_budget`、`deadline`、`anthropic_budget` | 沒有任何一張圖，而且 Mode B 因預算或時間被跳過；`anthropic_budget` 也用在請求一開始 Anthropic 預算就用完（**什麼都沒呼叫**）；`deadline` 也用在一開始時限就已過 |
| `error` | `null` | `internal_error` | 策略本身出了沒預料到的錯誤（程式 bug）；仍然是 200，不是 500 |

`service_token_missing` 只出現在 503 的錯誤本文（見下）。**內部失敗不會變成 HTTP 錯誤**：只要請求本身合格，一定是 200 加上 `status`，Java 才有東西可以告訴使用者。

### HTTP 錯誤

錯誤的本文是 `{"status": "error", "reason": <代碼>, "detail": <一句話>, "schema_version": 1}`：

| HTTP | `reason` | 何時 |
| --- | --- | --- |
| 400 | `bad_request` | 缺 `image`、空檔、`target_lang` 缺少或格式不對、`request_id`／`policy`／`mode_b_model` 不合法 |
| 401 | `unauthorized` | 沒帶 `X-Service-Token` |
| 403 | `forbidden` | 令牌不對 |
| 404 | `not_found` | 沒有這個 id、已過期、或沒有圖 |
| 409 | `request_id_in_use` | 這個 `request_id` 正在處理或還在保留期限內 |
| 413 | `payload_too_large` | 檔案超過 20 MB（`Content-Length` 遠超過時在讀本文之前就拒絕） |
| 415 | `unsupported_media_type` | 不是 jpeg／png／webp，或不是能解碼的圖片 |
| 503 | `service_token_missing` | 沒設 `SERVICE_TOKEN` |

### 取回結果與保留期限

- `GET /v1/translations/{id}` 把同一份 JSON 再給一次；`GET /v1/translations/{id}/image.png` 串流被採用的那張圖（`image/png`，`Cache-Control: no-store`）。兩者都要令牌。
- 記錄存在 `<LAB_DATA_DIR>/v1/<id>/`：`record.json`（回應本文）與 `image.png`（只有 `status: image`）。**來源圖片不會寫進硬碟**；Mode A 的暫存檔放在 `work/`，請求結束就刪。
- 背景清掃每分鐘（保留期限短於 4 分鐘時更勤）刪除寫入超過 `V1_RETENTION_MINUTES`（預設 15）的資料夾；過期的記錄在清掃之前就已經是 404。

## 策略：先 A 再 B，三個層級

`app/policy.py` 的 `run(image, target_lang, policy, request_id, deadline)`：

1. **Vision 讀來源圖一次**（`jobs.SourceOcr`，Mode A 與兩個 judge 共用）。沒有字：`status: no_text`。Vision 失敗：Mode A 做不了，Mode B 與它的 judge 照樣可以。
2. **Mode A**（Vision 字框 → Claude 規劃 → 擦除 → Pillow 畫字，見後面各節）加 judge；**不自己重試**（lab 的那一次重試由 Mode B 取代）；覆蓋補翻照舊。judge 對 Mode A **不計 `rows`**（見「第 7 輪的修正」）。
3. Mode A 判定 `pass`：`image`／`clean`／`a_pass`，結束。
4. 否則，當策略允許、OpenRouter 本月預算還有空間、時限剩下至少 `MODE_B_MIN_REMAINING_S`（90 秒）、而且 Anthropic 預算還有空間（Mode B 的 judge 要用）：**Mode B 一次生成**（`google/gemini-3-pro-image`，2K，不重試）加 judge；兩個嘗試用 `verify.choose_attempt` 選較好的（平手給 A）。**最多這兩個嘗試，沒有更多重試。**
5. 被採用的那個嘗試：判定 `pass` → `clean`；否則 usability ≥ `SEND_MIN_USABILITY`（0.60；剛好 0.60 算）→ `warned`（附圖，Java 加一行警語）；否則 `text_only`（`below_threshold`）。沒有任何嘗試做出圖：`error`。
6. Mode B 被跳過時，原因寫在 `reason`（`openrouter_budget`、`anthropic_budget`、`deadline`、`policy_a_only`；圖還是 `warned`），也寫在 `attempts[].skipped` 與 `warnings`。

整體時限 `V1_DEADLINE_S`（預設 210 秒，從請求開始算）：超過之後**不再開始新的工作**，回傳目前最好的結果。已經在跑的呼叫不會被中斷（Anthropic／Vision／OpenRouter 各自的逾時仍是 lab 的設定），所以它不是硬性的牆：Java 端自己要有讀取逾時（建議 240 秒），逾時就當成 `text_only` 處理。一個模式裡的錯誤不會殺掉另一個。

`policy`：`a_then_b`（預設，上面這套）；`a_only`（只跑 A；A 沒過也不跑 B）；`b_only`（不跑 A，只跑 B）；`both`（A 與 B 都跑，不管 A 有沒有過，挑最好的；預算與時限的規則照樣適用）。

**輸出圖的 OCR 在正式 API 裡不跑**：lab 對每張輸出圖多打一次 Vision 只為了「OCR 警告」，那些警告不影響判定；正式 API 省掉它（少一次 Google 呼叫與一個可能卡住的步驟）。所以 `/v1` 整個請求只有一次 Vision（來源圖）。

## 預算

- 數字記在 `<LAB_DATA_DIR>/budget.json`：`{"month": "2026-10", "openrouter_usd": 3.21, "anthropic_usd": 12.5, "requests": 87}`。月份是 **Asia/Taipei 的日曆月**（固定 UTC+8，不依賴時區資料庫）；進入新月份的第一次讀寫從 0 開始。寫入是原子的（同目錄暫存檔 → `fsync` → `os.replace`），程序內有一把鎖；壞掉的檔案會被改名成 `budget.json.damaged-<時間>` 再從 0 算。
- **每個嘗試的費用在知道的當下就加進去**：OpenRouter 用回應的 `usage.cost`；Claude 的規劃（含覆蓋補翻）與 judge 用價目表（`claude_units.PRICES_PER_MTOK`）依 tokens 估算；**不知道價格時算 0 並在 `warnings` 提醒**。`LAB_MOCK=1` 時一律算 0，但檔案照寫。judge 呼叫成功之後才失敗（例如 JSON 不合 schema）的那一筆錢不會被記到。
- 上限：`OPENROUTER_MONTHLY_BUDGET_USD`（預設 15）、`ANTHROPIC_MONTHLY_BUDGET_USD`（預設 80），**0 = 不設上限**。`budget.allows_openrouter()`／`allows_anthropic()` 是「已用 < 上限」（剛好用完就不准）。沒有每個使用者的限制。
- OpenRouter 用完：不再跑 Mode B（`reason` 見上表）。Anthropic 用完：整個請求 `status: error`、`reason: anthropic_budget`，**什麼都不呼叫**。
- 上限是軟的：一個在還有空間時開始的請求會跑完，就算中途用完；同時開始的兩個請求可能都通過檢查。
- `GET /healthz` 與每個回應的 `budget`：`{"month", "openrouter_spent_usd", "openrouter_budget_usd", "anthropic_spent_usd", "anthropic_budget_usd"}`（上限 0 表示不設上限）。`requests` 只在檔案裡：每個進到策略的請求加 1（含因預算被拒絕的）。

## 語言

`config.LANGUAGES`（英文名稱用在 prompt 裡）：`en` English、`zh-TW` Traditional Chinese (Taiwan)、`zh-CN` Simplified Chinese、`zh-HK` Traditional Chinese (Hong Kong)、`ja` Japanese、`ko` Korean、`vi` Vietnamese、`th` Thai、`id` Indonesian、`ms` Malay、`tl` Filipino (Tagalog)、`fr` French、`de` German、`es` Spanish、`it` Italian、`pt` Portuguese、`ru` Russian、`ar` Arabic、`he` Hebrew、`hi` Hindi、`tr` Turkish、`nl` Dutch、`pl` Polish、`uk` Ukrainian。

- **正規化**（`config.normalize_lang`）：不分大小寫、`_` 當 `-`；`zh`、`zh-Hant`、`zh-Hant-TW`、`tw` → `zh-TW`；`zh-Hans`、`zh-Hans-CN`、`cn`、`zh-SG` → `zh-CN`；`zh-Hant-HK`、`zh-MO` → `zh-HK`；`fil` → `tl`、`iw` → `he`；已知語言的地區碼（`fr-FR`、`pt-BR`、`en-US`）就是那個語言。回應的 `target_lang` 是用的那個碼。
- **不認識的碼**（`sw`、`xx` …）在 `/v1` 照樣接受（格式要對）：prompt 直接用那個碼當語言名稱，字型退回 Noto Sans／DejaVu，`warnings` 會有一則提醒。lab 頁面仍然是下拉選單（表裡的語言）。
- **字型**：中日韓依語言挑 Noto Sans CJK 的字面（`zh-HK` 用 `zh-TW` 的 TC）；**泰文、阿拉伯文、希伯來文、天城文**用 `fonts-noto-core` 裡的 Noto Sans Thai／Arabic／Hebrew／Devanagari（`fonts.pick_font`）。對應的字型沒裝時退回 Noto Sans／DejaVu，`warnings` 會說缺哪個字型。
- **右到左的文字**（`ar`、`he`）：照原樣畫（沒有 bidi、沒有字母連寫的 shaping），`warnings` 會說圖多半是錯的。這個 PR 不處理 shaping。泰文沒有斷詞，只在空白處換行。

## 第 7 輪的修正（judge 與排版）

四項都來自第 6 輪的真實圖片結果，各有合成的單元測試：

1. **`rows` 不是 Mode A 的檢查項目**：`verify.verify_output(..., structure_checks=True)`，Mode A 傳 `False`（`jobs.check_output(..., structure_checks=False)`）。Mode A 的字畫在原位，結構不可能變，第 5、6 輪 Mode A 上的 `misaligned_marks`／`missing_row` 都是 judge 的雜訊。judge 提到的 `structure_issues` 照樣存起來、也顯示成提醒（紀錄裡的 `structure_warnings`、`checks.rows.ignored`；`/v1` 的 `warnings`），只是不讓 `rows` 失敗。Mode B 照舊檢查。
2. **同組的成員用組的行高排版**（`pipeline_a._lift_to_group`）：一個 unit 自己的字框比全組字高中位數小（第 6 輪越南文海報的「Ô tô」項目符號是 6 px 的框、其餘是 12 px 的清單），就用一個高度（直排是寬度）等於全組中位數的框排版，中心不變，這樣它能達到共同字級，不會被畫得小小的。unit 報告記 `frame_height_from_group: true` 與 `group_frame_height`。只有組內至少兩個 unit、且自己比中位數小的才會被拉高。
3. **標題與小標的中日韓換行**（`layout.py`）：譯文裡有空白或中日韓標點（`，、。；：！？`、右括號）時，換行優先斷在那裡（斷完那一行至少要有 40% 滿，否則照舊逐字斷）；孤字規則變成「最後一行不可以是 2 個以下的中日韓字（或一個很短的拉丁詞，≤ 4 字元）」，只用於 `title`、`heading`；先用稍窄的第一行重排，還是避不開就用稍小的字級——從次大的字級往下找，**最多降到「可讀」的下限**（`min_size`），到下限還是有孤字就接受它（以前是最多縮 15%）。
4. **拉丁目標語言圖片裡的窄直排欄**：目標語言不是中日韓時，`vertical_rl`／`vertical_lr` 的 unit 若欄寬（字高）小於 `MIN_ROTATED_COLUMN_PX`（預設 16）就**保持原樣**（`skipped: column too narrow`，不擦除），不再畫成讀不出來的轉 90 度小字；unit 報告的 `skip_reason`、Mode A 的 `warnings`、lab 頁面都會說。（第 6 輪收據的 20 欄菜名只有 15 px 寬，轉 90 度之後讀不出來，還拖低分數。）中日韓目標的直排照舊直立。

## Lab 頁面（`/lab`）

原本的頁面與它的 `/api/jobs`、`/api/batches`、`/files` 現在在 `/lab` 底下（`/lab/`、`/lab/api/jobs`、`/lab/api/batches`、`/lab/files/...`），全部由 `LAB_TOKEN` 保護（`?token=` 或標頭 `X-Lab-Token`；**沒設 `LAB_TOKEN` 就不設防**，所以只在綁 `127.0.0.1` 時可以不設）。頁面用相對網址，所以放在 `/lab/` 就會打 `/lab/api/...`。

**舊的根路徑保留一個版本**：`/api/*` 與 `/files/*` 仍然有效（回應多一個 `Deprecation: true` 標頭），`GET /` 以 307 導到 `/lab/`（保留 `?token=`）。`/v1` 與 `/healthz` 不受 `LAB_TOKEN` 影響，`/lab` 也不受 `SERVICE_TOKEN` 影響。

綁定位址 `BIND_HOST`（預設 `127.0.0.1`）、埠 `PORT`（預設 4041；舊的 `LAB_PORT` 仍然有效，兩個都設時 `PORT` 優先）。uvicorn 的 access log 維持關閉。單獨用 `docker compose` 跑時 compose 把 `BIND_HOST` 固定為 `0.0.0.0`（Docker 要發佈埠）。

## Mock 模式

`LAB_MOCK=1` 讓 `/v1` 也完全離線：Vision、規劃、judge 與影像模型都是罐頭答案（Mode B 直接回傳輸入圖；judge 一律 usability 1.0），所以 Java 端可以對著它開發與測試。`policy=both` 的 mock 請求會跑 A 與 B；預算檔照寫、費用算 0。罐頭答案永遠是 `status: image`、`tier: clean`（`a_then_b` 時 `reason: a_pass`），要測其他 `status` 請用單元測試的腳本化結果。

```bash
cd services/image-translator
LAB_MOCK=1 SERVICE_TOKEN=dev-token python -m app &      # 127.0.0.1:4041
curl -s localhost:4041/healthz
curl -s -H 'X-Service-Token: dev-token' -F image=@sample.png -F target_lang=zh-TW localhost:4041/v1/translations
```

## 隱私

- 只有**縮小後的圖片**（長邊最多 1568 px 的 JPEG，Mode B 最多 2048 px）與 OCR 文字會送到 Anthropic 與 OpenRouter；原圖本身只送給 Google Vision。
- INFO 等級的 log 沒有任何 OCR 文字、譯文、圖片資料、令牌或金鑰：只有請求 id、狀態、模式、判定與數字。測試會檢查。錯誤訊息進 `attempts[].error` 之前會遮掉已知的金鑰（`redaction.py`，含 `SERVICE_TOKEN`）。
- 來源圖不寫進硬碟；回傳的圖與記錄只留 `V1_RETENTION_MINUTES` 分鐘。

## 這個 PR 沒有驗證的

所有需要真實 Anthropic（規劃與 judge）、Google Vision、OpenRouter 的部分都**只經過離線測試與 `LAB_MOCK=1` 端到端**：新的策略在真實圖片上的通過率、`SEND_MIN_USABILITY`（0.60）與預算數字是否合適、Mode B 在 210 秒時限內的實際耗時、第 7 輪四項修正在真實規劃輸出上的效果、泰文／阿拉伯文等字型（沒有裝 Noto 字型的環境只測了退回與提醒）。價目表之外的 Claude 型號費用算 0。

---

## Lab：同一批圖的 Mode A／Mode B 對照（`/lab`）

以下是 lab 本身的說明（第 2–6 輪的內容仍然有效，只是路徑在 `/lab` 底下）。


頁面一次最多收 **4 張圖（一個批次）**，**每張圖可以各自選目標語言**（選好檔案後每張圖多一列語言選單，預設是批次的語言）。送出後最上面是**批次彙總表**（每張圖 × Mode／模型一列：語言、判定、可用度、Anthropic 額度費用、OpenRouter 現金費用、時間），下面每張圖一個區塊，區塊內是一排並排的欄位：**原圖｜Mode A｜Mode B 的每個模型（每次 run）各一欄**（欄位多時橫向捲動）。

- **Mode B（影像模型）**：整張圖丟給 OpenRouter 上有「圖片輸出」能力的模型，要它「把所有文字換成譯文，其餘不動」。可以一次勾選最多 4 個模型平行跑，每個模型還能**連跑 1–3 次**來算通過率。頁面預設勾 `google/gemini-3-pro-image`（Pro）；Flash 等其他模型照舊可多選。
- **Mode A（hybrid，第 5 輪升級後預設也開）**：Google Vision 只給「每個字的框」→ Claude（看圖 + 看字清單）決定哪些字屬於同一個翻譯單位、翻譯、給 `short`（短版）、對齊、粗細、重要性 → 我們的程式用字框算幾何，**逐 unit 判斷背景**：平坦就填色、否則 OpenCV inpaint，且擦除範圍受**顏色護欄**限制（不會長進旁邊的黑色格子或表格線）→ 排版先求「可讀」（短版、往旁邊的空位長、直排的拉丁字轉 90 度）→ Pillow 畫回去。Claude 的用量由訂閱額度支付，所以 Mode A 的費用只算在「Anthropic（額度）」。
- **審查（judge，第 5 輪改成看圖）**：每張輸出（Mode B 與 Mode A 同一套 prompt、同一種紀錄）都由 Claude **同時看原圖與輸出圖**，回報可用度 `usability`、完成度 `coverage`、亂碼／讀不出來、表格列重複或漏掉、被改的數字、沒翻的文字、翻錯的文字。判定（`pass` 或 `numbers+garbled` 這種）**完全來自 judge**；OCR 的數字與殘留檢查降為「OCR 警告」。判定不是 `pass` 時**自動重試一次**（Mode B 重畫一次、Mode A 再請 Claude 規劃一次），兩次的結果都留著。
- **給審閱者的連結**：每個 job 跑完後，所有圖片與 JSON 會在背景上傳到你的 MinIO（每張圖一個 manifest），**整個批次都上傳完後再多一份批次 manifest**，頁面上有對應的連結，審閱者不用開頁面也能抓到結果。

沒有針對任何單張圖的特殊規則：分組、方向、角色、對齊、粗細都是 Claude 看圖判斷，幾何與繪製是通用程式。

## 為什麼是現在這個樣子

### 第 2–4 輪

Mode B 用 `google/gemini-3-pro-image` 在四張測試圖上都接近 Google Lens 的品質，但每次執行都不穩定：模型會改手寫數字、漏翻標題、在目標語言裡編出亂碼。第 3、4 輪加了：更嚴格的 prompt、依模型挑 API 路徑、輸出尺寸選項、每張輸出自動驗證 + 一次重試、多模型並排、連跑多次算通過率、結果上傳 MinIO，以及讀 OCR 文字的語意 judge。

### 第 5 輪的發現，以及這一輪改了什麼

第 4 輪在兩張真實圖片（收據、美髮價目表）上跑了 18 次，**每一次都被判失敗**，原因多半在驗證本身：

1. **judge 讀的是 OCR 文字，不是圖。** Vision 會把輸出圖上很小的字讀錯（10 px 價格標籤的 "Member Price" 變成 "Manbar Price"／"Orighet Price"，手寫的 "1 Spicy" 變成 "901 spicy"），所以畫得出來的圖被判 `garbled`。
2. **OCR 的數字檢查在手寫字與很小的 logo 字上不可靠**：Vision 把手寫的 77446716 讀成 97446716（信心 0.909），"Since 2024" 也讀錯，正確的輸出被判 `digits-changed`。
3. **真正的錯誤只有看兩張圖才抓得到，卻沒被回報**：影像模型把表格的一列畫了兩次（手寫的數量記號因此跑到錯的菜名旁邊）、把「漂髮」翻成 "Perm"、把一個韓文標籤變成亂碼、有一次還把電話改成 99446916。

Mode A 在第 2 輪之後暫停，因為圖不好看（見 `第 5 輪 Mode A` 一節的弱點清單）。因為 Claude 用量有訂閱額度、影像模型是現金，這一輪把它升級回來。所以這一輪做了四件事（細節在後面各節）：

1. **一次最多四張圖的批次**：頁面、API（`POST /api/batches`）、批次彙總表、批次 manifest、全程序共用的 Claude 並行上限（4）。
2. **看圖的 judge**：同時看原圖與輸出圖（長邊最多 1568 px 的 JPEG）加上來源的 OCR 行（只當輔助），Mode A 與 Mode B 共用。
3. **判定改由 judge 決定**：numbers／rows／garbled／partial／score 五項；OCR 檢查變成警告；重試 prompt 取自 judge 的發現。
4. **Mode A 升級**：顏色護欄的擦除（平坦填色或 inpaint）、可讀的排版（`short`、往旁邊長、直排拉丁字轉 90 度、標題不留孤字）、Noto Sans 字型、Claude 規劃新增 `short`／`align`／`weight`／`importance`、帶著 judge 發現的一次重試。

### 第 6 輪的發現，以及這一輪改了什麼

第 5 輪在四張真實圖片上實跑（Mode A 的規劃與 judge 都是 `claude-sonnet-5-5`，Mode B 是 `google/gemini-3-pro-image`）：

| 圖 | Mode A | Mode B | 圖上看到的 |
| --- | --- | --- | --- |
| 收據 中→英（表格、手寫） | 0.72 `rows+partial+score` | 0.40 `numbers+rows+score` | A 的表格乾淨、價格與手寫記號都沒動；但右邊橘色卡片的直排菜名沒翻、各列字級與粗細不一、底部「Rd. St. Sec. …」坐在和紙張不同色的灰色方塊上，重試那次還因為 `max_tokens` 失敗。B 重複了一列、改了電話。 |
| 美髮價目表 中→英（深色藥丸） | 0.80 pass | 0.88 pass | 平手 |
| 日文文章 日→中 | 0.90 pass | 0.80 pass | A 乾淨，B 有錯字 |
| 越南文海報 越→中 | 0.60 `rows+score` | 0.93 pass | A 在米色底上留下髒斑、漏掉一個項目、粗細混用 |

這一輪針對這些失敗做六件事（各節有細節）：

1. **規劃不再死在 `max_tokens`**：`claude_units.MAX_TOKENS` 從 16000 提高到 32000；prompt 要求 `notes` 除非真的不確定否則是空字串、`short` 在譯文已經很短時是空字串（不要灌水）；失敗的那次呼叫的 output tokens 會寫進錯誤訊息。第二次呼叫失敗仍然沿用第一次的結果。
2. **規劃的覆蓋**：每個 OCR 字都要分到某個 unit（純裝飾才可以不列），非中日韓目標的圖裡，每一欄直排的中日韓字就是一個 `vertical_rl` unit；`validate_units` 之後如果超過 10% 的字不在任何 unit，會**補翻**一次（見「規劃的覆蓋與 token 預算」）。
3. **平坦填色要和真正的背景一致**：不再只信字框裡的顏色，要看 unit 外面一圈**環帶**；環帶和框內同意才平坦填色，而且填環帶的顏色，否則走顏色護欄的 inpaint；往旁邊長的那一段也要通過同一個測試（見「擦除不再髒」）。
4. **同一個表格欄／清單的字級與粗細一致**：規劃多一個必填欄位 `group`，渲染器對同組的 unit 用同一個字級、同一個粗細（見「同組同字級、同粗細」）。
5. **judge 只報問題**：陣列裡不可以有「這個沒問題」的條目，`mistranslated` 只列會誤導讀者的差異；`verify.judge_checks` 另加一道保險：`structure_issues` 的 `detail` 是空的就不算 `rows`（見「視覺審查」與「判定規則」）。
6. **每張圖各自的目標語言**：批次表單選完檔案後每張圖一列語言選單，API 多一個 `target_langs`，彙總表、批次紀錄與批次 manifest 都帶語言（見「一次送出最多四張圖」）。

## 三個指令跑起來

在安裝了 Docker 的伺服器上：

```bash
cd services/image-translator
cp .env.example .env        # 填 OPENROUTER_API_KEY（Mode A 另需 ANTHROPIC_API_KEY），強烈建議再填 LAB_TOKEN
docker compose up --build -d
```

然後開 `http://<伺服器>:4041/lab/?token=<LAB_TOKEN>`（沒設 `LAB_TOKEN` 就直接開 `http://<伺服器>:4041/lab/`；舊的根網址 `/` 會被導過去）。這個服務同時在同一個埠提供 `/v1` 與 `/healthz`。

看 log：`docker compose logs -f lab`。停掉：`docker compose down`。

只想先確認整個流程能動、手邊沒有任何金鑰（完全不打網路，Vision / Claude / OpenRouter / MinIO 全換成內建 fixture 與記憶體假物件）：

```bash
docker build -t overlay-lab:local .
docker run --rm -p 4041:4041 -e LAB_MOCK=1 overlay-lab:local
# 內建合成測試圖：http://localhost:4041/lab/api/mock-image ，下載後丟進頁面即可
```

不用 Docker（Python 3.12 或 3.13）：

```bash
python -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt
LAB_MOCK=1 python -m app          # 或設好環境變數後直接 python -m app
pytest -q                         # 單元測試 + mock 端到端（全部離線）
```

> CI（`.github/workflows/ci-cd.yml` 的 `Image translator tests`）用 Python 3.12 安裝 `requirements.txt` 並跑 `pytest -q`；本機 3.12 與 3.13 都可以。`requirements-runtime.txt` 是正式映像安裝的執行期套件（不含 `pytest`），`requirements.txt` 是它加上 `pytest`。

## 憑證怎麼給

### Google Vision（預設：直接掛正式機的 volume，不複製、不落地）

`scripts/deploy.sh` 會把 Vision 服務帳戶 JSON 放在 Docker volume `linebot-translator-secrets`，檔案是 `/run/secrets/linebot.json`（owner uid 10001、權限 0400）。
`docker-compose.yml` 預設就是把這個 **外部 volume 以唯讀掛進 lab 容器**，`GOOGLE_APPLICATION_CREDENTIALS` 預設指向 `/run/secrets/linebot.json`，所以**不需要把 JSON 取出來**。

- 容器是以 root 執行的（lab 工具刻意這樣做，才讀得到 0400 的檔案；這個獨立映像只用來跑 lab，請不要對網際網路開放，除非設了 `LAB_TOKEN` 與 `SERVICE_TOKEN`；compose 專案、映像與容器名稱仍叫 `overlay-lab`，這樣在新目錄 `docker compose up` 會原地取代正在跑的 lab 容器）。
- 上傳與輸出放在 `./data/`（容器內 `/app/data`，主機上是 root 擁有）。不用時 `sudo rm -rf data` 即可。
- 這個容器只需要對外連線（Vision、Anthropic、OpenRouter、MinIO），**不需要**加入 bot 的 Docker network。
- 如果 `docker compose up` 說找不到 volume：`docker volume ls | grep linebot-translator-secrets`，名稱不同的話改 compose 裡的 volume 名稱。
- **Mode B 的驗證也要 Vision**：來源圖 1 次（同一個 job 內 Mode A 與所有模型共用）+ 每張輸出圖 1 次。Vision 不能用時圖照常顯示，只是判定顯示 `unverified`、不會重試。

### Google Vision（替代：主機上的檔案）

編輯 `docker-compose.yml`：註解掉標 `(1)` 的 volume 那行與最下面的 `volumes:` 區塊，打開標 `(2)` 的那行，並在 `.env` 填：

```
GOOGLE_APPLICATION_CREDENTIALS=/secrets/vision.json
VISION_CREDENTIALS_FILE=/path/on/host/vision.json
```

如果你還是想把 JSON 取出來一份（用方法 1 並不需要）：

```bash
docker run --rm -v linebot-translator-secrets:/run/secrets:ro alpine cat /run/secrets/linebot.json > vision.json
chmod 600 vision.json
```

> 警告：`vision.json` 是金鑰，**不要放進 repo**（本目錄的 `.gitignore` 已擋 `vision*.json`，但請把它放在 repo 外面），用完就刪。

### OpenRouter 金鑰（從正在跑的正式容器讀，不要把整包環境變數印出來）

正式機的變數名稱是 `OPEN_ROUTE_API_KEY`；lab 兩個名稱（`OPENROUTER_API_KEY`、`OPEN_ROUTE_API_KEY`）都接受，直接貼進 `.env` 即可：

```bash
docker inspect linebot-translator --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E '^(OPEN_ROUTE|OPENROUTER)_API_KEY='
```

輸出的整行就是金鑰，請只貼進 `.env`，不要貼到聊天或 issue。

### Anthropic 金鑰（Mode A 的規劃，以及兩個 Mode 的視覺 judge）

`ANTHROPIC_API_KEY` 填進 `.env`。Mode A 預設模型 `claude-opus-5-5`（`CLAUDE_MODEL`；**擁有者目前的 `.env` 設的是 `claude-sonnet-5-5`**，所以第 5 輪的真實圖片結果是 Sonnet 規劃的，換回預設的 Opus 之後行為可能不同）；judge 預設 `claude-sonnet-5-5`（`CLAUDE_JUDGE_MODEL`）。沒有這支金鑰時 Mode A 不能用；Mode B 照常生成，但每次嘗試的 judge 顯示 `unavailable`，判定是 `unverified`（不會重試；OCR 警告仍會顯示）。

### MinIO（給審閱者的連結；選填）

變數名稱與正式 bot **完全相同**，所以可以從正在跑的 `linebot-translator` 容器複製（同樣只印 `MINIO_` 開頭的那幾行，貼進 `.env`，不要貼到聊天或 issue）：

```bash
docker inspect linebot-translator --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E '^MINIO_'
```

- `MINIO_ENDPOINT` 是 bot 的內部位址（例如 `http://minio:9000`）也沒關係：上傳走它，但**簽給審閱者的連結用 `MINIO_PUBLIC_ENDPOINT`**（審閱者的沙盒連得到的 `https://s3.azndev.com`）。只填 `MINIO_PUBLIC_ENDPOINT` 時上傳也走它。
- 正式 bot 沒設 `MINIO_BUCKET_NAME` 時用的是 `linebot-images`，這個工具預設的 bucket 是 `line-bot`（依需求）。從容器複製出來的值是什麼就用什麼；這個工具只會寫在 `<bucket>/<MINIO_PREFIX>/<job-id>/` 底下，不會動 bucket 裡其他東西。
- 缺 `MINIO_ENDPOINT`／`MINIO_PUBLIC_ENDPOINT`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY` 任何一個，就**略過上傳**並在頁面的「給審閱者的連結」那一行寫出缺哪些（只寫變數名稱）。
- `LAB_MOCK=1` 不需要任何 MinIO 設定：改用記憶體假物件，manifest 照樣產生（連結打不開，頁面會註明）。

## 環境變數

| 變數 | 預設 | 說明 |
| --- | --- | --- |
| `GOOGLE_APPLICATION_CREDENTIALS` | compose：`/run/secrets/linebot.json` | Vision 服務帳戶 JSON 的路徑（容器內）。正式 bot 用的是同一個變數名稱。 |
| `VISION_CREDENTIALS_FILE` | 空 | 方法 2 才用：主機上的 JSON 路徑，掛到 `/secrets/vision.json`。 |
| `ANTHROPIC_API_KEY` | 無 | Mode A 的規劃與視覺 judge 需要。沒有的話 Mode A 失敗、judge 顯示 `unavailable`（不影響生成與 OCR 警告）。 |
| `CLAUDE_MODEL` | `claude-opus-5-5` | Mode A 規劃用的模型（程式與 `.env.example` 的預設是 `claude-opus-5-5`；擁有者目前的 `.env` 設的是 `claude-sonnet-5-5`）。價目表只有 `claude-opus-5-5`（4 / 20 USD per 百萬 tokens）、`claude-sonnet-5-5`（2 / 10）、`claude-haiku-5-5`（0.10 / 0.50）；其他型號只顯示 tokens。 |
| `CLAUDE_JUDGE_MODEL` | `claude-sonnet-5-5` | 視覺 judge 用的模型（每次嘗試一次呼叫，見「視覺審查」）。價目表同上，其他型號只顯示 tokens、不估費用。 |
| `MODE_A_RETRY` | `1` | Mode A 的判定不是 `pass` 時，帶著 judge 的發現再請 Claude 規劃一次（頁面有同名的勾選框，可以逐次覆蓋）。`0` 關掉。 |
| `OPENROUTER_API_KEY` / `OPEN_ROUTE_API_KEY` | 無 | Mode B 必填，兩個名稱擇一。 |
| `OPENROUTER_IMAGE_MODEL` | `google/gemini-3-pro-image` | 頁面模型多選預設勾選的那一個（第 5 輪由 `google/gemini-2.5-flash-image` 改成 Pro）；沒指定 `image_models` 的 API 呼叫也用它。清單會列出 OpenRouter 上所有「輸出含圖片」的模型（快取 10 分鐘）。 |
| `OPENROUTER_IMAGE_API` | `auto` | `auto`：依模型挑順序（見下方「API 路徑」）；`chat` / `images`：所有模型都只走那一條。 |
| `OPENROUTER_HTTP_REFERER` | 空 | 選填，送給 OpenRouter 的 `HTTP-Referer`（也接受 `OPEN_ROUTE_HTTP_REFERER`）。 |
| `MINIO_ENDPOINT` | `MINIO_PUBLIC_ENDPOINT` 的值 | 上傳用的位址。可寫 `https://…` / `http://…`；沒寫 scheme 時，`localhost`、沒有點的主機名（docker service 名）與 IP 當 http，其他當 https。 |
| `MINIO_PUBLIC_ENDPOINT` | 空 | 簽發給審閱者的連結所用的位址（要連得到，例如 `https://s3.azndev.com`）。沒設時用 `MINIO_ENDPOINT` 簽，若那看起來是內部位址會在警告區提醒。 |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | 無 | 必填才會上傳。不會出現在頁面、log、result.json 或 manifest。 |
| `MINIO_BUCKET_NAME` | `line-bot` | bucket（必須已存在，這個工具不會建 bucket）。 |
| `MINIO_PREFIX` | `overlay-lab` | 物件 key 前綴：`<prefix>/<job-id>/<name>`；批次 manifest 在 `<prefix>/batch-<batch-id>/manifest.json`。 |
| `MINIO_URL_EXPIRY_HOURS` | `168` | 簽名連結有效小時數，限制在 1–168（S3 簽章連結最長 7 天）。 |
| `MINIO_REGION` | `us-east-1` | 簽章用的 region（與正式 bot 相同的變數）；有給的話簽連結不用連網。 |
| `LAB_TOKEN` | 空 | 設了之後 `/lab`（以及舊的根路徑 `/api`、`/files`）的每個請求都要帶 `?token=` 或標頭 `X-Lab-Token`，否則 401。沒設就不設防。`/v1` 與 `/healthz` 不看它。 |
| `SERVICE_TOKEN` | 空 | 每個 `/v1/*` 請求要帶的標頭 `X-Service-Token`。**沒設時 `/v1` 一律回 503**，絕不開放。 |
| `BIND_HOST` | `127.0.0.1` | 綁定位址。正式環境（Java 容器內）只綁本機；單獨用 compose 跑時固定 `0.0.0.0`。 |
| `PORT` | `4041` | 對外埠；優先於 `LAB_PORT`。不是 1–65535 的整數就用 4041。 |
| `LAB_PORT` | `4041` | 舊名稱，仍然有效（`PORT` 沒設時用它）。 |
| `OPENROUTER_MONTHLY_BUDGET_USD` | `15` | 每個日曆月（Asia/Taipei）OpenRouter（現金）的上限，USD；`0` = 不設上限；用完就不再跑 Mode B。見「預算」。 |
| `ANTHROPIC_MONTHLY_BUDGET_USD` | `80` | 每個日曆月 Anthropic（Claude 的費用估算）的上限，USD；`0` = 不設上限；用完整個 `/v1` 請求回 `status: error`、`reason: anthropic_budget`。 |
| `SEND_MIN_USABILITY` | `0.60` | `warned` 的下限：判定沒過的圖，judge 可用度 ≥ 它就附圖（加警語），低於它改傳譯文（`text_only`）。0–1。 |
| `V1_DEADLINE_S` | `210` | 一個 `/v1/translations` 請求從開始算起的總時限（秒）；超過後不再開始新的工作。 |
| `V1_RETENTION_MINUTES` | `15` | `/v1` 的記錄與圖片保留幾分鐘（背景清掃刪除）。 |
| `MODE_B_MIN_REMAINING_S` | `90` | Mode B 開始時至少要剩的秒數，否則跳過（`reason: deadline`）。 |
| `MIN_ROTATED_COLUMN_PX` | `16` | 目標語言不是中日韓時，欄寬（字高）小於它的直排 unit 保持原樣（`skipped: column too narrow`）。 |
| `LAB_MOCK` | `0` | `1` = 不打任何網路（`/v1` 也是），改用內建 fixture（Mode B 直接回傳輸入圖，Vision 用內建字框，Claude 與 judge 用罐頭答案，上傳改用記憶體假物件）。 |
| `LAB_DATA_DIR` | `./data` | 上傳與輸出（`<job-id>/`、`batch-<batch-id>/` 底下）、`/v1` 的記錄（`v1/<request_id>/`）與預算檔（`budget.json`）；容器內固定 `/app/data`。 |
| `LAB_INK_FRACTION` | `0.40` | 取文字顏色時，用字框內「離背景最遠」的這個比例的像素。細小的字顏色會偏淡，調小（例如 `0.2`）會比較深。 |
| `LAB_FONT_CJK`、`LAB_FONT_CJK_BOLD`、`LAB_FONT_LATIN`、`LAB_FONT_LATIN_BOLD` | 自動尋找 | 選填，指定字型檔。自動尋找的順序：拉丁字 Noto Sans → DejaVu Sans → Liberation／FreeSans；中日韓 Noto Sans CJK。 |

安全：lab 頁面只給你自己用。每張上傳上限 20 MB，只收 jpg / png / webp，輸出只從 `LAB_DATA_DIR/<job-id>/` 提供；uvicorn 的 access log 已關閉（網址可能帶 `?token=`）；log 不會出現圖片內容、金鑰、OCR 文字、譯文、檔名或簽名連結（連結含存取金鑰 ID 與簽章，只留在頁面、`result.json` 與 manifest）。

## 一次送出最多四張圖（批次）

- **頁面**：檔案欄可以多選 1–4 張（每張照舊的限制：jpg／png／webp、20 MB）。選好檔案後，每張圖多一列**語言選單**（預設是上面的目標語言；之後改上面的語言時，沒有自己動過的那幾列會跟著變，動過的不會）。一次送出就是一個**批次**：每張圖一個 job（Mode A 選用、Mode B 的每個模型 × 每次 run、驗證、judge、上傳，都和單張圖時一模一樣），頁面依送出順序畫出每張圖一個區塊。頁面預設：Mode A 開、Mode B 開且預選 `google/gemini-3-pro-image`、連跑 1 次、judge 開、輸出尺寸 `2K`、Mode A 重試開。
- **批次彙總表**（送出後頁面最上面的東西）：每張圖 × Mode A／每個 Mode B 模型一列，欄位：圖片、**語言**（第 6 輪）、Mode／模型、**判定徽章**、**judge 可用度**、**Anthropic（額度）** 費用、**OpenRouter（現金）** 費用、時間，最後一列是合計（幾列 pass、兩種費用的總和）。一列 Mode B 連跑多次時，判定與可用度取最佳的那一次（`verify.choose_attempt` 的順序），費用是所有已完成 run 的總和，時間是每個 run 的平均。Mode A 的費用只算在 Anthropic（規劃 + judge）；Mode B 的生成費用（OpenRouter 回應的 `usage.cost`）算在 OpenRouter，judge 費用算在 Anthropic。費用都是依價目表的估計，只算已完成的 run。
- **API**：`POST /api/batches`（multipart），欄位 `files`（1–4 個檔案，超過回 400，少於 1 個回 422）加上和 `POST /api/jobs` 一樣的表單欄位（`target_lang`、`mode_a`、`mode_b`、`image_models`、`output_size`、`verify`、`verify_details`、`runs`、`judge`，另有 `mode_a_retry`），另外**`target_langs`**（第 6 輪）：重複欄位，每個檔案一個值，依 `files` 的順序；沒有這個欄位、或比檔案少時，剩下的檔案用 `target_lang`（空字串也是 `target_lang`）；比檔案多、或不是支援的語言回 400，而且什麼都不會啟動。回傳 `{"batch_id": ..., "job_ids": [...]}`。**每一張都先檢查**（內容類型、大小、能不能解碼），有一張不合格就整批拒絕、什麼都不會啟動。`GET /api/batches/<batch-id>` 回傳批次的紀錄（含 `target_langs`：每張圖一個語言；`target_language`：不重複的語言清單，例如 `["en", "zh-TW"]`；`params.target_lang` 是批次的預設語言）加上每個 job 目前完整的文件（`jobs[]`，依送出順序，每個 job 的 `params.target_lang` 是自己的語言）、`summary[]`（彙總表的列，每列有 `target_lang`）、`totals`、`status`（所有 job 都結束才是 `done`）與 `publish`（批次 manifest 的上傳狀態）。`GET /api/batches/<batch-id>/manifest` 回傳本機留的批次 manifest。`POST /api/jobs` 照舊可以只送一張（不屬於任何批次）。
- **並行上限**（整個程序共用）：同時進行的圖片生成最多 4 個（`MAX_CONCURRENT_GENERATIONS`），**同時進行的 Claude 呼叫（Mode A 規劃與 judge 一起算）最多 4 個**（`MAX_CONCURRENT_CLAUDE`），所以四張圖不會一口氣丟出 20 個 Claude 請求；其餘排隊。每個 job 仍有自己的執行緒。
- **一個批次可以混多種語言**：每個 job 用自己的語言跑 Mode A／Mode B 與 judge，所以彙總表的判定與可用度只能在同一個語言的列之間比較。
- **批次 manifest**：每個 job 都跑完、也上傳完它自己的 manifest 之後，才上傳 `<MINIO_PREFIX>/batch-<batch-id>/manifest.json`（見「給審閱者的連結」）。上傳失敗只會是批次的一則警告，不會讓任何 job 或批次失敗；`LAB_MOCK=1` 用記憶體假物件，照樣產生。

## 視覺審查（judge）

**看什麼**：每次嘗試一次 Claude 呼叫（`app/judge.py`），Mode B 的每張輸出與 Mode A 的輸出同一套。它收到：

1. 原圖與輸出圖（兩張都縮到長邊最多 **1568 px**，用 `imaging.to_jpeg_base64` 編成 JPEG，以 `{"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", ...}}` 送出）；user 訊息把它們標成 `Image 1 is the ORIGINAL image.` 與 `Image 2 is the model's OUTPUT image.`；
2. 目標語言；
3. **來源 OCR 的文字行**（每行一列、依閱讀順序、最多 600 行、每行最多 400 字元）：只是輔助，OCR 會讀錯手寫與小字，數字要 judge 自己從圖上讀。**輸出圖的 OCR 文字不再送**（輸出的 OCR 仍會跑，只是為了 OCR 警告）。

來源的 OCR 失敗時 judge 只靠兩張圖照常運作；沒有輸出圖時 judge 標成 `skipped`。

**呼叫方式**：官方 `anthropic` SDK，和 Mode A 用同一個 client、同一組環境變數、同一個 server-side fallback 階梯（`claude_units.create_message`）；模型 `CLAUDE_JUDGE_MODEL`（預設 `claude-sonnet-5-5`）；`output_config={"format": {"type": "json_schema", ...}, "effort": "medium"}`；不帶 `thinking`；`max_tokens` 8000；timeout 180 秒。

**回答的 JSON**（pydantic 驗證；所有物件 `additionalProperties: false`、所有屬性必填；`usability` 與 `coverage` 超出 0–1 會被夾回去）：

- `usability`（0–1）：如果一個只看得懂目標語言的客人拿到輸出圖而不是原圖，它有多可用？1 = 需要的都讀得到、正確、位置對；0.5 = 約一半；低於 0.4 = 不能用。
- `coverage`（0–1）：來源文字中「出現在輸出圖、翻成目標語言、而且清楚」的比例（依大小與重要性加權）。只顯示與用來比較，**不影響判定**。
- `legibility_issues`：`{where, output_text, problem, major}`：輸出圖上不是真正目標語言文字或讀不出來的地方（編造的字、隨機字元、模糊、被切掉、重疊）；`major` = 客人會因此漏掉需要的東西（菜名、價格標籤、標題），裝飾、註腳、logo 字是 false。
- `structure_issues`：`{type, detail}`，`type` 是 `duplicated_row`、`missing_row`、`shifted`、`misaligned_marks`、`other`：重複、漏掉或移位的列／項目，以及手寫記號、價格、數量現在跑到別的項目旁。第 6 輪起每個有問題的列一筆，`detail` 點名那一列。
- `altered_numbers`：`{source, output, where}`：兩張圖之間不同的價格、日期、時間、電話、數量、年份（只差格式的不算）。
- `untranslated`：`{text, where, major}`：還是來源語言（或別的非目標語言）卻該翻的文字（品牌名、網址、e-mail、本來就是目標語言的除外）；標題或整欄內文是 major、註腳不是。
- `mistranslated`：`{output_text, source_text, problem}`：是真的字、但意思**會誤導讀者**（點錯菜、錯的服務、錯的價格標籤、意思相反）；可接受的改寫、較短的說法、少了一個形容詞都不列。只顯示，不影響判定。

**每個陣列只列問題**（第 6 輪）：沒有問題就是空陣列，**不可以**為了說「這個沒問題／有的／正確／可接受」而加一筆。第 5 輪的收據上，judge 在 `structure_issues` 寫了一筆 `missing_row`，`detail` 卻是「沒有任何一列漏掉或重複」，`rows` 因此判成沒過；它還在 `mistranslated` 列了一大串「可接受」的改寫。這是 prompt 的問題，所以修在 prompt（下面那份），不是靠程式去讀 `detail` 的文字。
- `summary`：一句英文。

**失敗不會讓嘗試失敗**：沒有金鑰、API 錯誤、被拒絕（refusal）、`max_tokens`、回傳的不是合法 JSON 或不符合 schema，都只會讓這次嘗試的 `judge` 標成 `unavailable`（頁面顯示原因），判定是 `unverified`、不會重試。

**費用與耗時**：每次嘗試記錄 tokens 與費用（價目表同 Mode A；其他型號只有 tokens）。兩張圖各約 1.6k tokens（一張圖約 寬 × 高 / 750 tokens，長邊 1568 px 時上限約 1.6k），加上 system prompt、OCR 行與約 0.5–1k 的輸出，預設的 `claude-sonnet-5-5`（2 / 10 USD 每百萬 tokens）**每次嘗試粗估 0.01–0.03 USD**——這是依價目表與典型文字量算的**估計，不是實測**，以頁面每次嘗試顯示的 tokens 與費用為準。這筆費用算在 Anthropic（額度）。

**它擋不住的**：judge 是個 LLM，看圖也會誤判（誤報或漏報），嚴格程度沒有在真實圖片上校準過；它讀小字、手寫字的準確度比 OCR 好但不是 100%。判定是提示不是定論，請看頁面的問題清單再看圖。

離線：`LAB_MOCK=1` 時 judge 用內建的罐頭答案（`usability` 1.0、沒有問題，完全不打網路）。

judge 的 system prompt 只定義在 `app/judge.py` 的 `SYSTEM_PROMPT`（測試會檢查下面這份與程式碼一致）：

```
You are the quality judge of an image-translation experiment. An image model, or a program, was told to redraw an image so that every piece of text in it is translated into the target language and everything else stays exactly the same. You see both pictures: Image 1 is the ORIGINAL and Image 2 is the OUTPUT that you judge. You also get the text that OCR read from the original, an aid only: it can contain reading errors, especially for handwriting and small print.

Judge what is actually drawn in Image 2, not what you would expect to be there: read its text the way a customer would, from the pixels. Be strict about garbled text and changed numbers. Compare tables and lists row by row. Never report something you cannot point to in Image 2.

Work in this order and put the results into the JSON fields:

1. Compare the two images region by region. For a table or a list compare item by item: every row of Image 1 must be in Image 2 exactly once, in the same order, with the handwritten marks, prices and quantities next to the same item.

2. usability: a number from 0 to 1. If a customer who only reads the target language got Image 2 instead of Image 1, how usable is it? 1 means everything they need is readable, correct and in the right place; 0.5 means about half of it is; below 0.4 means it is not usable.

3. coverage: a number from 0 to 1, the share of the source text that is present in Image 2, translated into the target language and legible. Weigh text by its size and importance: a missing title or a column of body text counts for more than a footnote.

4. legibility_issues: text in Image 2 that is not real target-language text or cannot be read: invented or scrambled words, random characters, pseudo-text, blurred, cut-off or overlapping text. For each give where (the region in words, for example "price pill of the second row" or "top-right card"), output_text (what you can read there, copied as it appears, or an empty string when nothing is readable), problem (a few words) and major: true when a customer would miss something they need (a dish name, a price label, a heading), false for decoration, footnotes and logo lettering. Do not list brand names, names of people or places, URLs, e-mail addresses or numbers here.

5. structure_issues: one entry for each row or item that is wrong. type duplicated_row (a row or item appears twice), missing_row (one is missing), shifted (items moved so that the layout no longer matches), misaligned_marks (handwritten marks, prices or quantities now sit next to a different item than in Image 1) or other; detail names the wrong row or item in a few words.

6. altered_numbers: prices, dates, times, phone numbers, quantities and years that differ between Image 1 and Image 2: source and output as written, and where. Read them from the images; the OCR text is only an aid. A difference of formatting alone (12:30 and 12.30, 1,200 and 1200) is not an alteration.

7. untranslated: text in Image 2 that is still in the source language (or in another language that is not the target language) although it should have been translated: text, where and major. Brand names, URLs, e-mail addresses and text that was already in the target language are not issues. A title or a column of body text is major, a footnote is not.

8. mistranslated: text in Image 2 that is real target-language text but whose meaning would mislead a reader of the output, for example a wrong dish, a wrong service, a wrong price label or a reversed meaning: output_text, source_text and problem (a few words). Acceptable paraphrases, shorter wordings and omitted adjectives are not problems: do not list them.

9. summary: one sentence in English on the overall result.

Every array lists problems and nothing else: when nothing is wrong in a category its array is empty. Never add an entry to say that something is fine, present, correct or acceptable. Return only the JSON object that matches the schema.
```

## 判定規則（verdict）

判定來自 judge 的紀錄（`app/verify.py`），五項檢查，嚴重程度由上往下：

| 檢查 | 沒過的條件 |
| --- | --- |
| `numbers` | `altered_numbers` 不是空的 |
| `rows` | **只有 Mode B 計**（Mode A 的字畫在原位、結構不會變，`structure_checks=False`，judge 的列問題只當提醒；第 7 輪）。有任何 `structure_issues` 的 `type` 是 `duplicated_row`、`missing_row` 或 `misaligned_marks`（`shifted`、`other` 不算），而且它的 `detail` 不是空的（第 6 輪的保險：沒有點名任何一列的條目不算；程式**不會**解析 `detail` 的文字，所以一筆有長篇 `detail`、內容卻是「沒有問題」的條目仍然算，那要靠 judge prompt 擋） |
| `garbled` | 有任何 `legibility_issues` 的 `major` 為 true |
| `partial` | 有任何 `untranslated` 的 `major` 為 true |
| `score` | `usability` < `USABILITY_MIN`（常數，預設 0.80；剛好 0.80 算過） |

- 判定 = 全部都過是 `pass`；否則是沒過的檢查依上表順序用 `+` 連起來（例如 `numbers+garbled`、`rows+score`）。
- `unverified`（灰）：judge 是 `unavailable` 或 `skipped`（或 judge 被關掉），**排在所有判定之下**，**不會重試**。
- **次要問題**（`major: false`）與 `mistranslated` 會顯示在頁面與 manifest，**永遠不會讓判定失敗**。
- 徽章在有 judge 的可用度時會附上它，例如 `pass · 0.97`、`garbled+score · 0.35`。

**優先順序（選哪一次、哪一個 run 是最佳）**：先依序比 (numbers 過、rows 過、garbled 過、partial 過、score 過)，前面的項目優先，每一項「過」的贏「沒過」的：所以**改了數字的那一次絕不會贏過沒改數字的那一次**；數字都沒改時，有重複／漏掉的列的不會贏過沒有的；依此往下到 score。五項的結果都相同時比 judge 的 `usability`（高者勝），再比 `coverage`（高者勝），都相同就留較早的那一次（較小的 run 編號、較早的嘗試）。`unverified` 比任何有判定的都低。一共 32 種判定，測試逐對檢查過。

**一次自動重試**：只有「第一次的判定不是 `pass`、而且判定真的有結果（不是 `unverified`）」才重試，最多一次。Mode B：同一個模型、同一張原圖，prompt 加上第二段（見「Mode B 的 prompt」）；Mode A：再請 Claude 規劃一次（見「Mode A」）。重試失敗（例如 API 錯誤）時沿用第一次的結果並顯示原因。**兩次的圖與判定都保留**，主要顯示的是「比較好的那一次」（上面的優先順序），另一次收在折疊面板裡。費用與耗時是兩次加總。

## OCR 警告（只提醒，不影響判定）

第 3、4 輪的 OCR 檢查仍然會算、也會存進 `verification.ocr_warnings`（頁面是收合的「OCR 警告」面板、manifest 有簡版），但**不再影響判定與重試 prompt**：Vision 會把很小的字與手寫字讀錯（這正是第 4 輪失敗的主因），所以它們只當提示。警告代碼 `warnings`：

- `digits-missing`：來源圖（高信心）的 2 位以上數字在輸出 OCR 裡找不到；
- `digits-unverified`：低信心（< 0.75，手寫或小字）的來源數字在輸出 OCR 裡找不到（5 位以上容許差 1 個數字，差 1 的記為 `near`、連警告都沒有）；
- `leftover-ratio`：輸出 OCR 裡來源語言的字的比例超過 8%；
- `leftover-big-glyph`：輸出裡有一個來源語言的字比輸出字高中位數大 2 倍以上（標題沒翻比註腳沒翻嚴重得多）。

規則細節（不變）：

- **數字**（`digits_ok`、`missing_digit_runs`、`unverified_digit_runs`、`digit_runs`）：從來源圖 OCR 的每個字取出所有「連續 **2 位以上**的數字」，兩個數字之間可以夾**一個** `- : / . ,`（`12:30`、`0912-345-678`、`1,280.50`、`2024/05/03` 各是一組），去掉分隔符後比對；全形與其他文字的數字先轉成 0–9。輸出端是「輸出圖 OCR 全文的所有數字串接起來」的字串，每組**硬**的來源數字都必須是它的子字串，所以重新排版不會誤報。每組數字的信心是讀出它的那些字的 Vision 字信心中最高的一個：< 0.75 是**軟**、≥ 0.75（或沒回報，值為 0）是**硬**。同一組數字在來源出現幾次都只算一組。限制：2–3 位的短數字在數字很多的輸出裡很容易「剛好」出現。
- **來源語言殘留**（`leftover_ok`、`leftover_ratio`、`leftover_words`、`big_leftover_words`）：來源語言 = 來源字詞的 Vision 偵測語言多數決（沒有偵測結果時改用文字 script 判斷；日文與中文共用漢字，多數決是 `zh` 但含假名的詞佔「漢字／假名詞」10% 以上時視為日文）。輸出端統計「語言是來源語言」的字，**不算**純數字、純標點、單一字元、網址與 e-mail，以及整個字都是用目標語言的文字寫的字。`leftover_ratio` = 殘留字數 ÷ 來源圖中「來源語言的字」的數量，≤ 0.08 才算比例過關；`vision.py` 為每個字記錄 `height`（字框短邊），殘留字高於輸出所有字高中位數的 2 倍（剛好 2 倍不算）就是 `leftover-big-glyph`。

## Mode B 的 prompt（v2）

整張圖的 prompt 只定義在 `app/openrouter.py` 的 `PROMPT_TEMPLATE`（測試會檢查下面這份與程式碼一致）：

```
Translate all the text in this image into {language} and output the edited image only.

Hard rules:
1. Translate EVERY piece of text into {language}: every line of multi-line titles, headings, table cells, labels, captions, footers and small print. Do not leave any line in the source language, unless it is a brand name, a URL, an e-mail address or already in {language}.
2. NEVER change, "correct", reformat or re-draw digits, numbers, prices, dates, times, phone numbers or handwritten marks: reproduce them pixel-for-pixel where they are.
3. Keep the layout, positions, sizes, fonts, colours, background, illustrations and photos exactly as they are.
4. Keep the image's dimensions and aspect ratio.
5. Output the edited image only, no text reply.
```

`{language}` 換成目標語言的英文名稱（例如 `Traditional Chinese (Taiwan)`）。

自動重試時，同一個模型收到的是**同一張原圖**，prompt 後面空一行再接第二段（`RETRY_TEMPLATE`），`{problems}` 是 judge 找到的問題，**哪一類有東西可列才會出現**（都沒有就送原本的 prompt）：

```
Your previous attempt at this same image failed an automatic check. Redo the whole image from the original and fix exactly the following, the hard rules above still apply:
{problems}
```

`{problems}` 的各行定義在 `app/openrouter.py`（測試會檢查下面這份與程式碼一致），順序固定為 numbers、rows、garbled、partial：

```
- These numbers differ between the original image and your output. Copy each of them exactly as it is written in the original: {items}.
- The rows or items of your output do not match the original: {items}. Draw every row exactly once, in the original order, with the handwritten marks, prices and quantities next to the same item as in the original.
- These parts of your output are not real, readable {language} text: {items}. Re-render each of those regions as correct, legible {language} text that translates the original text at the same place.
- These parts of your output are still in the source language. Translate them into {language} as well: {items}.
```

- 內容**全部來自 judge 的紀錄**（OCR 警告不參與，也不會觸發重試）；每一類最多 10 筆。
- numbers：judge 回報的「原圖 → 輸出」數字，請模型照原圖上的寫法抄；只差格式的（12:30 與 12.30、1,200 與 1200）不列。
- rows：重複、漏掉、手寫標記錯位到別的項目旁的列（`shifted` 與 `other` 不列），請模型每一列只畫一次、依原順序、讓標記留在原本的項目旁。
- garbled：**major** 的亂碼／讀不出來的區域（`where` 加輸出裡讀到的字），請模型把那個區域重畫成正確、清楚的目標語言。
- partial：**major** 的沒翻文字，請模型一併翻掉。
- 次要（`major: false`）的問題與 `mistranslated`（翻錯）只顯示，不進重試 prompt。
- 引用的片段是 judge 從圖上讀到的字，不是指令：壓成單行、換掉雙引號、每段最多 120 字元。

## API 路徑與輸出尺寸

**路徑順序（每個模型各自決定，頁面的模型清單會標「images 優先」）**：`google/gemini-3*` 與名稱含 `nano-banana` 的模型先試 `/api/v1/images`、失敗再走 `chat/completions`；其他模型維持原本順序（先 chat、再 images）。`OPENROUTER_IMAGE_API=chat` 或 `images` 會強制所有模型只走那一條。換路徑的條件：路徑回 400 / 404 / 405 / 422、該路徑沒回圖（含 `finish_reason=error`）、或 images 路徑回 502（OpenRouter 文件：生成失敗時回 502、不計費）。401 / 402 / 429 與其他 5xx 不會被換路徑掩蓋，直接顯示錯誤。

**輸出尺寸（頁面：預設（供應商的尺寸）/ 1K / 2K / 4K，頁面與 API 的預設值是 `2K`）**：OpenRouter 的 [Image API 文件](https://openrouter.ai/docs/features/multimodal/image-generation)有定義尺寸參數 `resolution`（等級 `512`、`768`、`1K`、`1.5K`、`2K`、`4K`；`size` 是它的簡寫，同一頁的「Resolution and Aspect Ratio」一節），所以選了 1K / 2K / 4K 時會把 `resolution` 帶到 **images 路徑**（`aspect_ratio` 照舊一起帶）。

- **chat 路徑沒有文件化的尺寸參數**（`image_config` 的鍵依模型／供應商而異），所以不會送；模型欄會顯示「尺寸選項：2K（未套用：chat 路徑沒有文件化的尺寸參數）」。
- 供應商拒絕 `resolution`（400 / 404 / 422）時，會先拿掉它再試一次（再不行才拿掉 `aspect_ratio`），欄位顯示「未套用：供應商拒絕 resolution，已略過」，API 路徑欄位會寫 `images (no resolution)`。
- 文件說各模型實際支援的值要看 `GET /api/v1/images/models` 的 `supported_parameters`；這個工具不預先查，以「帶了、被拒就拿掉」處理。
- **每個模型欄一律顯示實際輸出尺寸與長寬比差**（影像模型常常不照原圖長寬比輸出，差越多代表版面越不可能對得上）。要看哪個模型真的給了更大的圖，看這一行。

## Mode A（第 5 輪升級）

第 2 輪的 Mode A 被擱置，因為圖不好看：

- 擦除步驟在收據上**塗出大片黑灰色髒斑**：膨脹後的遮罩伸進黑色的價格格子與菜名旁的表格線，OpenCV inpaint 把那些黑色拉進白色的格子裡；logo 字變成兩行普通字；比來源長的譯文被縮到看不見；
- 美髮價目表：字縮到小得看不見、深色小標籤上的字糊成一團、拉丁字用 DejaVu 完全不像原本的字體；
- 海報：標題換行後單獨一個字在第二行、有些項目符號變粗或位移。

這一輪的流程：Vision 字框 → **Claude 規劃**（分組、翻譯、`short`、對齊、粗細、重要性）→ 幾何 → **逐 unit 量背景** → **排版（可讀優先；決定哪些 unit 要動）** → **擦除** → 畫字 → **judge** → 判定不是 `pass` 時**帶著 judge 的發現再規劃一次** → 兩次都留著、`verify.choose_attempt` 選一次顯示。

### 擦除不再髒（`erase.py`）

每個要翻的 unit 各自決定一種方法，記在 unit 的 `erase_method`：

- 先在 unit **自己的（未膨脹）字框**上量背景：取框內的主色（最常出現的顏色；若第二名也佔 15% 以上，例如粗體或反白字，就挑和框外一圈較接近的那個），把離主色超過 `INK_DISTANCE`（40，RGB 距離）的像素當墨水，其餘是背景像素。
- **`flat`（平坦填色）要框內與環帶都同意**（第 6 輪）。框內：背景像素的每通道標準差 ≤ `FLAT_STD_MAX`（10，0–255 的尺度）、背景像素夠多、墨水不超過框的 `MAX_INK_SHARE`（75%）。環帶：unit 的擦除範圍（膨脹後的框）外面一圈寬 `RING_PX`（6 px）的帶狀區域（裁在圖內；其他字的框不採樣；與紙色差超過 `INK_DISTANCE` 的像素，例如旁邊的黑格子、表格線、別的字，也不算證據）。環帶要同時滿足：每通道標準差 ≤ `FLAT_STD_MAX`；中位數與框內顏色的 RGB 距離 ≤ `RING_MATCH_MAX`（8）；沿閱讀方向的兩端（橫排是左右、直排是上下）的中位數，彼此距離的一半 ≤ `RING_MATCH_MAX`（長標籤底下的紙從一頭到另一頭有明暗時，單一顏色一定會在某一端露出方塊）。全部過了才是 flat，而且**填的顏色是環帶中位數**（`fill_color`），不是框內的主色：框內的主色會被字的暈邊與 JPEG 雜訊拉偏幾階，這就是第 5 輪收據底部和紙張不同色的灰色方塊。邊緣羽化 `FEATHER_SIGMA`（1.5，第 5 輪是 1.0）。環帶沒有任何可用的像素（框四周都是別的字或別的顏色）時沒有證據，只看框內，`erase_reason` 寫 `box only: no usable ring`。
- 框內雜訊稍高（超過 `FLAT_STD_MAX`、但不超過 `BOX_SPREAD_WITH_RING_MAX` = 16）時，**環帶乾淨又同色**也算 flat（`erase_reason`：`ring is clean and agrees; the box noise is glyph halo`）。原因：大標題的字很粗，暈邊讓框內「非墨水像素」的標準差剛好超過 10（第 5 輪的海報標題是 10.8–12.5），所以整排標題走 inpaint，Telea 在那一大片擦除範圍裡拖出橘色與白色的髒斑；周圍的米色紙明明是平的。這一條是第 6 輪相對於「框內與環帶都過」的唯一放寬，只在環帶乾淨且同色時才生效。
- **`inpaint`**：其他情況用 OpenCV Telea inpaint（和以前一樣，受顏色護欄），`erase_reason` 說明哪一關沒過：`box not flat`（框內不平坦）、`ring too mixed`（環帶裡沒有一個顏色佔得夠多）、`ring not flat`（環帶雜訊）、`ring colour differs from the box`（環帶與框內不同色，例如框內是白、四周是米色）、`ring ends differ (shading)`（紙有明暗）。漸層、照片、紋理、明暗不均的紙都走這條。
- 兩種方法的擦除範圍都有**顏色護欄**：字框膨脹時只會長進「顏色在該 unit 背景色 `GUARD_TOLERANCE`（56，紋理背景會依標準差放寬）以內」的像素，**絕不會長進黑色格子、表格線或別的顏色的相鄰元素**；inpaint 還會多留 3 px 的緩衝（Telea 會讀遮罩周圍那麼遠的像素）。沒被翻譯的字（保留的價格、沒列進任何 unit 的字）照舊受保護。
- unit 報告裡有 `erase_method`、`erase_bg`（框內的背景色）、`erase_spread`、`erase_ink_share`、`erase_px`，以及第 6 輪新增的 `erase_reason`（為什麼是這個方法）、`erase_fill`（flat 實際填的顏色）、`erase_ring_color`（環帶中位數，沒有環帶是 `null`）、`erase_ring_spread`、`erase_ring_gap`、`erase_extension`（往旁邊長的那一段：`filled` 或 `skipped`，沒長是 `null`）；頁面的 unit 表顯示擦除方法與原因。

### 可讀的排版（`layout.py`、`render.py`）

「可讀」= 需要的字級至少是來源字高的 `MIN_FIT_RATIO`（0.70）倍。排版**先於擦除**決定（因為「太小的次要 unit 保持原樣」要在擦除前就知道），順序：

1. 完整譯文放進原框；
2. 完整譯文放進**往旁邊長的框**：沿著 unit 的閱讀方向算「空位」（從框往外到另一個 unit 的框、沒被翻譯的字的框、或圖邊緣為止，各留字高 1/4 的間隙），再只算到背景維持同色的那一段（框外取樣，離背景色超過容許值的欄就停），最多長到原長度的 `MAX_GROW`（1.5）倍；依 planner 的 `align` 長：靠左往右長、靠右往左長、置中兩邊等量。只長到剛好可讀的最小一格（分 4 步找）。**只有 `flat` 背景的 unit 才會長**（`inpaint` 的不長），長出來的那一段也一併填成背景色。第 6 輪起，那一段要通過和環帶同一個測試（每通道標準差 ≤ `FLAT_STD_MAX`、中位數與填色的距離 ≤ `RING_MATCH_MAX`），沒通過就縮短到通過為止，最多縮到不長（改用 `short` 或縮小字級）：那是和填色略有不同的紙，填下去就是一塊方塊；真的要填的時候再測一次，沒過就不填（保留原本的紙）。兩個 unit 面對面時，先處理的那個長了之後，後面的把它當障礙；
3. `short`（planner 給的短版）用同樣兩種框再試一次，`text_used` 記 `full` 或 `short`；
4. 都不可讀：**次要**（`importance: minor`）的 unit 保持原樣、報告為 `skipped: too small`（`skip_reason: "too small"`，不擦除）；**主要**的用較短的那段文字縮到最小 10 px（和以前一樣），報告 `readable: false`。
- **直排**：目標語言不是中日韓的直排 unit，譯文**轉 90 度（順時針，由上往下讀）、一行一欄**（`vertical_rl` 第一行在最右欄、`vertical_lr` 在最左欄）。中日韓目標的直排照舊保持直立。欄寬（字高）小於 `MIN_ROTATED_COLUMN_PX`（16 px）的直排 unit 在拉丁目標語言的圖裡**保持原樣**（`skipped: column too narrow`，第 7 輪）。
- **字型**：拉丁字優先用 **Noto Sans**（Docker 映像裝 `fonts-noto-core`），找不到再用 DejaVu；中日韓用 Noto Sans CJK。粗體 = planner 說 `bold` **或**筆畫粗細估計說粗（不再因為角色是標題就硬畫粗體）。
- **孤字規則**（第 7 輪改過）：標題與小標（`role` 是 `title`／`heading`）換行後最後一行不可以是 2 個以下的中日韓字或一個很短的詞（拉丁字 ≤ 4 字元）；譯文有空白或中日韓標點時優先斷在那裡（見「第 7 輪的修正」）。先用稍窄的第一行重排（每次窄 4%、最多 28%），不行就改用稍小的字級，一路試到「可讀」的下限為止（`min_size`），到下限還是有孤字就接受它。
- unit 報告裡有 `text_used`、`readable`、`fit_ratio`（字級 ÷ 來源字高）、`min_fit_px`、`grow`（往前／往後長了幾 px）、`lane`（空位，已受平坦背景限制）。

### 同組同字級、同粗細（第 6 輪，`pipeline_a.render_units`）

同一個表格欄、清單或一排標籤的各列要長得一樣。規劃給每個 unit 一個 `group`（短 id，例如 `menu-names`、`menu-prices`、`section-headings`；獨立的 unit 是空字串）。渲染器對每一組：

- **粗細**：組內的 `weight` 都一樣時用規劃的；不一樣時取全組（規劃說 `bold` **或**筆畫估計說粗）的多數，平手算 `regular`；全組一個值（所以筆畫估計不會讓同組的某一列單獨變粗）。
- **字級**：先照原本的方式各自排好；然後全組的字級取「用完整譯文排進去、而且可讀的」那些 unit 的**最小字級**（沒有任何一個是這樣時，取可讀的那些的最小），但不低於全組字高中位數的 `MIN_FIT_RATIO`（0.70）。每個 unit 再用剛好這個字級重排一次：完整譯文放不下就用 `short`；還是放不下的 unit 保留自己較小的字級，報告 `group_size_broken: true`。
- **對齊**：照規劃每個 unit 自己的 `align`。
- **第 7 輪**：自己的字框比全組字高中位數小的 unit，用「高度（直排是寬度）等於全組中位數、中心不變」的框排版，才能達到共同字級；unit 報告記 `frame_height_from_group` 與 `group_frame_height`。
- 沒有 `group` 的 unit 完全不受影響。unit 報告多了 `group`、`group_size`、`group_weight`、`group_size_broken`（也在 `A_a<j>_units.json` 裡）；頁面的 unit 表有一欄，Mode A 欄有一行「群組」。

### Claude 規劃（`claude_units.py`）

模型還是**絕不輸出座標**。每個 unit 的欄位（schema 都必填，`validate_units` 仍然嚴格：多餘欄位、未知 word id、重複 id、列舉值錯誤都會被拒絕）新增了四個：

- `short`：譯文的短版（目標是 `translation` 字元數的 60% 以內；標籤與表格格子可以用縮寫），已經夠短就是空字串；
- `align`：`left`／`center`／`right`，和來源文字的排法一致（要看圖判斷）；
- `weight`：`regular`／`bold`，和來源文字看起來的粗細一致；
- `importance`：`major`／`minor`（標題、品名、價格、說明是 major；裝飾、註腳、logo 字是 minor）。

第 6 輪再加一個必填欄位 `group`（見上一節）。prompt 也改了：`notes` 除非真的不確定否則是空字串、`short` 已經夠短就是空字串（不要灌水）；每個 OCR 字都要分到某個 unit，只有純裝飾才可以不列，小卡片、旁註、直排的欄都算；非中日韓目標的圖裡，每一欄直排的中日韓字就是一個 unit。

prompt 另外說明：表格的列要維持「一個格子一個 unit、順序和圖上一致」、價格與純數字的格子是 `keep`、品牌名與 logo 是 `keep`、非中日韓目標的直排 unit 會被轉 90 度畫所以譯文要短（並給 `short`）、對齊與粗細要看圖決定。完整的 system prompt（只定義在 `app/claude_units.py` 的 `SYSTEM_PROMPT`，測試會檢查下面這份與程式碼一致）：

```
You prepare an image for in-place translation. You receive the image and a list of OCR words (id, axis-aligned box, text). Group the words into translation units and translate each unit into the target language. You output units, never coordinates: our code computes the geometry from the word boxes, erases the original glyphs and draws your translation in place.

What a unit is:
- A unit is what a human translator would translate as one piece: one line of a list, one bullet, one heading, one paragraph, one dish name in a vertical column, one price tag, one table cell, one button label.
- Never split a sentence across units. Never merge different rows of a table, different list items or different bullets into one unit.
- Table rows stay one unit per cell, in the same order as in the picture (left to right, top to bottom): never merge two cells of a row or two rows of a column, and never move text from one cell to another.
- Vertical CJK columns are one unit per column, unless the columns are clearly one continuous paragraph. When the target language is not Chinese, Japanese or Korean, every vertical column of CJK characters is always its own unit (direction vertical_rl, columns ordered right to left), even when neighbouring columns look related, such as the dish names under the price tags of a small card: one column, one unit, one translation.
- Use the image to decide grouping, reading order and direction. OCR text can contain errors; write source_text as it actually reads in the image.

Coverage: assign EVERY word of the list to a unit. Go through the whole picture, small cards, side notes, captions, footers, hotline and address lines, stamps, handwritten notes and vertical columns included, and make sure that each id of the list is in exactly one unit. Leave a word out only when it is pure decoration (a flourish, artwork lettering that carries no information). Anything a customer could read as information is a unit: translate it, or mark it keep when it must not be translated. An answer that leaves out many of the ids is wrong.

Fields:
- word_ids: ids taken from the word list, in reading order. Every id appears in exactly one unit, never in two. Words you do not list are left untouched in the image: that is only for pure decoration (see Coverage); logos and brand names are listed as keep units.
- source_text: the unit's text in the source language.
- translation: natural, idiomatic target-language text for this role. Keep it short for labels and tags (the space available is the size of the source text). Keep numbers, units and currency symbols. Do not add explanations, quotes or notes inside the translation.
- direction: "horizontal", "vertical_rl" (columns read top to bottom, columns ordered right to left) or "vertical_lr" (top to bottom, columns left to right).
- rotated: true when the unit's text runs along a page that is turned (photographed sideways or upside down, or text set at an angle); direction then describes how it reads in the text's own frame.
- role: title | heading | body | label | price | number | other.
- keep: true for things that must not be translated: prices and every other price-like or number-only cell, phone numbers, dates and times, URLs, e-mail addresses, brand names and logos, and text that is already in the target language. For keep units set translation equal to source_text and short to an empty string.
- notes: an empty string unless something is really uncertain, then a few words. Never use it to comment on your work or to repeat the translation.
- short: a shorter alternative translation of the same unit, at most about 60% of the characters of translation (abbreviations are fine for labels and table cells). Our renderer draws the full translation first and switches to short only when the full one would have to be drawn much smaller than the source text. Use an empty string when the translation is already short: never fill short with a copy of the translation or with filler.
- align: left | center | right, as the source text is set in the picture (a heading centred over a column is center, a column of right-aligned amounts is right).
- weight: regular | bold, as the source text looks in the picture.
- importance: major | minor. Headings, item names, prices and instructions are major; decoration, footnotes and logo lettering are minor. A minor unit whose translation cannot be drawn at a readable size is left untouched instead of being drawn tiny.
- group: a short id (for example menu-names, menu-prices, section-headings, bullets) shared by the units that belong to the same table column, list or run of labels and are set alike in the picture; our renderer draws every unit of a group in one font size and one weight. Use an empty string for a unit that stands alone. Do not put a heading and its body text, or two different columns, into one group.

Vertical text: when the target language is not Chinese, Japanese or Korean, a unit with direction vertical_rl or vertical_lr is drawn rotated by 90 degrees (reading top to bottom, one line per column), so keep its translation short and always give a short alternative.

Look at the picture, not only at the OCR text, to decide alignment and weight.

Return only the JSON object that matches the schema.
```

### 規劃的覆蓋與 token 預算（第 6 輪）

- **token 預算**：`claude_units.MAX_TOKENS` = 32000（第 5 輪是 16000）。收據的第一次規劃用了 6.2k output tokens 排好 38 個 unit，重試那次卻在約 100 秒之後撞到 16000 被丟掉。`stop_reason` 是 `max_tokens` 時錯誤訊息是 `Claude hit max_tokens=32000 before finishing the JSON (the call produced N output tokens); ...`（SDK 有給 usage 時才有括號）。第二次嘗試失敗時沿用第一次的結果並顯示 `retry_error`，這條路沒有改。SDK 對「非串流、`max_tokens` 很大」的請求在預設 timeout 下會直接拒絕；這個工具的 client 明確給了 timeout（`REQUEST_TIMEOUT_S`，600 秒），測試用真的 SDK 走一遍確認沒被擋。
- **覆蓋補翻**：`validate_units` 之後，如果超過 10%（`COVERAGE_MIN_SHARE` = 0.90 的另一面）的 OCR 字（有文字的字）不在任何 unit 裡，就**再呼叫規劃一次**：同一張圖、同一份 system prompt，user 訊息只列沒進 unit 的字，並說明這些字被漏掉了、必須分組翻譯、或標成 `keep`，每一欄直排是一個 unit。新的 unit 接在後面（已經用掉的 id 由驗證丟棄，有未知 id 的 unit 整個拒絕）。補翻只做一次；兩次呼叫的 tokens、費用、時間加總，同樣占 Claude 並行上限（4）的名額。補翻失敗（API 錯誤、`max_tokens`…）時保留第一次的結果，原因寫在 `planner_coverage.error` 與提醒區。
- 結果裡記錄：Mode A 的 `coverage_pass`（有沒有補翻）與 `planner_coverage`（`words`、`unlisted_before`、`unlisted_after`、`added_units`、`added_words`、`error`），每次嘗試各一份，也在 `A_a<j>_units.json` 裡。頁面的 Mode A 欄在補翻時多一行「覆蓋補翻：補翻 N 字（…）」。

### 一次重試（`MODE_A_RETRY`）

輸出經過和 Mode B 同一個 judge（同一份 prompt、同一種紀錄）。判定不是 `pass`（而且不是 `unverified`）就再呼叫一次規劃，user 訊息後面多一段（`claude_units.build_feedback`）：judge 找到的 major 亂碼／讀不出來的地方、major 沒翻的文字、被改的數字、結構問題（每類最多 10 筆，引用的片段壓成單行），加上渲染器報告「沒畫好」的 unit（`overflowed`、`truncated`、低於可讀字級、或 `skipped: too small`），以來源文字稱呼，並要求更短的譯文（與 `short`）或重新分組。重新渲染、再審一次，`verify.choose_attempt` 挑一次顯示；兩次都留著：

| 檔案 | 內容 |
| --- | --- |
| `A_a1.png`、`A_a2.png` | 每次嘗試的結果圖 |
| `A_a1_erased.png`、`A_a2_erased.png` | 只擦除、還沒畫字的圖（單獨看擦除品質） |
| `A_a1_units.json`、`A_a2_units.json` | 每次嘗試的 units（含擦除方法、用字、字級等結果） |
| `A_a1_verify.json`、`A_a2_verify.json` | 每次嘗試的驗證結果（judge 的完整紀錄與 OCR 警告） |

第二次呼叫失敗時沿用第一次並顯示 `retry_error`。環境變數 `MODE_A_RETRY`（預設 `1`）決定頁面勾選框的預設，API 的表單欄位 `mode_a_retry` 可以逐次覆蓋；沒有 judge 判定（judge 關掉、不可用）時不會重試。

## 怎麼讀結果

每個欄位上方有耗時；Mode A 另外拆成各階段。

**批次彙總表**：見「一次送出最多四張圖」。判定徽章的 tooltip 會逐項說明沒過的檢查。

**Mode B 欄（每個模型的每一次 run 各一欄）**
- 標題：模型 id（連跑多次時加 `· run k`）、狀態、判定徽章（附 judge 的可用度）。一個 run 失敗（錯誤顯示在該欄）不影響其他 run 與其他模型。
- `耗時（含驗證與重試）`、`費用（OpenRouter 現金，生成）`、`費用（Anthropic 額度，judge）`、`費用（合計）`、`tokens`：兩次嘗試的加總（有重試時括號內列出各次）；生成費用是 OpenRouter 回應的 `usage.cost`，回應沒有帶才顯示「回應沒有帶 cost」；judge 費用依 Anthropic 價目表估算。耗時是這個 run 自己做事的時間，不含排隊等生成名額的時間。
- `API 路徑`、`輸出尺寸（長寬比差 x%）`、`尺寸選項`：見「API 路徑與輸出尺寸」。
- 「驗證細節」面板：判定、五項檢查各自的結果與數量、可用度與完成度、judge 的耗時與費用與摘要，然後是 judge 列出的問題清單（亂碼／讀不出來、結構問題、被改的數字、沒翻、翻錯，並標出 major 或次要），最後是收合的「**OCR 警告**」面板（見上）；勾「顯示驗證細節」會預設展開，並在 OCR 警告裡多附上輸出圖的 OCR 全文（沒勾就不會把 OCR 全文留在結果裡）。每次驗證的 JSON 也存成檔案（`B<n>_<模型>_r<k>_a<j>_verify.json`，也會上傳）。
- 「嘗試 1／2」折疊面板：沒被採用的那一次（圖、API 路徑、尺寸、驗證細節；重試那次可看實際送出的 prompt）。
- 「模型附帶文字」是模型除了圖以外說的話（拒絕時通常在這裡）。

**各模型彙總（每個模型連跑 2 次以上時，在 job 的 `mode_b.summary`；頁面看批次彙總表即可）**：通過率（判定為 `pass` 的 run 數 ÷ 已結束的 run 數，失敗的 run 算沒過）、各次判定、平均總費用與時間（只算完成的 run）、最佳 run。怎麼讀通過率：3 次裡 3 次 `pass` 才算穩定；樣本只有 1–3 次，所以 3 / 3 不是保證。要比較兩個模型，請用幾張**不同的圖**、每張都跑 3 次。通過率只回答「驗證有沒有通過」，不回答「好不好看」。

**Mode A 欄**
- 標題旁有判定徽章；`總耗時`、`Vision`、`Claude 規劃`、`erase / render`、`審查耗時`、`tokens (in / out)`、`費用（Anthropic 額度）`（規劃 + judge，括號內分開列）、`units`（翻譯了幾個 / 共幾個 / Vision 的字數）、`覆蓋補翻`（有補翻才有：「補翻 N 字」）、`擦除方法`（flat／inpaint 各幾個）、`群組`（第 6 輪）、`用 short 的 unit`、`太小而略過`、`fallback 形式`（實際用的 Claude 呼叫形式：`default` = `fallbacks="default"` + beta `server-side-fallback-2026-07-01`；`array` = `fallbacks=[{"model":"claude-opus-4-8"}]` + beta `server-side-fallback-2026-06-01`；`none` = 不帶 fallbacks 的一般 `messages.create`；`mock`）。
- 「下載僅擦除版」：只做擦除、還沒畫字的圖。「驗證細節」同 Mode B。有重試時，沒被採用的那一次收在折疊面板（含重試時多送給 Claude 的那一段）。
- 提醒區：Claude 的 unit 被丟掉的原因、字清單過大、某個 unit 畫失敗、太小而略過的次要 unit。
- 原圖欄下的「疊上 Mode A 的 unit 框」：藍＝翻譯、綠＝保留（`keep`）、灰＝略過；用來一眼檢查 Claude 的分組對不對。
- 「Mode A 每個 unit」表：每個 unit 的動作、角色、重要性、**擦除方法**、**用字（full／short）**、字級（與可讀比例）、往旁邊長了多少；「Mode A units（JSON）」面板是完整欄位：`id`、`word_ids`、`source_text`、`translation`、`short`、`direction`（`horizontal` / `vertical_rl` / `vertical_lr`）、`rotated`、`role`、`align`、`weight`、`importance`、`group`、`keep`、`notes`；幾何 `frame`、`bbox`、`angle`、`size`、`glyph_h`；結果 `action`（`translated` / `kept` / `skipped`，後者含原因）、`font_size`、`layout`（`horizontal` / `vertical_cjk` / `vertical_latin`）、`lines`、`fitted`、`readable`、`overflowed`、`truncated`、`bold`、`text_used`、`text_color`、`bg_color`、`ink_bbox`、`erase_*`（含第 6 輪的 `erase_reason`、`erase_fill`、`erase_ring_*`、`erase_extension`）、`grow`、`lane`、`group_size`、`group_weight`、`group_size_broken`。`overflowed: true` 表示 10 px 還是放不進框，字會往框下方（或直排的欄尾）多出最多 30%，超過的部分以 `…` 截斷。

**比較時建議看**：批次彙總表的判定與可用度、judge 列出的問題（看圖，不是看 OCR）、版面與字體是否保留、顏色、直排／旋轉頁面的處理、輸出尺寸、兩種費用與耗時，以及模型有沒有改動不該動的圖形或文字。

## 給審閱者的連結（MinIO）

每個 job 全部跑完之後（不論有沒有錯誤），背景執行緒會把下面這些上傳到 `<MINIO_BUCKET_NAME>/<MINIO_PREFIX>/<job-id>/`（key 是 `<prefix>/<job-id>/<name>`）：

| name | 內容 |
| --- | --- |
| `original.<ext>` | 送出時的原圖 |
| `A_a<j>.png`、`A_a<j>_erased.png`、`A_a<j>_units.json`、`A_a<j>_verify.json` | Mode A 第 j 次嘗試（1 或 2）的結果圖、只擦除圖、units 與驗證（有勾 Mode A 才有） |
| `B<n>_<模型>_r<k>_a1.png`、`..._a2.png` | Mode B 第 n 個模型第 k 次 run 的第 1、第 2 次嘗試的圖 |
| `B<n>_<模型>_r<k>_a<j>_verify.json` | 該次嘗試的驗證結果（含 judge 的完整紀錄與 OCR 警告） |
| `manifest.json` | 這個 job 的 metadata 與**每個物件的簽名 GET 網址** |

**批次的 manifest**：一個批次的每個 job 都跑完、也上傳完之後，才上傳 `<MINIO_PREFIX>/batch-<batch-id>/manifest.json`（`schema_version: 3`，`kind: "batch"`）：`batch_id`、各時間、`target_language`（**清單**：這個批次用到的語言，不重複、依第一次出現的順序，例如 `["en", "zh-TW"]`，第 6 輪由字串改成清單）、`options`（Mode、模型清單、輸出尺寸、`runs`、`judge`、`judge_model`、`claude_model`、`mode_a_retry`）、`jobs[]`（每張圖一筆：`index`、`job_id`、`image_name`、`target_language`（這張圖自己的語言）、尺寸、`status`、`publish_status`、**那個 job manifest 的簽名網址** `manifest`，沒上傳成功的是 `null` 並在 `warnings` 說明）、`summary[]`（和頁面彙總表同樣的列）、`totals`（兩種費用的總和與 pass 的列數）、`warnings`，以及 manifest 自己的 `manifest.url`。上傳失敗不會讓批次或任何 job 失敗。

**job 的 `manifest.json`**（`schema_version: 3`）有：`job_id`、`batch_id`、`image_index`、各時間、`target_language`（這個 job 自己的語言，仍是單一字串）、`options`（模型清單、輸出尺寸、是否驗證、`runs`、`judge`、`judge_model`、`mode_a_retry`…）、`original`，`mode_a`（判定、`usability`、`coverage`、費用拆成 `cost_usd`／`judge_cost_usd`／`cost_anthropic_usd`／`cost_openrouter_usd`、`attempts[]`：每次嘗試的圖、擦除圖、units、驗證 JSON 的連結、判定、judge 紀錄、擦除方法與 `short` 的統計、重試時多送的段落…），`mode_b[]`（**每個模型一筆**），`objects[]`（`name`、`key`、`bytes`、`content_type`、`url`），`warnings`，以及 manifest 自己的 `manifest.url`。單一物件上傳失敗不會讓 job 失敗：那個物件在 manifest 裡只有 `error`、沒有 `url`，並出現在頁面的「提醒」裡。

`mode_b[]` 的每一筆：`model`、`route_preference`、`resolution_requested`、`runs_requested`，
- `summary`：`runs_done`、`runs_failed`、`passes`、`pass_rate`、`verdicts[]`、`mean_total_cost_usd`、`total_cost_usd`、`mean_elapsed_ms`、`best_run`、`best_verdict`、`best_image`（最佳 run 的圖與連結）；
- `runs[]`（**所有** run，每次一筆）：`run`、`status`、`error`、`api_used`、`verdict`、`failed_checks`、`usability`、`coverage`、`selected_attempt`、`elapsed_ms`、`judge_ms`、`usage`、`cost_usd`（生成）、`judge_cost_usd`、`cost_openrouter_usd`、`cost_anthropic_usd`、`total_cost_usd`、採用的圖 `image`，以及 `attempts[]`：每次嘗試的圖、驗證 JSON、`verdict`、`failed_checks`、`usability`、`coverage`、`judge`（`status`、`error`、`model`、`usability`、`coverage`、`legibility_issues`、`structure_issues`、`altered_numbers`、`untranslated`、`mistranslated`、`summary`、tokens、`cost_usd`）、`ocr_warnings`（警告代碼與缺少的數字、殘留的字）、路徑、尺寸、耗時、tokens、費用、送出的 prompt。

schema 1（第 3 輪）是每個模型一筆；schema 2 在中間多了 `runs[]`；**schema 3（第 5 輪）改了判定欄位、Mode A 的 attempts、費用拆分，並多了批次 manifest**。第 6 輪沒有升 schema：job manifest 的欄位意思沒變，批次 manifest 的 `target_language` 從字串變成清單（批次 manifest 從第 5 輪才有；只有一種語言時是只有一個元素的清單），請照清單讀。

頁面在每張圖的區塊顯示那個 job 的 manifest 簽名網址（連結有效 `MINIO_URL_EXPIRY_HOURS` 小時，預設 168 = 7 天），旁邊有「複製」鈕；上傳還在跑時顯示「上傳到 MinIO 中…」，沒設定時顯示缺哪些變數；批次 manifest 的連結在彙總表下方。同一個網址也在 `GET /api/jobs/<job-id>` 的 `publish.manifest_url` 與 `GET /api/batches/<batch-id>` 的 `publish.manifest_url`（log 裡只記上傳了幾個物件，不記連結）。manifest 也會留一份在本機（`LAB_DATA_DIR/<job-id>/manifest.json`、`LAB_DATA_DIR/batch-<batch-id>/manifest.json`）。

注意：

- **任何拿到連結的人都能下載**（原圖也在裡面，含圖上所有個資），到期前無法撤銷，只能刪物件。請只把連結交給要看的人。
- 清掉舊物件（先用 `mc alias set <alias> https://s3.azndev.com <ACCESS_KEY> <SECRET_KEY>` 設好別名；bucket 名稱以你實際的 `MINIO_BUCKET_NAME` 為準）：

  ```bash
  mc rm -r --force <alias>/line-bot/overlay-lab/                       # 這個工具上傳的全部（含批次 manifest）
  mc rm -r --force <alias>/line-bot/overlay-lab/<job-id>/              # 單一 job
  mc rm -r --force <alias>/line-bot/overlay-lab/batch-<batch-id>/      # 單一批次的 manifest
  mc rm -r --force --older-than 7d <alias>/line-bot/overlay-lab/       # 只清 7 天前的
  ```

## 直接打 API（不開頁面）

lab 的 API（路徑現在在 `/lab` 底下：`POST /lab/api/batches`、`POST /lab/api/jobs`、`GET /lab/api/jobs/<job-id>` …，舊的根路徑 `/api/...` 保留一個版本；下面仍用舊寫法）：

`POST /api/batches`（multipart，1–4 張圖）與 `POST /api/jobs`（multipart，1 張圖）：

| 欄位 | 預設 | 說明 |
| --- | --- | --- |
| `files`（批次）／`file`（單張） | 必填 | jpg / png / webp；批次 1–4 個 `files`，每個各自受 20 MB 與格式限制 |
| `target_lang` | `en` | `config.LANGUAGES` 裡的碼（見「語言」；lab 不收表外的碼）；批次的預設語言 |
| `target_langs`（只有批次） | 空 | 每個檔案一個語言（第 6 輪）：欄位可重複，依 `files` 的順序；沒有、或比檔案少時剩下的用 `target_lang`，空字串也是 `target_lang`；比檔案多或不是支援的語言回 400 |
| `mode_b` / `mode_a` | `0` / `0` | 至少選一個；頁面預設兩個都勾 |
| `image_models` | `OPENROUTER_IMAGE_MODEL`（`google/gemini-3-pro-image`） | 最多 4 個；欄位可重複，或用逗號分隔。舊的單一欄位 `image_model` 仍可用 |
| `output_size` | `2K` | `default`（不送 `resolution`，用供應商自己的尺寸）、`1K`、`2K`、`4K`，只套用在 images 路徑。沒填，或填空字串，都是 `2K` |
| `verify` | `1` | `0` = 不驗證、不審查、不重試（頁面沒有這個開關，一律開著） |
| `verify_details` | `0` | `1` = 在 OCR 警告裡附上輸出圖的 OCR 全文（頁面的「顯示驗證細節」） |
| `runs` | `1` | 每個模型連跑幾次，1–3；超出範圍回 400。每次是獨立的一輪（各自的重試、驗證、judge） |
| `judge` | `1` | `0` = 不跑視覺 judge（`verify=0` 時也不跑）；沒有 judge 就沒有判定（`unverified`）、不會重試 |
| `mode_a_retry` | `MODE_A_RETRY` | Mode A 的判定不是 `pass` 時再規劃一次；`1` / `0` 逐次覆蓋 |

批次回傳 `{"batch_id": ..., "job_ids": [...]}`，用 `GET /api/batches/<batch-id>` 輪詢（每個 job 的文件都在 `jobs[]`，`status: done` 之後 `publish.status` 還會從 `pending` → `running` → `done` / `skipped` / `error`）；單張回傳 `{"job_id": ...}`，用 `GET /api/jobs/<job-id>` 輪詢，`mode_b.results[]` 是每個模型**每次 run** 的結果（扁平、依模型再依 run 排序），`mode_b.summary[]` 是每個模型的彙總，`mode_a` 是 Mode A 的結果（含 `attempts[]`）。圖在 `GET /files/<job-id>/<name>`。

## 費用備註

- **兩種錢分開看**：**Anthropic（額度）** = Claude 的費用，由訂閱額度支付：Mode A 的規劃、兩個 Mode 的 judge；**OpenRouter（現金）** = 影像模型的生成費用（`usage.cost`）。批次彙總表與每個欄位都分開列。
- 每個 Mode B run = 1 次影像生成（判定不是 `pass` 會再多 1 次）+ 每次嘗試 1 次 judge。生成費用取決於模型與尺寸（OpenRouter 文件提到 images 路徑的定價可能依解析度分級，所以選大尺寸通常更貴），回應沒有帶 cost 的模型只顯示 tokens。**費用 = 圖片數 × 模型數 × 每個模型連跑的次數 × 每個 run 的 1–2 次嘗試**：4 張圖、4 個模型各跑 3 次最壞是 96 次生成，請小心。
- **judge** = 每次嘗試 1 次 Claude 呼叫（預設 `claude-sonnet-5-5`，2 / 10 USD 每百萬 tokens；`effort: medium`；兩張圖各約 1.6k tokens 加上 prompt 與 OCR 行），粗估 0.01–0.03 USD（**估計不是實測**）。不想付這筆錢就取消勾選「視覺審查」（或 `judge=0`），代價是沒有判定（`unverified`）。
- **Mode A** = 1 次 Vision `document_text_detection`（與 Mode B 的來源圖 OCR 共用，不會重複打）+ 1 次 Claude 規劃（圖 + 字清單，輸出 JSON）+ 1 次 judge；判定不是 `pass` 時再加 1 次規劃與 1 次 judge。超過 10% 的字沒進任何 unit 時，還會多 1 次覆蓋補翻（只送沒列的字，再加圖；同樣占 Claude 並行名額）。規劃用 `effort: high`，思考 tokens 計入輸出，所以字多的圖可能比想像貴；實際數字以頁面顯示的 tokens 與估計費用為準（估計只依價目表，沒算快取與長上下文加價）。
- 驗證的 OCR 警告 = 每個 job 1 次來源圖 Vision（共用）+ 每張輸出圖（每次嘗試）1 次 Vision（依 Google 當時的計價）。
- 每次按「開始」，選了的圖、模型、次數與 Mode 都會各打一次付費 API。沒有快取；自動重試最多每個 run、每個 Mode A 各一次。
- **並行與 429**：不管選了幾張圖、幾個模型、連跑幾次，同時進行中的圖片生成最多 4 個、Claude 呼叫最多 4 個（都跨 job 共用），其餘排隊；Vision 與 OCR 不受限制。OpenRouter 回 429 時等 20 秒再試同一個請求一次，再 429 就算這個 run 失敗（不會被改走另一條 API 路徑掩蓋）。

## 第 5 輪的破壞性變更

- **manifest `schema_version` 3**（job 與批次）：判定欄位改成 `failed_checks`／`usability`／`coverage` 加新的 judge 欄位（`legibility_issues`、`structure_issues`、`altered_numbers`、`untranslated`、`mistranslated`），舊的 `missing_digit_runs`、`leftover_*`、`garbled_segments`、`untranslated_segments`、`mistranslated_segments`、`reference_translation` 不見了（OCR 的部分在 `ocr_warnings`）。
- **判定名稱換了**：`digits-changed`、`garbled`、`partial` 與它們的組合，變成 `numbers`、`rows`、`garbled`、`partial`、`score` 與用 `+` 連起來的組合（`pass`、`unverified` 不變）；`garbled`、`partial` 這兩個名字還在，但意思變成「judge 看圖後的 major 問題」。判定不再看 OCR，也不再看 `coverage < 0.90`。
- **judge 的簽名與紀錄**：`judge_output(source_ocr, source_image, output_png, target_lang)`，不再收輸出的 OCR；紀錄新增 `usability` 等欄位，沒有 `reference_translation`。
- **Mode A 的檔名**：沒有 `A.png`、`A_erased.png`、`A_units.json` 了，改成每次嘗試各一組 `A_a<j>.png`、`A_a<j>_erased.png`、`A_a<j>_units.json`、`A_a<j>_verify.json`；`GET /api/jobs/...` 的 `mode_a.output` 等欄位指向被採用的那一次，另有 `attempts[]`。Mode A 的 unit 多了 `short`、`align`、`weight`、`importance`、`erase_method`、`text_used` 等欄位；標題不再自動置中、自動粗體（改由 planner 的 `align`／`weight` 決定）。
- **預設值**：`OPENROUTER_IMAGE_MODEL` 預設改成 `google/gemini-3-pro-image`；頁面預設 Mode A 開；`MODE_A_RETRY` 預設開。
- **費用欄位**：結果與 manifest 多了 `cost_openrouter_usd`／`cost_anthropic_usd`；Mode A 的 `cost_usd` 仍是規劃的費用，`total_cost_usd` 含 judge。
- 頁面送出改用 `POST /api/batches`（`POST /api/jobs` 照舊可用，但不屬於批次、不產生批次 manifest）。

## 第 6 輪的變更（與第 5 輪不相容之處）

- **批次 manifest 的 `target_language` 是清單**（不重複的語言，依第一次出現的順序），不再是字串；每個 `jobs[]` 的項目多了自己的 `target_language`。job manifest 不變。`schema_version` 仍是 3。
- **批次紀錄**多了 `target_langs`（每張圖一個）、`target_language`（不重複清單）與 `images[].target_lang`；`params.target_lang` 現在是批次的預設語言（不再是第一個 job 的語言）；彙總表的每一列多了 `target_lang`。
- **`POST /api/batches`** 多了 `target_langs`；`jobs.start_batch(..., langs=None)` 多了關鍵字參數（沒給就全部用 `lang`）。
- **規劃的 JSON schema 多了必填欄位 `group`**；`ClaudeUnit.group` 預設 `""`，舊的手建 unit 照舊能用。units JSON 的每個 unit 多了 `group`、`group_size`、`group_weight`、`group_size_broken`，以及 `erase_reason`、`erase_fill`、`erase_ring_color`、`erase_ring_spread`、`erase_ring_gap`、`erase_extension`。
- **Mode A 的結果與每次嘗試**多了 `coverage_pass`、`planner_coverage`（Mode A 結果裡 `coverage` 仍然是 judge 的完成度，所以規劃的覆蓋叫 `planner_coverage`）。
- **flat／inpaint 的決定改了**：`erase.measure_background(rgb, um, exclude=None)` 多了環帶的判斷與 `BackgroundInfo.fill_color`；同一張圖第 6 輪的 flat 單位會比第 5 輪少（環帶不同意的改走 inpaint），填的顏色是環帶中位數。羽化從 1.0 改成 1.5。
- `claude_units.MAX_TOKENS` 32000（原 16000）；judge 的 system prompt 改了（見上），`verify.judge_checks` 的 `rows` 多了「`detail` 不是空的」這一條。

## 第 7 輪的變更（與第 6 輪不相容之處）

- **目錄搬了**：`tools/overlay-lab` → `services/image-translator`（套件仍是 `app`）。compose 專案、映像與容器名稱不變（`overlay-lab`）；`MINIO_PREFIX` 的預設值仍是 `overlay-lab`（已經上傳的物件不搬家）。
- **lab 的網址搬到 `/lab`**；根路徑 `/api/*`、`/files/*` 保留一個版本並帶 `Deprecation: true`，`GET /` 導向 `/lab/`。lab 頁面的語言下拉選單現在列出整張 `LANGUAGES` 表。
- **新的 `/v1`、`/healthz`**、`SERVICE_TOKEN`、預算檔、`BIND_HOST`／`PORT`（預設只綁 `127.0.0.1`；Dockerfile 與 compose 設 `0.0.0.0`，healthcheck 改打 `/healthz`）。
- **Mode A 的 `rows` 不算**：Mode A 的判定、`jobs.check_output(..., structure_checks)`、`verify.verify_output(..., structure_checks)`、`verify.judge_checks(judge, structure_checks)` 都多了參數（預設 `True`，Mode B 不變）；報告多了 `structure_checks`、`structure_warnings`，`checks.rows` 在關閉時多一個 `ignored`。`jobs.check_output` 也多了 `opts["ocr"]`（預設開，正式策略關）。
- **孤字規則變嚴**：`layout.is_orphan` 把「2 個以下的中日韓字」也算孤字（以前只有 1 個）；`layout.ORPHAN_SHRINK_MIN`（縮小字級最多 15%）不見了，改成降到可讀的下限；`wrap_text(..., prefer_breaks=False)` 多了參數，`wrap_avoiding_orphan` 用它。
- **直排拉丁字**：窄於 `MIN_ROTATED_COLUMN_PX` 的欄不再畫（`skipped: column too narrow`）。`render.render_unit(..., column_px=None)`。
- `config.LANGUAGES` 從 6 種變成 24 種；`fonts.CJK_FACE` 多了 `zh-HK`，`fonts.is_cjk_lang`、`fonts.resolve_script_font`、`fonts.font_notes` 是新的；`verify.TARGET_SCRIPTS` 補了新語言的文字。OpenRouter 的 `X-Title` 從 `LineBotTranslator overlay-lab` 改成 `LineBotTranslator image-translator`。

## 這個工具「不是」什麼

- **lab 本身不是正式版**：沒有佇列與限流、沒有使用者隔離（MinIO 只用來交付結果）；圖片與結果會留在 `./data/`，自己清。正式的路徑是 `/v1`（令牌、預算、保留期限、只留記錄與一張圖）。服務本身仍然沒有 LINE、沒有 MongoDB：那是 Java bot 的事。
- **inpaint 是 OpenCV Telea，不是生成式模型**：只用在框內或環帶不平坦、或兩者不同色的 unit；漸層、照片、複雜紋理上的大面積文字會糊、會留下痕跡。環帶與框內同意的平坦背景用填色，不是 inpaint。
- Mode A 的譯文只用一種顏色、一種字型家族（拉丁字 Noto Sans／DejaVu Sans，中日韓 Noto Sans CJK）畫，不會還原原本的字型；字體粗細靠 planner 的 `weight` 與筆畫粗細估計。
- 判定是 LLM 看圖的結果，不是人眼：judge 可能誤報或漏報（嚴格程度沒有在真實圖片上校準過），小字與手寫字讀得比 OCR 好但不是 100%；OCR 警告更不可靠，只當提示；通過率只有 1–3 次的樣本。
- 整張圖會被送到 Anthropic（Mode A 的規劃、judge：**縮到長邊 1568 px 的原圖與輸出圖**，加上來源的 OCR 文字行）、OpenRouter / 其後的模型供應商（Mode B）與 Google Vision，也會（設了 MinIO 時）上傳到你的 MinIO，含圖上的所有個資，請自行評估。
- 字清單太大（估計超過約 12k tokens）只會提醒，不會丟字；超過 150k tokens 會直接拒絕。
- **第 6 輪的改動沒有在真實的 Anthropic／Vision／OpenRouter 上驗證過**：覆蓋補翻、`group`、新的 prompt 與 judge prompt 只經過離線測試與 `LAB_MOCK=1` 端到端；環帶判斷（擦除）另外拿第 5 輪四張真實圖片的單元框重建字框離線重跑過（收據底部的方塊消失、海報標題的髒斑消失），但那不等於真實規劃輸出的結果。`RING_MATCH_MAX`、`RING_PX`、`BOX_SPREAD_WITH_RING_MAX` 這幾個門檻是照這四張圖調的，沒有更多樣本。

## 疑難排解

- Mode A / OCR 警告：`Google Vision credentials are not usable`：確認 `GOOGLE_APPLICATION_CREDENTIALS` 指到容器內存在的檔案，或看上面「憑證怎麼給」。Mode A 會失敗；Mode B 的 OCR 警告顯示「沒有」，判定仍由 judge 決定。
- Mode A：`Claude hit max_tokens=32000 before finishing the JSON (the call produced N output tokens)`：這張圖的 unit 多到 32000 tokens 也寫不完；重試時這會讓第二次嘗試失敗、沿用第一次（頁面顯示 `retry_error`）。字很多的圖可以改用較小的模型或拆圖。
- Mode A 的提醒區出現「the coverage pass failed」：覆蓋補翻那一次呼叫失敗，沒進 unit 的字照舊保持原樣，其餘結果不受影響。
- Mode A：`Claude refused ...`：模型安全分類器拒絕，頁面會顯示類別；預設已開 server-side fallback。
- Mode B：`the model returned no image (it said: ...)`：該模型沒有輸出圖片，換一個模型，或把 `OPENROUTER_IMAGE_API` 設成 `images` / `chat` 試另一條路。
- 判定是 `unverified`：judge 不可用（沒設 `ANTHROPIC_API_KEY`、Claude API 錯誤、被拒絕、JSON 不合 schema）、被關掉，或沒有輸出圖；頁面會寫原因。這不影響生成與 OCR 警告，只是這次沒有判定也不會重試；補好金鑰後重跑。
- 判定不是 `pass` 但圖看起來沒問題：看 judge 的問題清單與 `where`（它指出的區域）。judge 可能誤報；以眼睛為準，必要時把 `CLAUDE_JUDGE_MODEL` 換成別的型號比較。
- Mode A 的某個 unit 周圍出現方塊或髒斑：看 unit 表的「擦除（原因）」與 JSON 的 `erase_reason`、`erase_ring_color`、`erase_fill`。`ring …` 開頭的原因表示環帶不同意，已經改走 inpaint；`box only: no usable ring` 表示框四周沒有可用的像素，只能信框內。
- 同一個表格的字級還是不一致：看 JSON 的 `group`（規劃有沒有把它們分到同一組）與 `group_size_broken`（有的 unit 放不進共同字級，保留了自己較小的字級）。
- Mode A 的字還是很小：看 unit 表的「用字」與「字級（比例）」：`readable: false` 表示連 `short` 都放不進、只好縮小；請 Claude 給更短的 `short`（重試會要求）或接受。`skipped: too small` 是次要 unit，保持原樣。
- Mode B 出現 `HTTP 429`：OpenRouter 的速率限制。工具已經等 20 秒重試過一次還是 429，才會顯示；減少模型數或連跑次數，或稍後再試。
- 「給審閱者的連結」顯示缺變數：補齊 `.env` 後重啟容器（`docker compose up -d`）。顯示上傳失敗：看頁面的「提醒」（例如 bucket 不存在、金鑰錯誤；金鑰本身不會出現在訊息裡）。批次 manifest 沒出現：要等每一張圖都跑完並上傳完；有一張的 job manifest 沒上傳成功時，批次 manifest 照樣上傳，只是那一張的連結是 `null`。
- 連結審閱者打不開：確認用的是 `MINIO_PUBLIC_ENDPOINT` 簽的（警告區有提醒的話表示簽成了內部位址），且沒有過期。
- 下拉選單只有預設模型：OpenRouter 模型清單抓取失敗（狀態列會顯示原因），不影響執行。
- `/v1` 一直是 503：沒設 `SERVICE_TOKEN`（`reason: service_token_missing`）。401 是沒帶 `X-Service-Token`、403 是令牌不對；令牌只收標頭。`/healthz` 不需要令牌。
- `/v1` 回 `status: error`、`reason: anthropic_budget`：本月 Anthropic 預算用完了（看 `budget`，或 `GET /healthz`）；下個日曆月（Asia/Taipei）自動歸零，或調高 `ANTHROPIC_MONTHLY_BUDGET_USD`（0 = 不設上限）。`openrouter_budget` 只會讓 Mode B 被跳過。
- `/v1` 一直回 `text_only`／`below_threshold`：看 `judge.summary` 與 `attempts`；`unverified` 是 judge 沒有答案（金鑰、API 錯誤）。
- 圖上直排的欄沒翻譯、`skipped: column too narrow`：拉丁目標語言的圖裡窄於 `MIN_ROTATED_COLUMN_PX` 的欄保持原樣，這是刻意的（轉 90 度的小字讀不出來）；真的要畫就調小 `MIN_ROTATED_COLUMN_PX`。
- 泰文／阿拉伯文出現方塊：`warnings` 會寫缺哪個 Noto 字型；Docker 映像裝了 `fonts-noto-core` 就有。阿拉伯文與希伯來文沒有 shaping／bidi，圖多半是錯的。
- 測試：`docker compose run --rm lab pytest -q`（容器內也有測試；README 與 prompt 一致性的那些測試會因為映像沒有 README 而略過）。
