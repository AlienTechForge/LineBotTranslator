# overlay-lab：圖片翻譯 overlay 的離線對照實驗

> 這是獨立的 Python 原型，**不是正式服務**，跟 Java 的 LINE bot 沒有任何程式碼上的關係，也不會被 bot 載入。
> 目的只有一個：用同一批測試圖，並排比較三種做法，看「模型驅動」的流程能做到什麼程度，再決定要不要改正式版。

三欄並排：**原圖｜Mode A（hybrid）｜Mode B（影像模型）**。

- **Mode A（hybrid）**：Google Vision 只負責給「每個字的框」→ Claude（看圖 + 看字清單）決定哪些字屬於同一個翻譯單位（unit）、閱讀方向、角色、要不要保留，並翻譯 → 我們的程式用字框算出每個 unit 的幾何 → 用 OpenCV inpaint 把原字抹掉（背景重建，不是塗色塊）→ 用 Pillow 以原本的位置、大小、顏色把譯文畫回去。
- **Mode B（影像模型）**：整張圖丟給 OpenRouter 上有「圖片輸出」能力的模型，要它「把所有文字換成譯文，其餘不動」。可選：再用 Vision OCR 掃一次輸出，看它到底寫了什麼字。

沒有針對任何單張圖的特殊規則：分組、方向、角色都是 Claude 看圖判斷，幾何與繪製是通用程式。

## 三個指令跑起來

在安裝了 Docker 的伺服器上：

```bash
cd tools/overlay-lab
cp .env.example .env        # 填 ANTHROPIC_API_KEY、OPENROUTER_API_KEY，強烈建議再填 LAB_TOKEN
docker compose up --build -d
```

然後開 `http://<伺服器>:4041/?token=<LAB_TOKEN>`（沒設 `LAB_TOKEN` 就直接開 `http://<伺服器>:4041/`）。

看 log：`docker compose logs -f lab`。停掉：`docker compose down`。

只想先確認整個流程能動、手邊沒有任何金鑰（完全不打網路，Vision / Claude / OpenRouter 全換成內建 fixture）：

```bash
docker build -t overlay-lab:local .
docker run --rm -p 4041:4041 -e LAB_MOCK=1 overlay-lab:local
# 內建合成測試圖：http://localhost:4041/api/mock-image ，下載後丟進頁面即可
```

不用 Docker（Python 3.12 或 3.13）：

```bash
python -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt
LAB_MOCK=1 python -m app          # 或設好環境變數後直接 python -m app
pytest -q                         # 單元測試 + mock 端到端
```

## 憑證怎麼給

### Google Vision（預設：直接掛正式機的 volume，不複製、不落地）

`scripts/deploy.sh` 會把 Vision 服務帳戶 JSON 放在 Docker volume `linebot-translator-secrets`，檔案是 `/run/secrets/linebot.json`（owner uid 10001、權限 0400）。
`docker-compose.yml` 預設就是把這個 **外部 volume 以唯讀掛進 lab 容器**，`GOOGLE_APPLICATION_CREDENTIALS` 預設指向 `/run/secrets/linebot.json`，所以**不需要把 JSON 取出來**。

- 容器是以 root 執行的（lab 工具刻意這樣做，才讀得到 0400 的檔案；它不是正式服務，請不要對網際網路開放，除非設了 `LAB_TOKEN`）。
- 上傳與輸出放在 `./data/`（容器內 `/app/data`，主機上是 root 擁有）。不用時 `sudo rm -rf data` 即可。
- 這個容器只需要對外連線（Vision、Anthropic、OpenRouter），**不需要**加入 bot 的 Docker network。
- 如果 `docker compose up` 說找不到 volume：`docker volume ls | grep linebot-translator-secrets`，名稱不同的話改 compose 裡的 volume 名稱。

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

### Anthropic 金鑰

`ANTHROPIC_API_KEY` 填進 `.env`。預設模型 `claude-opus-5-5`，可用 `CLAUDE_MODEL` 換。

## 環境變數

| 變數 | 預設 | 說明 |
| --- | --- | --- |
| `GOOGLE_APPLICATION_CREDENTIALS` | compose：`/run/secrets/linebot.json` | Vision 服務帳戶 JSON 的路徑（容器內）。正式 bot 用的是同一個變數名稱。 |
| `VISION_CREDENTIALS_FILE` | 空 | 方法 2 才用：主機上的 JSON 路徑，掛到 `/secrets/vision.json`。 |
| `ANTHROPIC_API_KEY` | 無 | Mode A 必填。 |
| `CLAUDE_MODEL` | `claude-opus-5-5` | Mode A 用的模型。價目表只有 `claude-opus-5-5`（4 / 20 USD per 百萬 tokens）、`claude-sonnet-5-5`（2 / 10）、`claude-haiku-5-5`（0.10 / 0.50）；其他型號只顯示 tokens。 |
| `OPENROUTER_API_KEY` / `OPEN_ROUTE_API_KEY` | 無 | Mode B 必填，兩個名稱擇一。 |
| `OPENROUTER_IMAGE_MODEL` | `google/gemini-2.5-flash-image` | Mode B 預設影像模型；頁面的下拉選單會列出 OpenRouter 上所有「輸出含圖片」的模型（快取 10 分鐘）。 |
| `OPENROUTER_IMAGE_API` | `auto` | `auto`：先走 `chat/completions`（`modalities: ["image","text"]`），失敗再走專用的 `/api/v1/images`；`chat` / `images`：只走那一條。 |
| `OPENROUTER_HTTP_REFERER` | 空 | 選填，送給 OpenRouter 的 `HTTP-Referer`（也接受 `OPEN_ROUTE_HTTP_REFERER`）。 |
| `LAB_TOKEN` | 空 | 設了之後每個請求都要帶 `?token=` 或標頭 `X-Lab-Token`，否則 401。 |
| `LAB_PORT` | `4041` | 對外埠。 |
| `LAB_MOCK` | `0` | `1` = 不打任何網路，改用內建 fixture（Mode B 直接回傳輸入圖）。 |
| `LAB_DATA_DIR` | `./data` | 上傳與輸出（`<job-id>/` 底下）；容器內固定 `/app/data`。 |
| `LAB_INK_FRACTION` | `0.40` | 取文字顏色時，用字框內「離背景最遠」的這個比例的像素。細小的字顏色會偏淡，調小（例如 `0.2`）會比較深。 |
| `LAB_FONT_CJK`、`LAB_FONT_CJK_BOLD`、`LAB_FONT_LATIN`、`LAB_FONT_LATIN_BOLD` | 自動尋找 | 選填，指定字型檔。 |

安全：頁面只給你自己用。上傳上限 20 MB，只收 jpg / png / webp，輸出只從 `LAB_DATA_DIR/<job-id>/` 提供；uvicorn 的 access log 已關閉（網址可能帶 `?token=`）；log 不會出現圖片內容、金鑰或完整 OCR 文字。

## 怎麼讀結果

每個欄位上方有耗時；Mode A 另外拆成各階段。

**Mode A 欄**
- `Vision` / `Claude` / `erase / render`：各階段毫秒。`tokens (in / out)` 與 `估計費用` 來自 Claude 回應的 usage（`out` 含思考 tokens）。
- `units`：翻譯了幾個 / 共幾個 unit / Vision 的字數。
- `fallback 形式`：實際用的 Claude 呼叫形式。`default` = `fallbacks="default"` + beta `server-side-fallback-2026-07-01`；`array` = `fallbacks=[{"model":"claude-opus-4-8"}]` + beta `server-side-fallback-2026-06-01`；`none` = 不帶 fallbacks 的一般 `messages.create`；`mock`。前一種被拒絕才會退到後一種，原因會記在 `fallback_attempts`。
- 「下載僅擦除版」：只做 inpaint、還沒畫字的圖，用來單獨看擦除品質。
- 提醒區：Claude 的 unit 被丟掉的原因（word id 不存在整個 unit 作廢、重複的 id 被移除）、字清單過大、某個 unit 畫失敗。
- 原圖欄下的「疊上 Mode A 的 unit 框」：藍＝翻譯、綠＝保留（`keep`）、灰＝略過；用來一眼檢查 Claude 的分組對不對。
- 「Mode A units（JSON）」面板，每個 unit：
  `id`、`word_ids`、`source_text`、`translation`、`direction`（`horizontal` / `vertical_rl` / `vertical_lr`）、`rotated`、`role`、`keep`、`notes`；
  幾何：`frame`（旋轉矩形四角）、`bbox`、`angle`（度，順時針為正）、`size`、`glyph_h`；
  結果：`action`（`translated` / `kept` / `skipped`，後者含原因）、`font_size`、`layout`（`horizontal` / `vertical_cjk` / `vertical_latin`）、`lines`、`fitted`、`overflowed`、`truncated`、`bold`、`text_color`、`bg_color`、`color_fallback`、`ink_bbox`；
  `paragraphs` 是 Vision 自己的段落編號（只供對照 Claude 的分組）。
  `overflowed: true` 表示 10 px 還是放不進框，字會往框下方（或直排的欄尾）多出最多 30%，超過的部分以 `…` 截斷（`truncated: true`）。

**Mode B 欄**
- `API 路徑`：實際走 `chat` 還是 `images`（`(no aspect ratio)` 表示拿掉了長寬比參數才成功）。
- `tokens` / `費用`：只有 OpenRouter 回應有帶 `usage` 才會顯示。
- `輸出尺寸（長寬比差 x%）`：影像模型常常不照原圖長寬比輸出，差越多代表版面越不可能對得上。
- 勾「用 Vision OCR 驗證」會多一個「Mode B 驗證 OCR 文字」面板，列出模型實際畫出來的所有字，可以人工比對漏翻、錯字、自己亂加的字。
- 「模型附帶文字」是模型除了圖以外說的話（拒絕時通常在這裡）。

**比較時建議看**：完成度（有沒有漏翻、有沒有翻一半）、版面與字體是否保留、顏色、直排／旋轉頁面的處理、以及 B 有沒有改動不該動的圖形或文字。

## 費用備註

- Mode A = 1 次 Vision `document_text_detection` + 1 次 Claude（圖 + 字清單，輸出 JSON）。Claude 用 `effort: high`，思考 tokens 計入輸出，所以字多的圖可能比想像貴；實際數字以頁面顯示的 tokens 與估計費用為準（估計只依上面的價目表，沒算快取與長上下文加價）。Vision 依 Google 當時的計價。
- Mode B 的費用取決於所選影像模型；OpenRouter 的回應有帶 `usage.cost` 時會直接顯示。勾「Vision OCR 驗證」會多一次 Vision。
- 每次按「開始」，選了的 Mode 都會各打一次付費 API。沒有快取、不會自動重試整個流程。

## 這個工具「不是」什麼

- **不是正式版**：沒有 LINE、沒有 MongoDB / MinIO、沒有佇列與限流、沒有使用者隔離。圖片與結果會留在 `./data/`，自己清。
- **inpaint 是 OpenCV Telea，不是生成式模型**：平坦背景幾乎看不出來（顏色可能有 ±幾階的差），但漸層、照片、複雜紋理上的大面積文字會糊、會留下痕跡。這正是要拿來跟 Mode B 比較的地方。
- 譯文只用一種顏色、一種字型家族（Noto Sans CJK / DejaVu Sans）畫，不會還原原本的字型；字體粗細只靠角色與筆畫粗細估計。
- 整張圖會被送到 Anthropic（Mode A）與 OpenRouter / 其後的模型供應商（Mode B），含圖上的所有個資，請自行評估。
- 字清單太大（估計超過約 12k tokens）只會提醒，不會丟字；超過 150k tokens 會直接拒絕。

## 疑難排解

- Mode A：`Google Vision credentials are not usable`：確認 `GOOGLE_APPLICATION_CREDENTIALS` 指到容器內存在的檔案，或看上面「憑證怎麼給」。
- Mode A：`Claude refused ...`：模型安全分類器拒絕，頁面會顯示類別；預設已開 server-side fallback。
- Mode B：`the model returned no image (it said: ...)`：該模型沒有輸出圖片，換一個模型，或把 `OPENROUTER_IMAGE_API` 設成 `images` / `chat` 試另一條路。
- 下拉選單只有預設模型：OpenRouter 模型清單抓取失敗（狀態列會顯示原因），不影響執行。
- 測試：`docker compose run --rm lab pytest -q`（容器內也有測試）。
