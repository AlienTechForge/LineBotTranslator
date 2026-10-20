# overlay-lab：圖片翻譯 overlay 的離線對照實驗

> 這是獨立的 Python 原型，**不是正式服務**，跟 Java 的 LINE bot 沒有任何程式碼上的關係，也不會被 bot 載入。
> 目的只有一個：用同一批測試圖，比較「模型驅動」的做法能做到什麼程度，再決定要不要改正式版。

頁面是一排並排的欄位：**原圖｜Mode A（hybrid，選用）｜Mode B 的每個模型各一欄**（欄位多時橫向捲動）。

- **Mode B（影像模型，主線）**：整張圖丟給 OpenRouter 上有「圖片輸出」能力的模型，要它「把所有文字換成譯文，其餘不動」。可以一次勾選最多 4 個模型平行跑、並排比較。每張輸出都會**自動驗證**（Vision OCR 比對來源圖與輸出圖：數字有沒有被改、有沒有漏翻），判定不是 `pass` 時**自動重試一次**，兩次的結果都留著。
- **Mode A（hybrid，選用、預設關閉）**：Google Vision 只負責給「每個字的框」→ Claude（看圖 + 看字清單）決定哪些字屬於同一個翻譯單位（unit）、閱讀方向、角色、要不要保留，並翻譯 → 我們的程式用字框算出每個 unit 的幾何 → 用 OpenCV inpaint 把原字抹掉（背景重建，不是塗色塊）→ 用 Pillow 以原本的位置、大小、顏色把譯文畫回去。不再是主線，但仍可用。
- **給審閱者的連結**：job 跑完後，所有圖片與 JSON 會在背景上傳到你的 MinIO，頁面上有一行「給審閱者的連結」（manifest 的簽名網址），審閱者不用開頁面也能抓到結果。

沒有針對任何單張圖的特殊規則：分組、方向、角色都是 Claude 看圖判斷，幾何與繪製是通用程式。

## 為什麼是現在這個樣子（第 2 輪實驗的發現）

Mode B 用 `google/gemini-3-pro-image` 在四張測試圖（收據 + 直式菜單、越南文資訊圖、旋轉 90° 的美髮價目表、日文文章）上都接近 Google Lens 的品質，但有兩種缺陷肉眼不容易一次看完：

1. 模型**改了手寫數字**（客戶電話 77446716 變成 09446916、手寫時間變成 12:30）；
2. 有一行標題**沒翻**。

另外：chat 路徑曾經回 `finish_reason=error`，換 `/api/v1/images` 就成功；輸出寬度只有約 900–1000 px。所以這一輪加了：更嚴格的 prompt、依模型挑 API 路徑、輸出尺寸選項、每張輸出自動驗證 + 一次重試、多模型並排、結果上傳 MinIO。

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

### Anthropic 金鑰（只有 Mode A 需要）

`ANTHROPIC_API_KEY` 填進 `.env`。預設模型 `claude-opus-5-5`，可用 `CLAUDE_MODEL` 換。

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
| `ANTHROPIC_API_KEY` | 無 | Mode A 才需要。 |
| `CLAUDE_MODEL` | `claude-opus-5-5` | Mode A 用的模型。價目表只有 `claude-opus-5-5`（4 / 20 USD per 百萬 tokens）、`claude-sonnet-5-5`（2 / 10）、`claude-haiku-5-5`（0.10 / 0.50）；其他型號只顯示 tokens。 |
| `OPENROUTER_API_KEY` / `OPEN_ROUTE_API_KEY` | 無 | Mode B 必填，兩個名稱擇一。 |
| `OPENROUTER_IMAGE_MODEL` | `google/gemini-2.5-flash-image` | 頁面模型多選預設勾選的那一個；清單會列出 OpenRouter 上所有「輸出含圖片」的模型（快取 10 分鐘）。 |
| `OPENROUTER_IMAGE_API` | `auto` | `auto`：依模型挑順序（見下方「API 路徑」）；`chat` / `images`：所有模型都只走那一條。 |
| `OPENROUTER_HTTP_REFERER` | 空 | 選填，送給 OpenRouter 的 `HTTP-Referer`（也接受 `OPEN_ROUTE_HTTP_REFERER`）。 |
| `MINIO_ENDPOINT` | `MINIO_PUBLIC_ENDPOINT` 的值 | 上傳用的位址。可寫 `https://…` / `http://…`；沒寫 scheme 時，`localhost`、沒有點的主機名（docker service 名）與 IP 當 http，其他當 https。 |
| `MINIO_PUBLIC_ENDPOINT` | 空 | 簽發給審閱者的連結所用的位址（要連得到，例如 `https://s3.azndev.com`）。沒設時用 `MINIO_ENDPOINT` 簽，若那看起來是內部位址會在警告區提醒。 |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | 無 | 必填才會上傳。不會出現在頁面、log、result.json 或 manifest。 |
| `MINIO_BUCKET_NAME` | `line-bot` | bucket（必須已存在，這個工具不會建 bucket）。 |
| `MINIO_PREFIX` | `overlay-lab` | 物件 key 前綴：`<prefix>/<job-id>/<name>`。 |
| `MINIO_URL_EXPIRY_HOURS` | `168` | 簽名連結有效小時數，限制在 1–168（S3 簽章連結最長 7 天）。 |
| `MINIO_REGION` | `us-east-1` | 簽章用的 region（與正式 bot 相同的變數）；有給的話簽連結不用連網。 |
| `LAB_TOKEN` | 空 | 設了之後每個請求都要帶 `?token=` 或標頭 `X-Lab-Token`，否則 401。 |
| `LAB_PORT` | `4041` | 對外埠。 |
| `LAB_MOCK` | `0` | `1` = 不打任何網路，改用內建 fixture（Mode B 直接回傳輸入圖，Vision 用內建字框，上傳改用記憶體假物件）。 |
| `LAB_DATA_DIR` | `./data` | 上傳與輸出（`<job-id>/` 底下）；容器內固定 `/app/data`。 |
| `LAB_INK_FRACTION` | `0.40` | 取文字顏色時，用字框內「離背景最遠」的這個比例的像素。細小的字顏色會偏淡，調小（例如 `0.2`）會比較深。 |
| `LAB_FONT_CJK`、`LAB_FONT_CJK_BOLD`、`LAB_FONT_LATIN`、`LAB_FONT_LATIN_BOLD` | 自動尋找 | 選填，指定字型檔。 |

安全：頁面只給你自己用。上傳上限 20 MB，只收 jpg / png / webp，輸出只從 `LAB_DATA_DIR/<job-id>/` 提供；uvicorn 的 access log 已關閉（網址可能帶 `?token=`）；log 不會出現圖片內容、金鑰、OCR 文字或簽名連結（連結含存取金鑰 ID 與簽章，只留在頁面、`result.json` 與 manifest）。

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

自動重試時，同一個模型收到的是**同一張原圖**，prompt 後面空一行再接第二段（`RETRY_TEMPLATE`），`{problems}` 依哪項檢查沒過列出：

```
Your previous attempt at this same image failed an automatic check. Redo the whole image from the original and fix exactly the following, the hard rules above still apply:
{problems}
```

其中數字那一行是 `- These digit sequences of the original image were changed or lost in your output. Copy them exactly as they are written in the original, character for character: {runs}.`（列出遺失或被改的數字，照來源圖上的寫法，最多 20 個），殘留那一行是 `- These words are still in the source language in your output. Translate them into {language} as well: {words}.`（最多 10 個還沒翻掉的字）。哪一行有東西可列才會出現：沒有遺失的數字就沒有數字那一行，沒有殘留的字就沒有殘留那一行；兩邊都沒有就送原本的 prompt。

## API 路徑與輸出尺寸

**路徑順序（每個模型各自決定，頁面的模型清單會標「images 優先」）**：`google/gemini-3*` 與名稱含 `nano-banana` 的模型先試 `/api/v1/images`、失敗再走 `chat/completions`；其他模型維持原本順序（先 chat、再 images）。`OPENROUTER_IMAGE_API=chat` 或 `images` 會強制所有模型只走那一條。換路徑的條件：路徑回 400 / 404 / 405 / 422、該路徑沒回圖（含 `finish_reason=error`）、或 images 路徑回 502（OpenRouter 文件：生成失敗時回 502、不計費）。401 / 402 / 429 與其他 5xx 不會被換路徑掩蓋，直接顯示錯誤。

**輸出尺寸（頁面：預設 / 1K / 2K / 4K）**：OpenRouter 的 [Image API 文件](https://openrouter.ai/docs/features/multimodal/image-generation)有定義尺寸參數 `resolution`（等級 `512`、`768`、`1K`、`1.5K`、`2K`、`4K`；`size` 是它的簡寫，同一頁的「Resolution and Aspect Ratio」一節），所以選了 1K / 2K / 4K 時會把 `resolution` 帶到 **images 路徑**（`aspect_ratio` 照舊一起帶）。

- **chat 路徑沒有文件化的尺寸參數**（`image_config` 的鍵依模型／供應商而異），所以不會送；模型欄會顯示「尺寸選項：2K（未套用：chat 路徑沒有文件化的尺寸參數）」。
- 供應商拒絕 `resolution`（400 / 404 / 422）時，會先拿掉它再試一次（再不行才拿掉 `aspect_ratio`），欄位顯示「未套用：供應商拒絕 resolution，已略過」，API 路徑欄位會寫 `images (no resolution)`。
- 文件說各模型實際支援的值要看 `GET /api/v1/images/models` 的 `supported_parameters`；這個工具不預先查，以「帶了、被拒就拿掉」處理。
- **每個模型欄一律顯示實際輸出尺寸與長寬比差**（影像模型常常不照原圖長寬比輸出，差越多代表版面越不可能對得上）。要看哪個模型真的給了更大的圖，看這一行。

## 驗證（每張 Mode B 輸出）

流程：Vision OCR 來源圖（每個 job 一次，Mode A 與所有模型共用，快取）與每張輸出圖 → 兩項檢查 → 判定。

**數字完整性**（`digits_ok`、`missing_digit_runs`）

- 從來源圖 OCR 的每個字取出所有「連續 **2 位以上**的數字」；兩個數字之間可以夾**一個** `- : / . ,`（`12:30`、`0912-345-678`、`1,280.50`、`2024/05/03` 各是一組），去掉分隔符後比對（`1230`、`0912345678`、`128050`、`20240503`）。全形與其他文字的數字先轉成 0–9。
- 輸出端是「輸出圖 OCR 全文的所有數字串接起來」的字串，每組來源數字都必須是它的子字串。所以重新排版（空格、換行、分隔符換了）不會誤判，數字被改（77446716 → 09446916）會抓到。
- 重複：同一組數字在來源出現幾次都只算一組（`missing_digit_runs` 也只列一次），輸出裡找得到就算過；來源裡 `12:30` 與 `1230` 去掉分隔符後是同一組。
- 限制：輸出端是把所有數字串接起來比對，所以 2–3 位的短數字在數字很多的輸出裡很容易「剛好」出現，這種數字被改不一定抓得到；長數字（電話、金額、日期、時間）才是這項檢查的重點。

**來源語言殘留**（`leftover_ok`、`leftover_ratio`、`leftover_words`）

- 來源語言 = 來源字詞的 Vision 偵測語言多數決（每個字的 `detected_languages` 取信心最高的；Vision 對那個字沒給時沿用它所屬段落、區塊或頁面的）；沒有偵測結果時改用文字 script 判斷（漢字、假名、諺文、帶越南文字母的拉丁字母…）。日文與中文共用漢字，Vision 常把日文裡的純漢字詞標成 `zh`，所以多數決是 `zh` 但含假名的詞佔「漢字／假名詞」10% 以上時視為日文。
- 輸出端統計「語言是來源語言」的字，**不算**：純數字、純標點、只有 1 個字元的字、網址與 e-mail、以及整個字都是用目標語言的文字（目標 script）寫的字（例如目標是中文時，純漢字詞無法證明是殘留）。目標語言的 script：`en` 拉丁、`vi` 拉丁（含越南文字母）、`zh-TW` / `zh-CN` 漢字（含注音）、`ja` 漢字 + 假名、`ko` 諺文。
- `leftover_ratio` = 上面統計到的殘留字數 ÷ 來源圖中「來源語言的字」的數量（分母同樣不算純數字、純標點、1 個字元的字）；同時列出前 20 個（不重複、依閱讀順序）殘留的字。**`leftover_ok` = ratio ≤ 0.08**（剛好 8% 算過）。來源完全沒有可判斷語言的字時視為通過。

**判定（徽章）**

| 徽章 | 意思 |
| --- | --- |
| `pass`（綠） | 數字都在，殘留比例 ≤ 8% |
| `partial`（橘） | 數字都在，但殘留比例 > 8%（有漏翻） |
| `digits-changed`（紅） | 有 2 位以上的數字遺失或被改，殘留比例 ≤ 8% |
| `digits-changed+partial`（深紅） | 兩者都有 |
| `unverified`（灰） | 驗證本身沒跑成功（例如 Vision 憑證不能用，原因會顯示在欄位）；圖照常顯示，**不會重試** |

模型欄的徽章旁有可展開的「驗證細節」：判定、來源語言與偵測方式、缺少的數字（照來源圖上的寫法）、殘留比例（含門檻與分子分母）、殘留的字、驗證耗時；勾「顯示驗證細節」會預設展開，並多附上輸出圖的 OCR 全文（沒勾就不會把 OCR 全文留在結果裡）。每次驗證的 JSON 也存成檔案（`B<n>_<模型>_a<k>_verify.json`，也會上傳）。

**一次自動重試**

- 只有「第一次的判定不是 `pass`、而且驗證有跑成功」才重試，最多一次：同一個模型、同一張原圖，prompt 加上上面的第二段，指名漏的數字與最多 10 個還沒翻的字。重試失敗（例如 API 錯誤）時沿用第一次的結果並顯示原因。
- **兩次的圖與判定都保留**。主要顯示的是「比較好的那一次」，另一次收在折疊面板裡。選擇規則：`pass` > `partial` > `digits-changed` > `digits-changed+partial` > 無法驗證；同等級時，缺少的數字組數較少者勝，再比較殘留比例較低者；仍相同就留第一次。因此**改了數字的那一次絕不會贏過沒改數字的那一次**（`pass` / `partial` 都比任何 `digits-changed*` 好）。
- 費用與耗時是**兩次加總**（欄位上另外列出各次）。

## 怎麼讀結果

每個欄位上方有耗時；Mode A 另外拆成各階段。

**Mode B 欄（每個模型一欄）**
- 標題：模型 id、狀態、驗證徽章。一個模型失敗（錯誤顯示在該欄）不影響其他模型。
- `耗時（含驗證與重試）`、`費用`、`tokens`：兩次嘗試的加總（有重試時括號內列出生成與驗證各占多少、各次費用）；費用是 OpenRouter 回應的 `usage.cost`，回應沒有帶才顯示「回應沒有帶 cost」。
- `API 路徑`：實際走 `chat` 還是 `images`（`(no aspect ratio)`、`(no resolution)` 表示拿掉了那個參數才成功），括號內是該模型的優先路徑。
- `輸出尺寸（長寬比差 x%）`、`尺寸選項`：見上。
- 「嘗試 1／2」折疊面板：沒被採用的那一次（圖、API 路徑、尺寸、驗證細節；重試那次可看實際送出的 prompt）。
- 「模型附帶文字」是模型除了圖以外說的話（拒絕時通常在這裡）。

**Mode A 欄**（有勾才會出現）
- `Vision` / `Claude` / `erase / render`：各階段毫秒（Vision 是共用的那一次呼叫的時間）。`tokens (in / out)` 與 `估計費用` 來自 Claude 回應的 usage（`out` 含思考 tokens）。
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

**比較時建議看**：驗證徽章（有沒有改數字、有沒有漏翻）、完成度、版面與字體是否保留、顏色、直排／旋轉頁面的處理、輸出尺寸、費用與耗時，以及模型有沒有改動不該動的圖形或文字。

## 給審閱者的連結（MinIO）

job 全部跑完之後（不論有沒有錯誤），背景執行緒會把下面這些上傳到 `<MINIO_BUCKET_NAME>/<MINIO_PREFIX>/<job-id>/`（key 是 `<prefix>/<job-id>/<name>`）：

| name | 內容 |
| --- | --- |
| `original.<ext>` | 送出時的原圖 |
| `A.png`、`A_units.json` | Mode A 的結果圖與 units（有勾 Mode A 才有） |
| `B<n>_<模型>_a1.png`、`..._a2.png` | Mode B 第 n 個模型的第 1、第 2 次嘗試的圖 |
| `B<n>_<模型>_a<k>_verify.json` | 該次嘗試的驗證結果 |
| `manifest.json` | 這次執行的 metadata 與**每個物件的簽名 GET 網址** |

`manifest.json` 有：`job_id`、各時間、`target_language`、`options`（模型清單、輸出尺寸、是否驗證…）、`original`，`mode_a`（耗時、tokens、費用、圖與 units 的連結），`mode_b[]`（每個模型：`model`、`route_preference`、`api_used`、`resolution_requested` / `resolution_applied`、`output_size`、`aspect_drift_pct`、`elapsed_ms`、`usage`、`cost_usd`、`verdict`、`selected_attempt`、採用的圖 `image`，以及 `attempts[]`：每次嘗試的圖、驗證 JSON、`verdict`、缺少的數字、殘留比例與字、路徑、尺寸、耗時、tokens、費用、送出的 prompt），`objects[]`（`name`、`key`、`bytes`、`content_type`、`url`），`warnings`，以及 manifest 自己的 `manifest.url`。單一物件上傳失敗不會讓 job 失敗：那個物件在 manifest 裡只有 `error`、沒有 `url`，並出現在頁面的「提醒」裡。

頁面在「給審閱者的連結」那一行顯示 manifest 的簽名網址（連結有效 `MINIO_URL_EXPIRY_HOURS` 小時，預設 168 = 7 天），旁邊有「複製」鈕；上傳還在跑時顯示「上傳到 MinIO 中…」，沒設定時顯示缺哪些變數。同一個網址也在 `GET /api/jobs/<job-id>` 的 `publish.manifest_url`（log 裡只記上傳了幾個物件，不記連結）。manifest 也會留一份在本機 `LAB_DATA_DIR/<job-id>/manifest.json`。

注意：

- **任何拿到連結的人都能下載**（原圖也在裡面，含圖上所有個資），到期前無法撤銷，只能刪物件。請只把連結交給要看的人。
- 清掉舊物件（先用 `mc alias set <alias> https://s3.azndev.com <ACCESS_KEY> <SECRET_KEY>` 設好別名；bucket 名稱以你實際的 `MINIO_BUCKET_NAME` 為準）：

  ```bash
  mc rm -r --force <alias>/line-bot/overlay-lab/                       # 這個工具上傳的全部
  mc rm -r --force <alias>/line-bot/overlay-lab/<job-id>/              # 單一 job
  mc rm -r --force --older-than 7d <alias>/line-bot/overlay-lab/       # 只清 7 天前的
  ```

## 直接打 API（不開頁面）

`POST /api/jobs`（multipart）：

| 欄位 | 預設 | 說明 |
| --- | --- | --- |
| `file` | 必填 | jpg / png / webp |
| `target_lang` | `en` | `en`、`zh-TW`、`zh-CN`、`ja`、`ko`、`vi` |
| `mode_b` / `mode_a` | `0` / `0` | 至少選一個；頁面預設勾 Mode B、不勾 Mode A |
| `image_models` | `OPENROUTER_IMAGE_MODEL` | 最多 4 個；欄位可重複，或用逗號分隔。舊的單一欄位 `image_model` 仍可用 |
| `output_size` | 空（預設） | `1K` / `2K` / `4K`，只套用在 images 路徑 |
| `verify` | `1` | `0` = 不驗證、不重試（頁面沒有這個開關，驗證一律開著） |
| `verify_details` | `0` | `1` = 在結果裡附上輸出圖的 OCR 全文（頁面的「顯示驗證細節」） |

回傳 `{"job_id": ...}`；用 `GET /api/jobs/<job-id>` 輪詢（`status: done` 之後 `publish.status` 還會從 `pending` → `running` → `done` / `skipped` / `error`），`mode_b.results[]` 是每個模型的結果。圖在 `GET /files/<job-id>/<name>`。

## 費用備註

- 每個 Mode B 模型 = 1 次影像生成（驗證不過會再多 1 次）。費用取決於模型與尺寸：頁面每欄顯示 OpenRouter 回應的 `usage.cost`（有重試時是兩次加總，並列出各次），回應沒有帶 cost 的模型只顯示 tokens。OpenRouter 文件提到 images 路徑的定價可能依解析度分級（例如 2K、4K），所以選大尺寸通常更貴。同時比較 4 個模型就是 4 倍。
- 驗證 = 每個 job 1 次來源圖 Vision（共用）+ 每張輸出圖 1 次 Vision（依 Google 當時的計價）。
- Mode A = 1 次 Vision `document_text_detection`（與 Mode B 的來源圖 OCR 共用，不會重複打）+ 1 次 Claude（圖 + 字清單，輸出 JSON）。Claude 用 `effort: high`，思考 tokens 計入輸出，所以字多的圖可能比想像貴；實際數字以頁面顯示的 tokens 與估計費用為準（估計只依上面的價目表，沒算快取與長上下文加價）。
- 每次按「開始」，選了的模型與 Mode 都會各打一次付費 API。沒有快取；自動重試最多每個模型一次。

## 這個工具「不是」什麼

- **不是正式版**：沒有 LINE、沒有 MongoDB、沒有佇列與限流、沒有使用者隔離（MinIO 只用來交付結果）。圖片與結果會留在 `./data/`，自己清。
- **inpaint 是 OpenCV Telea，不是生成式模型**：平坦背景幾乎看不出來（顏色可能有 ±幾階的差），但漸層、照片、複雜紋理上的大面積文字會糊、會留下痕跡。
- Mode A 的譯文只用一種顏色、一種字型家族（Noto Sans CJK / DejaVu Sans）畫，不會還原原本的字型；字體粗細只靠角色與筆畫粗細估計。
- 驗證是靠 OCR 比對，不是人眼：OCR 讀錯（手寫字、很小的字）會造成誤判，判定是提示不是定論；短數字（2–3 位）的檢查較寬鬆；中日韓的單字元詞不計入殘留比例。
- 整張圖會被送到 Anthropic（Mode A）、OpenRouter / 其後的模型供應商（Mode B）與 Google Vision，也會（設了 MinIO 時）上傳到你的 MinIO，含圖上的所有個資，請自行評估。
- 字清單太大（估計超過約 12k tokens）只會提醒，不會丟字；超過 150k tokens 會直接拒絕。

## 疑難排解

- Mode A / 驗證：`Google Vision credentials are not usable`：確認 `GOOGLE_APPLICATION_CREDENTIALS` 指到容器內存在的檔案，或看上面「憑證怎麼給」。驗證的話徽章會是 `unverified`。
- Mode A：`Claude refused ...`：模型安全分類器拒絕，頁面會顯示類別；預設已開 server-side fallback。
- Mode B：`the model returned no image (it said: ...)`：該模型沒有輸出圖片，換一個模型，或把 `OPENROUTER_IMAGE_API` 設成 `images` / `chat` 試另一條路。
- 「給審閱者的連結」顯示缺變數：補齊 `.env` 後重啟容器（`docker compose up -d`）。顯示上傳失敗：看頁面的「提醒」（例如 bucket 不存在、金鑰錯誤；金鑰本身不會出現在訊息裡）。
- 連結審閱者打不開：確認用的是 `MINIO_PUBLIC_ENDPOINT` 簽的（警告區有提醒的話表示簽成了內部位址），且沒有過期。
- 下拉選單只有預設模型：OpenRouter 模型清單抓取失敗（狀態列會顯示原因），不影響執行。
- 測試：`docker compose run --rm lab pytest -q`（容器內也有測試；README 與 prompt 一致性的那個測試會因為映像沒有 README 而略過）。
