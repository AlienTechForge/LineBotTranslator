# overlay-lab：圖片翻譯 overlay 的離線對照實驗

> 這是獨立的 Python 原型，**不是正式服務**，跟 Java 的 LINE bot 沒有任何程式碼上的關係，也不會被 bot 載入。
> 目的只有一個：用同一批測試圖，比較「模型驅動」的做法能做到什麼程度，再決定要不要改正式版。

頁面一次最多收 **4 張圖（一個批次）**。送出後最上面是**批次彙總表**（每張圖 × Mode／模型一列：判定、可用度、Anthropic 額度費用、OpenRouter 現金費用、時間），下面每張圖一個區塊，區塊內是一排並排的欄位：**原圖｜Mode A｜Mode B 的每個模型（每次 run）各一欄**（欄位多時橫向捲動）。

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

## 三個指令跑起來

在安裝了 Docker 的伺服器上：

```bash
cd tools/overlay-lab
cp .env.example .env        # 填 OPENROUTER_API_KEY（Mode A 另需 ANTHROPIC_API_KEY），強烈建議再填 LAB_TOKEN
docker compose up --build -d
```

然後開 `http://<伺服器>:4041/?token=<LAB_TOKEN>`（沒設 `LAB_TOKEN` 就直接開 `http://<伺服器>:4041/`）。

看 log：`docker compose logs -f lab`。停掉：`docker compose down`。

只想先確認整個流程能動、手邊沒有任何金鑰（完全不打網路，Vision / Claude / OpenRouter / MinIO 全換成內建 fixture 與記憶體假物件）：

```bash
docker build -t overlay-lab:local .
docker run --rm -p 4041:4041 -e LAB_MOCK=1 overlay-lab:local
# 內建合成測試圖：http://localhost:4041/api/mock-image ，下載後丟進頁面即可
```

不用 Docker（Python 3.12 或 3.13）：

```bash
python -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt
LAB_MOCK=1 python -m app          # 或設好環境變數後直接 python -m app
pytest -q                         # 單元測試 + mock 端到端（全部離線）
```

> 這個 repository 的 CI 是 Maven build，**不會測這個工具**。改動後請自己跑 `pytest -q`（Python 3.12 與 3.13 都可以）。

## 憑證怎麼給

### Google Vision（預設：直接掛正式機的 volume，不複製、不落地）

`scripts/deploy.sh` 會把 Vision 服務帳戶 JSON 放在 Docker volume `linebot-translator-secrets`，檔案是 `/run/secrets/linebot.json`（owner uid 10001、權限 0400）。
`docker-compose.yml` 預設就是把這個 **外部 volume 以唯讀掛進 lab 容器**，`GOOGLE_APPLICATION_CREDENTIALS` 預設指向 `/run/secrets/linebot.json`，所以**不需要把 JSON 取出來**。

- 容器是以 root 執行的（lab 工具刻意這樣做，才讀得到 0400 的檔案；它不是正式服務，請不要對網際網路開放，除非設了 `LAB_TOKEN`）。
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

`ANTHROPIC_API_KEY` 填進 `.env`。Mode A 預設模型 `claude-opus-5-5`（`CLAUDE_MODEL`）；judge 預設 `claude-sonnet-5-5`（`CLAUDE_JUDGE_MODEL`）。沒有這支金鑰時 Mode A 不能用；Mode B 照常生成，但每次嘗試的 judge 顯示 `unavailable`，判定是 `unverified`（不會重試；OCR 警告仍會顯示）。

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
| `CLAUDE_MODEL` | `claude-opus-5-5` | Mode A 規劃用的模型。價目表只有 `claude-opus-5-5`（4 / 20 USD per 百萬 tokens）、`claude-sonnet-5-5`（2 / 10）、`claude-haiku-5-5`（0.10 / 0.50）；其他型號只顯示 tokens。 |
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
| `LAB_TOKEN` | 空 | 設了之後每個請求都要帶 `?token=` 或標頭 `X-Lab-Token`，否則 401。 |
| `LAB_PORT` | `4041` | 對外埠。 |
| `LAB_MOCK` | `0` | `1` = 不打任何網路，改用內建 fixture（Mode B 直接回傳輸入圖，Vision 用內建字框，Claude 與 judge 用罐頭答案，上傳改用記憶體假物件）。 |
| `LAB_DATA_DIR` | `./data` | 上傳與輸出（`<job-id>/`、`batch-<batch-id>/` 底下）；容器內固定 `/app/data`。 |
| `LAB_INK_FRACTION` | `0.40` | 取文字顏色時，用字框內「離背景最遠」的這個比例的像素。細小的字顏色會偏淡，調小（例如 `0.2`）會比較深。 |
| `LAB_FONT_CJK`、`LAB_FONT_CJK_BOLD`、`LAB_FONT_LATIN`、`LAB_FONT_LATIN_BOLD` | 自動尋找 | 選填，指定字型檔。自動尋找的順序：拉丁字 Noto Sans → DejaVu Sans → Liberation／FreeSans；中日韓 Noto Sans CJK。 |

安全：頁面只給你自己用。每張上傳上限 20 MB，只收 jpg / png / webp，輸出只從 `LAB_DATA_DIR/<job-id>/` 提供；uvicorn 的 access log 已關閉（網址可能帶 `?token=`）；log 不會出現圖片內容、金鑰、OCR 文字、譯文、檔名或簽名連結（連結含存取金鑰 ID 與簽章，只留在頁面、`result.json` 與 manifest）。

## 一次送出最多四張圖（批次）

- **頁面**：檔案欄可以多選 1–4 張（每張照舊的限制：jpg／png／webp、20 MB）。一次送出就是一個**批次**：每張圖一個 job（Mode A 選用、Mode B 的每個模型 × 每次 run、驗證、judge、上傳，都和單張圖時一模一樣），頁面依送出順序畫出每張圖一個區塊。頁面預設：Mode A 開、Mode B 開且預選 `google/gemini-3-pro-image`、連跑 1 次、judge 開、輸出尺寸 `2K`、Mode A 重試開。
- **批次彙總表**（送出後頁面最上面的東西）：每張圖 × Mode A／每個 Mode B 模型一列，欄位：圖片、Mode／模型、**判定徽章**、**judge 可用度**、**Anthropic（額度）** 費用、**OpenRouter（現金）** 費用、時間，最後一列是合計（幾列 pass、兩種費用的總和）。一列 Mode B 連跑多次時，判定與可用度取最佳的那一次（`verify.choose_attempt` 的順序），費用是所有已完成 run 的總和，時間是每個 run 的平均。Mode A 的費用只算在 Anthropic（規劃 + judge）；Mode B 的生成費用（OpenRouter 回應的 `usage.cost`）算在 OpenRouter，judge 費用算在 Anthropic。費用都是依價目表的估計，只算已完成的 run。
- **API**：`POST /api/batches`（multipart），欄位 `files`（1–4 個檔案，超過回 400，少於 1 個回 422）加上和 `POST /api/jobs` 一樣的表單欄位（`target_lang`、`mode_a`、`mode_b`、`image_models`、`output_size`、`verify`、`verify_details`、`runs`、`judge`，另有 `mode_a_retry`），回傳 `{"batch_id": ..., "job_ids": [...]}`。**每一張都先檢查**（內容類型、大小、能不能解碼），有一張不合格就整批拒絕、什麼都不會啟動。`GET /api/batches/<batch-id>` 回傳批次的紀錄加上每個 job 目前完整的文件（`jobs[]`，依送出順序）、`summary[]`（彙總表的列）、`totals`、`status`（所有 job 都結束才是 `done`）與 `publish`（批次 manifest 的上傳狀態）。`GET /api/batches/<batch-id>/manifest` 回傳本機留的批次 manifest。`POST /api/jobs` 照舊可以只送一張（不屬於任何批次）。
- **並行上限**（整個程序共用）：同時進行的圖片生成最多 4 個（`MAX_CONCURRENT_GENERATIONS`），**同時進行的 Claude 呼叫（Mode A 規劃與 judge 一起算）最多 4 個**（`MAX_CONCURRENT_CLAUDE`），所以四張圖不會一口氣丟出 20 個 Claude 請求；其餘排隊。每個 job 仍有自己的執行緒。
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
- `structure_issues`：`{type, detail}`，`type` 是 `duplicated_row`、`missing_row`、`shifted`、`misaligned_marks`、`other`：重複、漏掉或移位的列／項目，以及手寫記號、價格、數量現在跑到別的項目旁。
- `altered_numbers`：`{source, output, where}`：兩張圖之間不同的價格、日期、時間、電話、數量、年份（只差格式的不算）。
- `untranslated`：`{text, where, major}`：還是來源語言（或別的非目標語言）卻該翻的文字（品牌名、網址、e-mail、本來就是目標語言的除外）；標題或整欄內文是 major、註腳不是。
- `mistranslated`：`{output_text, source_text, problem}`：是真的字、但意思和來源不同。只顯示，不影響判定。
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

5. structure_issues: type duplicated_row (a row or item appears twice), missing_row (one is missing), shifted (items moved so that the layout no longer matches), misaligned_marks (handwritten marks, prices or quantities now sit next to a different item than in Image 1) or other; detail names the item in a few words.

6. altered_numbers: prices, dates, times, phone numbers, quantities and years that differ between Image 1 and Image 2: source and output as written, and where. Read them from the images; the OCR text is only an aid. A difference of formatting alone (12:30 and 12.30, 1,200 and 1200) is not an alteration.

7. untranslated: text in Image 2 that is still in the source language (or in another language that is not the target language) although it should have been translated: text, where and major. Brand names, URLs, e-mail addresses and text that was already in the target language are not issues. A title or a column of body text is major, a footnote is not.

8. mistranslated: text in Image 2 that is real target-language text but whose meaning differs from the source: output_text, source_text and problem (a few words).

9. summary: one sentence in English on the overall result.

Use empty arrays when there is nothing to report. Return only the JSON object that matches the schema.
```

## 判定規則（verdict）

判定來自 judge 的紀錄（`app/verify.py`），五項檢查，嚴重程度由上往下：

| 檢查 | 沒過的條件 |
| --- | --- |
| `numbers` | `altered_numbers` 不是空的 |
| `rows` | 有任何 `structure_issues` 的 `type` 是 `duplicated_row`、`missing_row` 或 `misaligned_marks`（`shifted`、`other` 不算） |
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
- **`flat`（平坦填色）**：背景像素的每通道標準差 ≤ `FLAT_STD_MAX`（10，0–255 的尺度）、背景像素夠多、墨水不超過框的 `MAX_INK_SHARE`（75%）→ 用背景色填滿擦除範圍（邊緣羽化約 1–2 px，只羽化在背景色的像素上）。表格、海報、純色卡片、白紙都走這條，不會有 inpaint 的糊與拖色。
- **`inpaint`**：其他（漸層、照片、紋理）用 OpenCV Telea inpaint，和以前一樣。
- 兩種方法的擦除範圍都有**顏色護欄**：字框膨脹時只會長進「顏色在該 unit 背景色 `GUARD_TOLERANCE`（56，紋理背景會依標準差放寬）以內」的像素，**絕不會長進黑色格子、表格線或別的顏色的相鄰元素**；inpaint 還會多留 3 px 的緩衝（Telea 會讀遮罩周圍那麼遠的像素）。沒被翻譯的字（保留的價格、沒列進任何 unit 的字）照舊受保護。
- unit 報告裡有 `erase_method`、`erase_bg`（背景色）、`erase_spread`、`erase_ink_share`、`erase_px`；頁面的 unit 表也顯示 `erase_method`。

### 可讀的排版（`layout.py`、`render.py`）

「可讀」= 需要的字級至少是來源字高的 `MIN_FIT_RATIO`（0.70）倍。排版**先於擦除**決定（因為「太小的次要 unit 保持原樣」要在擦除前就知道），順序：

1. 完整譯文放進原框；
2. 完整譯文放進**往旁邊長的框**：沿著 unit 的閱讀方向算「空位」（從框往外到另一個 unit 的框、沒被翻譯的字的框、或圖邊緣為止，各留字高 1/4 的間隙），再只算到背景維持同色的那一段（框外取樣，離背景色超過容許值的欄就停），最多長到原長度的 `MAX_GROW`（1.5）倍；依 planner 的 `align` 長：靠左往右長、靠右往左長、置中兩邊等量。只長到剛好可讀的最小一格（分 4 步找）。**只有 `flat` 背景的 unit 才會長**（`inpaint` 的不長），長出來的那一段也一併填成背景色。兩個 unit 面對面時，先處理的那個長了之後，後面的把它當障礙；
3. `short`（planner 給的短版）用同樣兩種框再試一次，`text_used` 記 `full` 或 `short`；
4. 都不可讀：**次要**（`importance: minor`）的 unit 保持原樣、報告為 `skipped: too small`（`skip_reason: "too small"`，不擦除）；**主要**的用較短的那段文字縮到最小 10 px（和以前一樣），報告 `readable: false`。
- **直排**：目標語言不是中日韓的直排 unit，譯文**轉 90 度（順時針，由上往下讀）、一行一欄**（`vertical_rl` 第一行在最右欄、`vertical_lr` 在最左欄）。中日韓目標的直排照舊保持直立。
- **字型**：拉丁字優先用 **Noto Sans**（Docker 映像裝 `fonts-noto-core`），找不到再用 DejaVu；中日韓用 Noto Sans CJK。粗體 = planner 說 `bold` **或**筆畫粗細估計說粗（不再因為角色是標題就硬畫粗體）。
- **孤字規則**：標題與小標（`role` 是 `title`／`heading`）換行後最後一行不可以是單獨一個字（中日韓）或一個很短的詞（拉丁字 ≤ 4 字元）：先用稍窄的第一行重排（每次窄 4%、最多 28%），不行就縮小字級（最多縮 15%）。
- unit 報告裡有 `text_used`、`readable`、`fit_ratio`（字級 ÷ 來源字高）、`min_fit_px`、`grow`（往前／往後長了幾 px）、`lane`（空位，已受平坦背景限制）。

### Claude 規劃（`claude_units.py`）

模型還是**絕不輸出座標**。每個 unit 的欄位（schema 都必填，`validate_units` 仍然嚴格：多餘欄位、未知 word id、重複 id、列舉值錯誤都會被拒絕）新增了四個：

- `short`：譯文的短版（目標是 `translation` 字元數的 60% 以內；標籤與表格格子可以用縮寫），已經夠短就是空字串；
- `align`：`left`／`center`／`right`，和來源文字的排法一致（要看圖判斷）；
- `weight`：`regular`／`bold`，和來源文字看起來的粗細一致；
- `importance`：`major`／`minor`（標題、品名、價格、說明是 major；裝飾、註腳、logo 字是 minor）。

prompt 另外說明：表格的列要維持「一個格子一個 unit、順序和圖上一致」、價格與純數字的格子是 `keep`、品牌名與 logo 是 `keep`、非中日韓目標的直排 unit 會被轉 90 度畫所以譯文要短（並給 `short`）、對齊與粗細要看圖決定。完整的 system prompt（只定義在 `app/claude_units.py` 的 `SYSTEM_PROMPT`，測試會檢查下面這份與程式碼一致）：

```
You prepare an image for in-place translation. You receive the image and a list of OCR words (id, axis-aligned box, text). Group the words into translation units and translate each unit into the target language. You output units, never coordinates: our code computes the geometry from the word boxes, erases the original glyphs and draws your translation in place.

What a unit is:
- A unit is what a human translator would translate as one piece: one line of a list, one bullet, one heading, one paragraph, one dish name in a vertical column, one price tag, one table cell, one button label.
- Never split a sentence across units. Never merge different rows of a table, different list items or different bullets into one unit.
- Table rows stay one unit per cell, in the same order as in the picture (left to right, top to bottom): never merge two cells of a row or two rows of a column, and never move text from one cell to another.
- Vertical CJK columns are one unit per column, unless the columns are clearly one continuous paragraph.
- Use the image to decide grouping, reading order and direction. OCR text can contain errors; write source_text as it actually reads in the image.

Fields:
- word_ids: ids taken from the word list, in reading order. Every id may appear at most once across all units. Words you do not list are left untouched in the image (use that for pure decoration, logos and artwork lettering you would not translate).
- source_text: the unit's text in the source language.
- translation: natural, idiomatic target-language text for this role. Keep it short for labels and tags (the space available is the size of the source text). Keep numbers, units and currency symbols. Do not add explanations, quotes or notes inside the translation.
- direction: "horizontal", "vertical_rl" (columns read top to bottom, columns ordered right to left) or "vertical_lr" (top to bottom, columns left to right).
- rotated: true when the unit's text runs along a page that is turned (photographed sideways or upside down, or text set at an angle); direction then describes how it reads in the text's own frame.
- role: title | heading | body | label | price | number | other.
- keep: true for things that must not be translated: prices and every other price-like or number-only cell, phone numbers, dates and times, URLs, e-mail addresses, brand names and logos, and text that is already in the target language. For keep units set translation equal to source_text and short to an empty string.
- notes: a very short remark if something is uncertain, otherwise an empty string.
- short: a shorter alternative translation of the same unit, at most about 60% of the characters of translation (abbreviations are fine for labels and table cells). Our renderer draws the full translation first and switches to short only when the full one would have to be drawn much smaller than the source text. Use an empty string when the translation is already short.
- align: left | center | right, as the source text is set in the picture (a heading centred over a column is center, a column of right-aligned amounts is right).
- weight: regular | bold, as the source text looks in the picture.
- importance: major | minor. Headings, item names, prices and instructions are major; decoration, footnotes and logo lettering are minor. A minor unit whose translation cannot be drawn at a readable size is left untouched instead of being drawn tiny.

Vertical text: when the target language is not Chinese, Japanese or Korean, a unit with direction vertical_rl or vertical_lr is drawn rotated by 90 degrees (reading top to bottom, one line per column), so keep its translation short and always give a short alternative.

Look at the picture, not only at the OCR text, to decide alignment and weight.

Return only the JSON object that matches the schema.
```

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
- 標題旁有判定徽章；`總耗時`、`Vision`、`Claude 規劃`、`erase / render`、`審查耗時`、`tokens (in / out)`、`費用（Anthropic 額度）`（規劃 + judge，括號內分開列）、`units`（翻譯了幾個 / 共幾個 / Vision 的字數）、`擦除方法`（flat／inpaint 各幾個）、`用 short 的 unit`、`太小而略過`、`fallback 形式`（實際用的 Claude 呼叫形式：`default` = `fallbacks="default"` + beta `server-side-fallback-2026-07-01`；`array` = `fallbacks=[{"model":"claude-opus-4-8"}]` + beta `server-side-fallback-2026-06-01`；`none` = 不帶 fallbacks 的一般 `messages.create`；`mock`）。
- 「下載僅擦除版」：只做擦除、還沒畫字的圖。「驗證細節」同 Mode B。有重試時，沒被採用的那一次收在折疊面板（含重試時多送給 Claude 的那一段）。
- 提醒區：Claude 的 unit 被丟掉的原因、字清單過大、某個 unit 畫失敗、太小而略過的次要 unit。
- 原圖欄下的「疊上 Mode A 的 unit 框」：藍＝翻譯、綠＝保留（`keep`）、灰＝略過；用來一眼檢查 Claude 的分組對不對。
- 「Mode A 每個 unit」表：每個 unit 的動作、角色、重要性、**擦除方法**、**用字（full／short）**、字級（與可讀比例）、往旁邊長了多少；「Mode A units（JSON）」面板是完整欄位：`id`、`word_ids`、`source_text`、`translation`、`short`、`direction`（`horizontal` / `vertical_rl` / `vertical_lr`）、`rotated`、`role`、`align`、`weight`、`importance`、`keep`、`notes`；幾何 `frame`、`bbox`、`angle`、`size`、`glyph_h`；結果 `action`（`translated` / `kept` / `skipped`，後者含原因）、`font_size`、`layout`（`horizontal` / `vertical_cjk` / `vertical_latin`）、`lines`、`fitted`、`readable`、`overflowed`、`truncated`、`bold`、`text_used`、`text_color`、`bg_color`、`ink_bbox`、`erase_*`、`grow`、`lane`。`overflowed: true` 表示 10 px 還是放不進框，字會往框下方（或直排的欄尾）多出最多 30%，超過的部分以 `…` 截斷。

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

**批次的 manifest**：一個批次的每個 job 都跑完、也上傳完之後，才上傳 `<MINIO_PREFIX>/batch-<batch-id>/manifest.json`（`schema_version: 3`，`kind: "batch"`）：`batch_id`、各時間、`target_language`、`options`（Mode、模型清單、輸出尺寸、`runs`、`judge`、`judge_model`、`claude_model`、`mode_a_retry`）、`jobs[]`（每張圖一筆：`index`、`job_id`、`image_name`、尺寸、`status`、`publish_status`、**那個 job manifest 的簽名網址** `manifest`，沒上傳成功的是 `null` 並在 `warnings` 說明）、`summary[]`（和頁面彙總表同樣的列）、`totals`（兩種費用的總和與 pass 的列數）、`warnings`，以及 manifest 自己的 `manifest.url`。上傳失敗不會讓批次或任何 job 失敗。

**job 的 `manifest.json`**（`schema_version: 3`）有：`job_id`、`batch_id`、`image_index`、各時間、`target_language`、`options`（模型清單、輸出尺寸、是否驗證、`runs`、`judge`、`judge_model`、`mode_a_retry`…）、`original`，`mode_a`（判定、`usability`、`coverage`、費用拆成 `cost_usd`／`judge_cost_usd`／`cost_anthropic_usd`／`cost_openrouter_usd`、`attempts[]`：每次嘗試的圖、擦除圖、units、驗證 JSON 的連結、判定、judge 紀錄、擦除方法與 `short` 的統計、重試時多送的段落…），`mode_b[]`（**每個模型一筆**），`objects[]`（`name`、`key`、`bytes`、`content_type`、`url`），`warnings`，以及 manifest 自己的 `manifest.url`。單一物件上傳失敗不會讓 job 失敗：那個物件在 manifest 裡只有 `error`、沒有 `url`，並出現在頁面的「提醒」裡。

`mode_b[]` 的每一筆：`model`、`route_preference`、`resolution_requested`、`runs_requested`，
- `summary`：`runs_done`、`runs_failed`、`passes`、`pass_rate`、`verdicts[]`、`mean_total_cost_usd`、`total_cost_usd`、`mean_elapsed_ms`、`best_run`、`best_verdict`、`best_image`（最佳 run 的圖與連結）；
- `runs[]`（**所有** run，每次一筆）：`run`、`status`、`error`、`api_used`、`verdict`、`failed_checks`、`usability`、`coverage`、`selected_attempt`、`elapsed_ms`、`judge_ms`、`usage`、`cost_usd`（生成）、`judge_cost_usd`、`cost_openrouter_usd`、`cost_anthropic_usd`、`total_cost_usd`、採用的圖 `image`，以及 `attempts[]`：每次嘗試的圖、驗證 JSON、`verdict`、`failed_checks`、`usability`、`coverage`、`judge`（`status`、`error`、`model`、`usability`、`coverage`、`legibility_issues`、`structure_issues`、`altered_numbers`、`untranslated`、`mistranslated`、`summary`、tokens、`cost_usd`）、`ocr_warnings`（警告代碼與缺少的數字、殘留的字）、路徑、尺寸、耗時、tokens、費用、送出的 prompt。

schema 1（第 3 輪）是每個模型一筆；schema 2 在中間多了 `runs[]`；**schema 3（這一輪）改了判定欄位、Mode A 的 attempts、費用拆分，並多了批次 manifest**。

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

`POST /api/batches`（multipart，1–4 張圖）與 `POST /api/jobs`（multipart，1 張圖）：

| 欄位 | 預設 | 說明 |
| --- | --- | --- |
| `files`（批次）／`file`（單張） | 必填 | jpg / png / webp；批次 1–4 個 `files`，每個各自受 20 MB 與格式限制 |
| `target_lang` | `en` | `en`、`zh-TW`、`zh-CN`、`ja`、`ko`、`vi` |
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
- **Mode A** = 1 次 Vision `document_text_detection`（與 Mode B 的來源圖 OCR 共用，不會重複打）+ 1 次 Claude 規劃（圖 + 字清單，輸出 JSON）+ 1 次 judge；判定不是 `pass` 時再加 1 次規劃與 1 次 judge。規劃用 `effort: high`，思考 tokens 計入輸出，所以字多的圖可能比想像貴；實際數字以頁面顯示的 tokens 與估計費用為準（估計只依價目表，沒算快取與長上下文加價）。
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

## 這個工具「不是」什麼

- **不是正式版**：沒有 LINE、沒有 MongoDB、沒有佇列與限流、沒有使用者隔離（MinIO 只用來交付結果）。圖片與結果會留在 `./data/`，自己清。
- **inpaint 是 OpenCV Telea，不是生成式模型**：只用在背景不平坦的 unit；漸層、照片、複雜紋理上的大面積文字會糊、會留下痕跡。平坦背景用填色，不是 inpaint。
- Mode A 的譯文只用一種顏色、一種字型家族（拉丁字 Noto Sans／DejaVu Sans，中日韓 Noto Sans CJK）畫，不會還原原本的字型；字體粗細靠 planner 的 `weight` 與筆畫粗細估計。
- 判定是 LLM 看圖的結果，不是人眼：judge 可能誤報或漏報（嚴格程度沒有在真實圖片上校準過），小字與手寫字讀得比 OCR 好但不是 100%；OCR 警告更不可靠，只當提示；通過率只有 1–3 次的樣本。
- 整張圖會被送到 Anthropic（Mode A 的規劃、judge：**縮到長邊 1568 px 的原圖與輸出圖**，加上來源的 OCR 文字行）、OpenRouter / 其後的模型供應商（Mode B）與 Google Vision，也會（設了 MinIO 時）上傳到你的 MinIO，含圖上的所有個資，請自行評估。
- 字清單太大（估計超過約 12k tokens）只會提醒，不會丟字；超過 150k tokens 會直接拒絕。

## 疑難排解

- Mode A / OCR 警告：`Google Vision credentials are not usable`：確認 `GOOGLE_APPLICATION_CREDENTIALS` 指到容器內存在的檔案，或看上面「憑證怎麼給」。Mode A 會失敗；Mode B 的 OCR 警告顯示「沒有」，判定仍由 judge 決定。
- Mode A：`Claude refused ...`：模型安全分類器拒絕，頁面會顯示類別；預設已開 server-side fallback。
- Mode B：`the model returned no image (it said: ...)`：該模型沒有輸出圖片，換一個模型，或把 `OPENROUTER_IMAGE_API` 設成 `images` / `chat` 試另一條路。
- 判定是 `unverified`：judge 不可用（沒設 `ANTHROPIC_API_KEY`、Claude API 錯誤、被拒絕、JSON 不合 schema）、被關掉，或沒有輸出圖；頁面會寫原因。這不影響生成與 OCR 警告，只是這次沒有判定也不會重試；補好金鑰後重跑。
- 判定不是 `pass` 但圖看起來沒問題：看 judge 的問題清單與 `where`（它指出的區域）。judge 可能誤報；以眼睛為準，必要時把 `CLAUDE_JUDGE_MODEL` 換成別的型號比較。
- Mode A 的字還是很小：看 unit 表的「用字」與「字級（比例）」：`readable: false` 表示連 `short` 都放不進、只好縮小；請 Claude 給更短的 `short`（重試會要求）或接受。`skipped: too small` 是次要 unit，保持原樣。
- Mode B 出現 `HTTP 429`：OpenRouter 的速率限制。工具已經等 20 秒重試過一次還是 429，才會顯示；減少模型數或連跑次數，或稍後再試。
- 「給審閱者的連結」顯示缺變數：補齊 `.env` 後重啟容器（`docker compose up -d`）。顯示上傳失敗：看頁面的「提醒」（例如 bucket 不存在、金鑰錯誤；金鑰本身不會出現在訊息裡）。批次 manifest 沒出現：要等每一張圖都跑完並上傳完；有一張的 job manifest 沒上傳成功時，批次 manifest 照樣上傳，只是那一張的連結是 `null`。
- 連結審閱者打不開：確認用的是 `MINIO_PUBLIC_ENDPOINT` 簽的（警告區有提醒的話表示簽成了內部位址），且沒有過期。
- 下拉選單只有預設模型：OpenRouter 模型清單抓取失敗（狀態列會顯示原因），不影響執行。
- 測試：`docker compose run --rm lab pytest -q`（容器內也有測試；README 與 prompt 一致性的那些測試會因為映像沒有 README 而略過）。
