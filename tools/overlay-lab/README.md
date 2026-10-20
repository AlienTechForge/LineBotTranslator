# overlay-lab：圖片翻譯 overlay 的離線對照實驗

> 這是獨立的 Python 原型，**不是正式服務**，跟 Java 的 LINE bot 沒有任何程式碼上的關係，也不會被 bot 載入。
> 目的只有一個：用同一批測試圖，比較「模型驅動」的做法能做到什麼程度，再決定要不要改正式版。

頁面是一排並排的欄位：**原圖｜Mode A（hybrid，選用）｜Mode B 的每個模型各一欄**（欄位多時橫向捲動）。

- **Mode B（影像模型，主線）**：整張圖丟給 OpenRouter 上有「圖片輸出」能力的模型，要它「把所有文字換成譯文，其餘不動」。可以一次勾選最多 4 個模型平行跑、並排比較，每個模型還能**連跑 1–3 次**（批次模式）來算通過率。每張輸出都會**自動驗證**：Vision OCR 比對來源圖與輸出圖（數字有沒有被改、有沒有漏翻，含「大字沒翻」），再由 Claude 當**語意 judge** 檢查有沒有亂碼（編造的字）與完成度；判定不是 `pass` 時**自動重試一次**，兩次的結果都留著。
- **Mode A（hybrid，選用、預設關閉）**：Google Vision 只負責給「每個字的框」→ Claude（看圖 + 看字清單）決定哪些字屬於同一個翻譯單位（unit）、閱讀方向、角色、要不要保留，並翻譯 → 我們的程式用字框算出每個 unit 的幾何 → 用 OpenCV inpaint 把原字抹掉（背景重建，不是塗色塊）→ 用 Pillow 以原本的位置、大小、顏色把譯文畫回去。不再是主線，但仍可用。
- **給審閱者的連結**：job 跑完後，所有圖片與 JSON 會在背景上傳到你的 MinIO，頁面上有一行「給審閱者的連結」（manifest 的簽名網址），審閱者不用開頁面也能抓到結果。

沒有針對任何單張圖的特殊規則：分組、方向、角色都是 Claude 看圖判斷，幾何與繪製是通用程式。

## 為什麼是現在這個樣子（第 2 輪實驗的發現）

Mode B 用 `google/gemini-3-pro-image` 在四張測試圖（收據 + 直式菜單、越南文資訊圖、旋轉 90° 的美髮價目表、日文文章）上都接近 Google Lens 的品質，但有兩種缺陷肉眼不容易一次看完：

1. 模型**改了手寫數字**（客戶電話 77446716 變成 09446916、手寫時間變成 12:30）；
2. 有一行標題**沒翻**。

另外：chat 路徑曾經回 `finish_reason=error`，換 `/api/v1/images` 就成功；輸出寬度只有約 900–1000 px。所以這一輪加了：更嚴格的 prompt、依模型挑 API 路徑、輸出尺寸選項、每張輸出自動驗證 + 一次重試、多模型並排、結果上傳 MinIO。

## 第 3 輪的發現，以及這一輪（第 4 輪）改了什麼

第 3 輪在真實圖片上的結果：`google/gemini-3-pro-image` 與 `google/gemini-3.1-flash-image`（經 OpenRouter、2K 輸出）的 Mode B 已經接近 Google Lens，但**每一次執行都不穩定**，而當時的 OCR 驗證漏掉最糟的失敗：

1. **目標語言的亂碼**：Pro 在直式菜單上整欄畫出編造的英文字（"Banero Salaino Az-infe Rilchkan"），在越南文海報的某一次把中文內文畫成無意義的漢字；Flash 把一個中文標籤畫成隨機漢字。這些字「就是目標語言的文字」，所以數字與殘留檢查都放行。
2. **手寫數字的誤報**：Vision 把手寫的來源電話 77446716 讀成 97446716，兩個模型第一次都被判 `digits-changed`，其實都畫對了；多餘的重試還讓 Pro 的結果變差。
3. **大字的門檻太鬆**：Flash 把海報標題的第二行留成越南文，只有 260 個字裡的 3 個（0.0115），沒有達到 `partial`。標題沒翻比註腳沒翻嚴重得多。
4. **同一張圖有時過、有時亂碼**：看單次結果沒有意義，要看每個模型的**通過率**。每月大約 50 張圖，成本其次、品質優先：兩個模型都跑、挑驗證過最好的一個是負擔得起的。

所以這一輪加了（細節在後面各節）：

- **語意 judge**（`app/judge.py`，預設開）：每次嘗試一次 Claude 呼叫，讀來源與輸出的 OCR 文字，給出自己的參考翻譯、完成度 `coverage`，以及亂碼／沒翻／翻錯／數字被改的段落；有亂碼或 `coverage < 0.90` 就是新的判定 `garbled`。
- **手寫數字的容忍**：低信心（< 0.75）的來源數字是「軟」的，找不到只警告、不判 `digits-changed`；5 位以上容許差 1 個數字。
- **大字殘留規則**：殘留的來源語言字若比輸出字高的中位數大 2 倍以上，不管比例就是 `partial`。
- **重試 prompt** 也帶上 judge 找到的亂碼、沒翻的段落與被改的數字。
- **批次**：每個模型連跑 1–3 次，每次獨立成一欄，另有各模型的彙總列（通過率、平均總費用與時間、最佳一次）；同時最多 4 個生成在跑，429 等 20 秒重試一次。
- 輸出尺寸預設改成 `2K`。

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

### Anthropic 金鑰（Mode A，以及 Mode B 的語意 judge）

`ANTHROPIC_API_KEY` 填進 `.env`。Mode A 預設模型 `claude-opus-5-5`（`CLAUDE_MODEL`）；Mode B 的語意 judge 預設 `claude-sonnet-5-5`（`CLAUDE_JUDGE_MODEL`）。沒有這支金鑰時 Mode B 照常跑，只是每次嘗試的 judge 顯示 `unavailable`，判定只看 OCR 檢查（所以抓不到亂碼）。

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
| `ANTHROPIC_API_KEY` | 無 | Mode A 與 Mode B 的語意 judge 需要。沒有的話 judge 顯示 `unavailable`（不影響生成與其他檢查）。 |
| `CLAUDE_MODEL` | `claude-opus-5-5` | Mode A 用的模型。價目表只有 `claude-opus-5-5`（4 / 20 USD per 百萬 tokens）、`claude-sonnet-5-5`（2 / 10）、`claude-haiku-5-5`（0.10 / 0.50）；其他型號只顯示 tokens。 |
| `CLAUDE_JUDGE_MODEL` | `claude-sonnet-5-5` | Mode B 語意 judge 用的模型（每次嘗試一次呼叫，見「驗證」）。價目表同上，其他型號只顯示 tokens、不估費用。 |
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

`{problems}` 是下面這些行，**哪項檢查有東西可列才會出現**（都沒有就送原本的 prompt）。這些句子定義在 `app/openrouter.py`（測試會檢查下面這份與程式碼一致）：

```
- These digit sequences of the original image were changed or lost in your output. Copy them exactly as they are written in the original, character for character: {runs}.
- These words are still in the source language in your output. Translate them into {language} as well: {words}.
- These parts of your output are not real {language} text: {outputs}. Re-render them as the correct translation of: {sources}.
- These parts of your output are not real {language} text: {outputs}. Re-render them as correct {language} text that translates the original text at the same place.
- These parts of your output are still in the source language. Translate them into {language} as well: {segments}.
- These numbers differ between the original image and your output. Copy each of them exactly as it is written in the original: {pairs}.
```

- 數字那一行：只列**硬**的遺失數字，照來源圖上的寫法，最多 20 個。軟的（低信心的手寫）從不列入：叫模型「照抄」一個 Vision 可能讀錯的數字，會把原本畫對的改錯。
- 殘留那一行：最多 10 個還沒翻掉的字，「大字沒翻」的字排在最前面。
- 亂碼那一行（judge）：最多 10 段亂碼；`{outputs}` 是輸出裡的亂碼，`{sources}` 是 judge 指出它們對應的來源文字。judge 說不出對應的來源時用 `BLIND` 那句。
- 沒翻那一行（judge）：最多 10 段。
- 數字那一行（judge）：最多 10 組「原文 → 輸出」，**略過**只是格式不同的（12:30 與 12.30）和對應到軟數字的（Vision 可能讀錯的那種）。
- 引用的片段是 OCR 讀到的圖上文字，不是指令：壓成單行、換掉雙引號、每段最多 120 字元。

## API 路徑與輸出尺寸

**路徑順序（每個模型各自決定，頁面的模型清單會標「images 優先」）**：`google/gemini-3*` 與名稱含 `nano-banana` 的模型先試 `/api/v1/images`、失敗再走 `chat/completions`；其他模型維持原本順序（先 chat、再 images）。`OPENROUTER_IMAGE_API=chat` 或 `images` 會強制所有模型只走那一條。換路徑的條件：路徑回 400 / 404 / 405 / 422、該路徑沒回圖（含 `finish_reason=error`）、或 images 路徑回 502（OpenRouter 文件：生成失敗時回 502、不計費）。401 / 402 / 429 與其他 5xx 不會被換路徑掩蓋，直接顯示錯誤。

**輸出尺寸（頁面：預設（供應商的尺寸）/ 1K / 2K / 4K，頁面與 API 的預設值是 `2K`）**：OpenRouter 的 [Image API 文件](https://openrouter.ai/docs/features/multimodal/image-generation)有定義尺寸參數 `resolution`（等級 `512`、`768`、`1K`、`1.5K`、`2K`、`4K`；`size` 是它的簡寫，同一頁的「Resolution and Aspect Ratio」一節），所以選了 1K / 2K / 4K 時會把 `resolution` 帶到 **images 路徑**（`aspect_ratio` 照舊一起帶）。

- **chat 路徑沒有文件化的尺寸參數**（`image_config` 的鍵依模型／供應商而異），所以不會送；模型欄會顯示「尺寸選項：2K（未套用：chat 路徑沒有文件化的尺寸參數）」。
- 供應商拒絕 `resolution`（400 / 404 / 422）時，會先拿掉它再試一次（再不行才拿掉 `aspect_ratio`），欄位顯示「未套用：供應商拒絕 resolution，已略過」，API 路徑欄位會寫 `images (no resolution)`。
- 文件說各模型實際支援的值要看 `GET /api/v1/images/models` 的 `supported_parameters`；這個工具不預先查，以「帶了、被拒就拿掉」處理。
- **每個模型欄一律顯示實際輸出尺寸與長寬比差**（影像模型常常不照原圖長寬比輸出，差越多代表版面越不可能對得上）。要看哪個模型真的給了更大的圖，看這一行。

## 驗證（每張 Mode B 輸出）

流程：Vision OCR 來源圖（每個 job 一次，Mode A 與所有模型共用，快取）與每張輸出圖 → **語意 judge**（Claude，讀兩邊的 OCR 文字）→ 三項檢查（數字、殘留、語意）→ 判定。

**數字完整性**（`digits_ok`、`missing_digit_runs`、`unverified_digit_runs`、`digit_runs`）

- 從來源圖 OCR 的每個字取出所有「連續 **2 位以上**的數字」；兩個數字之間可以夾**一個** `- : / . ,`（`12:30`、`0912-345-678`、`1,280.50`、`2024/05/03` 各是一組），去掉分隔符後比對（`1230`、`0912345678`、`128050`、`20240503`）。全形與其他文字的數字先轉成 0–9。
- 輸出端是「輸出圖 OCR 全文的所有數字串接起來」的字串，每組**硬**的來源數字都必須是它的子字串。所以重新排版（空格、換行、分隔符換了）不會誤判，數字被改（77446716 → 09446916）會抓到。
- 重複：同一組數字在來源出現幾次都只算一組（`missing_digit_runs` 也只列一次），輸出裡找得到就算過；來源裡 `12:30` 與 `1230` 去掉分隔符後是同一組。
- **手寫數字的容忍（第 4 輪）**：每組來源數字有一個「信心」，是讀出它的那些字的 Vision 字信心（`confidence`）中最高的一個。
  - 信心 **< 0.75** 是**軟（soft）**，**≥ 0.75**（或 Vision 沒回報信心，值為 0）是**硬（hard）**。
  - **硬**：維持上面的規則，必須是輸出數字串的子字串。沒找到 → `missing_digit_runs`（只有它們會讓 `digits_ok` 為假、判定 `digits-changed`）。
  - **軟、5 位以上**：先看是不是子字串；不是的話，輸出圖 OCR 裡只要有一組**同長度、最多差 1 個數字**的連續數字（輸出的 `12:30` 這種算一組，不是把全部數字串起來再開滑動窗口），就算找到（狀態 `near`，連警告都沒有）。
  - **軟、不到 5 位**：只比對子字串，不容忍。
  - **軟但找不到**：列在 `unverified_digit_runs`（頁面「無法確認的數字」，橘色警告）。**絕不影響判定、不觸發重試**，也不會寫進重試 prompt。
  - 每一組都記在 `digit_runs[]`：數字、照來源的寫法、信心、是否軟、狀態（`ok` / `near` / `unverified` / `missing`）；頁面的「數字明細」可展開。
  - 為什麼：第 3 輪 Vision 把手寫的 77446716 讀成 97446716（信心 0.5 上下），兩個模型第一次都被誤判。
  - **代價**：一個真的被改掉、而且來源是低信心手寫的數字，現在只會是警告，不會是 `digits-changed`。看到「無法確認的數字」請用肉眼對一次；judge 的「數字被改」清單可以當第二意見，但它讀的是同一份 OCR，所以同樣不影響判定。
- 限制：輸出端是把所有數字串接起來比對，所以 2–3 位的短數字在數字很多的輸出裡很容易「剛好」出現，這種數字被改不一定抓得到；長數字（電話、金額、日期、時間）才是這項檢查的重點。

**來源語言殘留**（`leftover_ok`、`leftover_ratio`、`leftover_words`、`big_leftover_words`）

- 來源語言 = 來源字詞的 Vision 偵測語言多數決（每個字的 `detected_languages` 取信心最高的；Vision 對那個字沒給時沿用它所屬段落、區塊或頁面的）；沒有偵測結果時改用文字 script 判斷（漢字、假名、諺文、帶越南文字母的拉丁字母…）。日文與中文共用漢字，Vision 常把日文裡的純漢字詞標成 `zh`，所以多數決是 `zh` 但含假名的詞佔「漢字／假名詞」10% 以上時視為日文。
- 輸出端統計「語言是來源語言」的字，**不算**：純數字、純標點、只有 1 個字元的字、網址與 e-mail、以及整個字都是用目標語言的文字（目標 script）寫的字（例如目標是中文時，純漢字詞無法證明是殘留）。目標語言的 script：`en` 拉丁、`vi` 拉丁（含越南文字母）、`zh-TW` / `zh-CN` 漢字（含注音）、`ja` 漢字 + 假名、`ko` 諺文。
- `leftover_ratio` = 上面統計到的殘留字數 ÷ 來源圖中「來源語言的字」的數量（分母同樣不算純數字、純標點、1 個字元的字）；同時列出前 20 個（不重複、依閱讀順序）殘留的字。比例 ≤ 0.08（剛好 8% 算過）才算比例過關。來源完全沒有可判斷語言的字時視為通過。
- **大字規則（第 4 輪）**：`vision.py` 為每個字記錄 `height`，也就是字框（多邊形）的**短邊**，單位 px：橫排是字高，直排的欄是字寬，與頁面有沒有轉過無關。輸出裡殘留的來源語言字，只要它的 `height` **大於**輸出所有字高度**中位數的 2 倍**（剛好 2 倍不算），**不管比例多低都判 `partial`**（`leftover_ok` 為假）；頁面的「大字沒翻」會寫出是哪個字、它的高度、中位數與倍數（`big_leftover_words`）。重試 prompt 的殘留字清單把這些字排在最前面。理由：第 3 輪 Flash 把海報標題的第二行留成越南文，只有 3 / 260 = 0.0115，比例規則抓不到，但標題沒翻比註腳沒翻嚴重得多。

**語意 judge**（`judge`、`coverage`；第 4 輪，預設開，每次嘗試一次 Claude 呼叫）

- 它檢查什麼：OCR 的數字與殘留檢查看不出「字是目標語言的文字，但根本不是字」（編造的詞、隨機漢字）。judge 讀**來源 OCR 文字**（逐行、依閱讀順序，用快取的那一次來源 OCR）與**輸出 OCR 文字**（逐行），先自己把來源忠實翻成目標語言（`reference_translation`，當尺，也顯示在頁面），再拿它對照輸出。只送文字，不送圖；每邊最多 600 行、每行最多 400 字元（超過會截斷並在紀錄裡標 `truncated`）。
- 呼叫方式：官方 `anthropic` SDK，和 Mode A 用同一個 client、同一組環境變數、同一個 server-side fallback 階梯；模型 `CLAUDE_JUDGE_MODEL`（預設 `claude-sonnet-5-5`）；`output_config={"format": {"type": "json_schema", ...}, "effort": "medium"}`；不帶 `thinking`；`max_tokens` 8000；timeout 180 秒。
- 回答的 JSON（用 pydantic 驗證；`coverage` 超出 0–1 會被夾回去）：
  - `reference_translation`：judge 自己對來源逐行的翻譯；
  - `coverage`（0–1）：來源內容中「在輸出裡被正確翻成目標語言」的比例；
  - `garbled_segments`：輸出裡**不是真的字**的段落（編造的詞、無意義的字元），每段有 `output_text` 與它對應的 `source_text`（說不出就是空字串）；
  - `untranslated_segments`：輸出裡仍是來源語言的段落（品牌名、網址、e-mail 除外）；
  - `mistranslated_segments`：是真的字、但意思和來源不同的行；
  - `altered_numbers`：兩邊都有、但數字不同的數字（`source` / `output`）；
  - `summary`：一句話。
- **判定**：`garbled_segments` 非空，或 `coverage < 0.90`（0.90 剛好算過）→ `garbled`。`untranslated_segments`、`mistranslated_segments`、`altered_numbers` 只顯示和寫進重試 prompt，不會單獨改變判定（它們該反映在 `coverage` 裡）。
- **失敗不會讓嘗試失敗**：沒有金鑰、API 錯誤、被拒絕（refusal）、`max_tokens`、回傳的不是合法 JSON 或不符合 schema，都只會讓這次嘗試的 `judge` 標成 `unavailable`（頁面顯示原因），判定改由 OCR 檢查決定，也不會因此重試。來源圖沒有任何文字時標成 `skipped`。
- 費用與耗時：每次嘗試記錄 tokens 與費用（價目表同 Mode A；其他型號只有 tokens），頁面分別顯示「費用（生成）」「費用（judge）」「費用（合計）」。粗估：預設的 `claude-sonnet-5-5`（2 / 10 USD 每百萬 tokens）處理一張字數中等的海報，每次嘗試大約幾美分以內（0.01–0.06 USD）——這是依價目表與典型文字量算的**估計，不是實測**，以頁面每次嘗試顯示的 tokens 與費用為準。
- 它**擋不住**的：judge 讀的是 OCR 文字，不是圖。OCR 把裝飾圖案讀成字、或把亂碼讀成「看起來合理」的字，judge 都看不到；它也是一個 LLM，可能誤報或漏報。判定是提示不是定論，`garbled` 時請看頁面的亂碼清單與參考翻譯，再看圖。
- 離線：`LAB_MOCK=1` 時 judge 用內建的罐頭答案（`coverage` 1.0、沒有問題段落），完全不打網路。

judge 的 system prompt 只定義在 `app/judge.py` 的 `SYSTEM_PROMPT`（測試會檢查下面這份與程式碼一致）。user 訊息是：

```
Target language: <英文語言名> (<代碼>)

SOURCE lines (<n>), the original image:
S1: ...
OUTPUT lines (<m>), the model's redrawn image:
O1: ...
```

```
You are the quality judge of an image-translation experiment. An image model was told to redraw an image so that every piece of text in it is translated into the target language and everything else stays exactly the same. You do not see any image. You get the OCR text of the ORIGINAL image (the SOURCE) and the OCR text read back from the model's OUTPUT image, both one line per row in reading order. Decide whether the OUTPUT text is a correct, real and complete translation of the SOURCE text.

Work in this order and put the results into the JSON fields:

1. reference_translation: translate every SOURCE line faithfully and naturally into the target language, one string per source line, in the same order (an empty string for a line with nothing to translate). Keep brand names, URLs, e-mail addresses and numbers as written. This is your own yardstick: write it from the source alone, before you look at the output.

2. Compare the OUTPUT with your reference translation. The output lines can be split, merged or ordered differently from the source lines because the image was redrawn, so match by meaning, not by line number. The OCR of either side can contain small reading errors (handwriting, tiny print, decoration read as characters): never count a single misread character, stray punctuation or an isolated fragment of one or two characters against the output.

3. coverage: a number from 0 to 1, the share of the SOURCE content that appears in the OUTPUT correctly translated into the target language. Weigh content by how much text it is and how much it matters: a missing title or a column of body text counts for more than a footnote. 1 means everything is there and correct; 0.5 means about half is missing, untranslated, garbled or wrong.

4. garbled_segments: parts of the OUTPUT that are not real, meaningful text in the target language: invented or scrambled words, random characters of the target script, repeated pseudo-words, text that looks like the target language but means nothing. For each give output_text (copied from the output, without its line label) and source_text (the source line it stands for, copied from the source, or an empty string when you cannot tell). Do not list brand names, names of people or places, URLs, e-mail addresses, numbers, or real text that is merely poorly translated (that goes to mistranslated_segments).

5. untranslated_segments: text in the OUTPUT that is still in the source language (or in any language other than the target language) although it should have been translated, copied from the output. Exceptions: brand names, product names kept in their original form, URLs, e-mail addresses, and text that was already in the target language in the source.

6. mistranslated_segments: OUTPUT lines that are real target-language text but whose meaning differs from the source: output_text, source_text and problem (a few words).

7. altered_numbers: numbers (prices, dates, times, phone numbers, quantities, years) that appear in both texts with different digits: source and output, as written. Do not list a number only because it is formatted differently (12:30 and 12.30, 1,200 and 1200).

8. summary: one sentence in English on the overall result.

Be strict about garbled text, it is the failure that matters most, but never report a segment you cannot point to in the OUTPUT. Use empty arrays when there is nothing to report. Return only the JSON object that matches the schema.
```

**判定（徽章）**

三項檢查各自通過或不通過，判定就是沒通過的項目用 `+` 連起來（順序固定：`digits-changed`、`garbled`、`partial`）。徽章在 judge 的完成度低於 1 時會附上完成度，例如 `pass · 0.97`、`garbled · 0.35`。

| 徽章 | 意思 |
| --- | --- |
| `pass`（綠） | 硬數字都在、殘留比例 ≤ 8% 且沒有大字殘留、judge 沒有列出亂碼且 `coverage` ≥ 0.90（judge 不可用時只看前兩項） |
| `partial`（橘） | 其餘都過，但有漏翻：殘留比例 > 8%，或有一個大字沒翻 |
| `garbled`（紅，虛線） | 其餘都過，但輸出有亂碼，或 judge 的 `coverage` < 0.90 |
| `garbled+partial` | 有亂碼，而且有漏翻 |
| `digits-changed`（紅） | 有（硬的）2 位以上的數字遺失或被改，其餘都過 |
| `digits-changed+partial` | 數字被改，而且有漏翻 |
| `digits-changed+garbled` | 數字被改，而且有亂碼 |
| `digits-changed+garbled+partial`（深紅） | 三項都有 |
| `unverified`（灰） | 驗證本身沒跑成功（例如 Vision 憑證不能用，原因會顯示在欄位）；圖照常顯示，**不會重試** |

**優先順序（選哪一次、哪一個 run 是最佳，從最好到最差）**

| 名次 | 判定 |
| --- | --- |
| 1 | `pass` |
| 2 | `partial` |
| 3 | `garbled` |
| 4 | `garbled+partial` |
| 5 | `digits-changed` |
| 6 | `digits-changed+partial` |
| 7 | `digits-changed+garbled` |
| 8 | `digits-changed+garbled+partial` |
| — | `unverified`（比以上全部差） |

排序是依序比 (數字有沒有被改、有沒有亂碼、有沒有漏翻)，所以：**改了數字的那一次絕不會贏過沒改數字的那一次**；數字狀態相同時，**有亂碼的絕不會贏過沒有亂碼的**。兩條原則只有在「亂碼但數字沒壞」對「數字壞了但文字乾淨」時互相衝突，這裡數字優先（`garbled` 排在 `digits-changed` 前面，連 `garbled+partial` 也是）。同一個判定內依序比：judge 的 `coverage`（高者勝；沒有 judge 的算比任何有 judge 的低）、缺少的硬數字組數（少者勝）、殘留比例（低者勝），仍相同就留較早的那一次（較小的 run 編號）。

模型欄的徽章旁有可展開的「驗證細節」：判定、來源語言與偵測方式、數字（含「無法確認的數字」警告與每組數字的信心）、缺少的數字（照來源圖上的寫法）、殘留比例（含門檻與分子分母）、殘留的字與「大字沒翻」、judge 的 coverage／摘要／亂碼／沒翻／翻錯／數字被改清單與可收合的「judge 的參考翻譯」、驗證耗時；勾「顯示驗證細節」會預設展開，並多附上輸出圖的 OCR 全文（沒勾就不會把 OCR 全文留在結果裡）。每次驗證的 JSON（含 judge 的完整紀錄）也存成檔案（`B<n>_<模型>_r<k>_a<j>_verify.json`，也會上傳）。

**一次自動重試**

- 只有「第一次的判定不是 `pass`、而且驗證有跑成功」才重試，最多一次：同一個模型、同一張原圖，prompt 加上上面的第二段，指名漏的（硬）數字、最多 10 個還沒翻掉的字（大字優先），以及 judge 找到的亂碼、沒翻的段落與被改的數字。重試失敗（例如 API 錯誤）時沿用第一次的結果並顯示原因。
- **兩次的圖與判定都保留**。主要顯示的是「比較好的那一次」，另一次收在折疊面板裡。選擇規則就是上面的優先順序與同判定內的比較順序。
- 費用與耗時是**兩次加總**（欄位上另外列出各次）。每個 run 各自重試，不影響其他 run。

## 怎麼讀結果

每個欄位上方有耗時；Mode A 另外拆成各階段。

**Mode B 欄（每個模型的每一次 run 各一欄）**
- 標題：模型 id（連跑多次時加 `· run k`）、狀態、驗證徽章。一個 run 失敗（錯誤顯示在該欄）不影響其他 run 與其他模型。
- `耗時（含驗證與重試）`、`費用（生成）`、`費用（judge）`、`費用（合計）`、`tokens`：兩次嘗試的加總（有重試時括號內列出各次）；生成費用是 OpenRouter 回應的 `usage.cost`，回應沒有帶才顯示「回應沒有帶 cost」；judge 費用依 Anthropic 價目表估算。耗時是這個 run 自己做事的時間，不含排隊等生成名額的時間。
- `API 路徑`：實際走 `chat` 還是 `images`（`(no aspect ratio)`、`(no resolution)` 表示拿掉了那個參數才成功），括號內是該模型的優先路徑。
- `輸出尺寸（長寬比差 x%）`、`尺寸選項`：見上。
- 驗證面板：見「驗證」。
- 「嘗試 1／2」折疊面板：沒被採用的那一次（圖、API 路徑、尺寸、驗證細節；重試那次可看實際送出的 prompt）。
- 「模型附帶文字」是模型除了圖以外說的話（拒絕時通常在這裡）。

**各模型彙總（每個模型連跑 2 次以上時，出現在欄位上方）**：每個模型一列。

| 欄 | 意思 |
| --- | --- |
| 通過率 | 判定為 `pass` 的 run 數 ÷ 已結束的 run 數；失敗的 run 算沒過（`2 / 3（67%）`） |
| 各次判定 | 每個 run 的徽章（失敗的顯示「失敗」） |
| 平均總費用 | 每個 run 的（生成 + 重試 + judge）費用的平均，只算完成的 run |
| 平均總時間 | 每個 run 的總時間（含重試、驗證、judge）的平均，只算完成的 run |
| 最佳 run | 依上面的優先順序挑出的那一次（判定 → coverage → 缺少的數字 → 殘留比例 → 較早的 run） |

怎麼讀通過率：3 次裡 3 次 `pass` 才算穩定；2 / 3 表示大約每三張圖有一張要人工看。樣本只有 1–3 次，所以 3 / 3 不是保證，1 / 3 與 2 / 3 之間也很難斷定誰比較好——要比較兩個模型，請用幾張**不同的圖**、每張都跑 3 次，比較總和。通過率只回答「驗證有沒有通過」，不回答「好不好看」，最佳 run 的圖還是要自己看。

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

**比較時建議看**：每個模型的通過率與各次判定、驗證徽章（有沒有改數字、有沒有漏翻、有沒有亂碼，以及 judge 的完成度）、版面與字體是否保留、顏色、直排／旋轉頁面的處理、輸出尺寸、費用與耗時，以及模型有沒有改動不該動的圖形或文字。

## 給審閱者的連結（MinIO）

job 全部跑完之後（不論有沒有錯誤），背景執行緒會把下面這些上傳到 `<MINIO_BUCKET_NAME>/<MINIO_PREFIX>/<job-id>/`（key 是 `<prefix>/<job-id>/<name>`）：

| name | 內容 |
| --- | --- |
| `original.<ext>` | 送出時的原圖 |
| `A.png`、`A_units.json` | Mode A 的結果圖與 units（有勾 Mode A 才有） |
| `B<n>_<模型>_r<k>_a1.png`、`..._a2.png` | Mode B 第 n 個模型第 k 次 run 的第 1、第 2 次嘗試的圖 |
| `B<n>_<模型>_r<k>_a<j>_verify.json` | 該次嘗試的驗證結果（含 judge 的完整紀錄與參考翻譯） |
| `manifest.json` | 這次執行的 metadata 與**每個物件的簽名 GET 網址** |

`manifest.json`（`schema_version: 2`）有：`job_id`、各時間、`target_language`、`options`（模型清單、輸出尺寸、是否驗證、`runs`、`judge`、`judge_model`…）、`original`，`mode_a`（耗時、tokens、費用、圖與 units 的連結），`mode_b[]`（**每個模型一筆**），`objects[]`（`name`、`key`、`bytes`、`content_type`、`url`），`warnings`，以及 manifest 自己的 `manifest.url`。單一物件上傳失敗不會讓 job 失敗：那個物件在 manifest 裡只有 `error`、沒有 `url`，並出現在頁面的「提醒」裡。

`mode_b[]` 的每一筆：`model`、`route_preference`、`resolution_requested`、`runs_requested`，
- `summary`：`runs_done`、`runs_failed`、`passes`、`pass_rate`、`verdicts[]`、`mean_total_cost_usd`、`total_cost_usd`、`mean_elapsed_ms`、`best_run`、`best_verdict`、`best_image`（最佳 run 的圖與連結）；
- `runs[]`（**所有** run，每次一筆）：`run`、`status`、`error`、`api_used`、`verdict`、`coverage`、`selected_attempt`、`elapsed_ms`、`judge_ms`、`usage`、`cost_usd`（生成）、`judge_cost_usd`、`total_cost_usd`、採用的圖 `image`，以及 `attempts[]`：每次嘗試的圖、驗證 JSON、`verdict`、`missing_digit_runs`、`unverified_digit_runs`、殘留比例與字、`big_leftover_words`、`coverage`、`judge`（`status`、`error`、`model`、`coverage`、亂碼／沒翻／翻錯／數字被改的清單、`summary`、tokens、`cost_usd`）、路徑、尺寸、耗時、tokens、費用、送出的 prompt。

schema 1（第 3 輪）是每個模型一筆、`attempts[]` 直接在模型底下；schema 2 在中間多了 `runs[]`，並把彙總放在 `summary`。

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
| `output_size` | `2K` | `default`（不送 `resolution`，用供應商自己的尺寸）、`1K`、`2K`、`4K`，只套用在 images 路徑。沒填，或填空字串，都是 `2K`（表單的空值等於沒填）；舊版的空字串要改成 `default` |
| `verify` | `1` | `0` = 不驗證、不重試（頁面沒有這個開關，驗證一律開著） |
| `verify_details` | `0` | `1` = 在結果裡附上輸出圖的 OCR 全文（頁面的「顯示驗證細節」） |
| `runs` | `1` | 每個模型連跑幾次，1–3；超出範圍回 400。每次是獨立的一輪（各自的重試、驗證、judge），同時最多 4 個生成在跑 |
| `judge` | `1` | `0` = 不跑語意 judge（`verify=0` 時也不跑）。頁面的「語意審查」勾選框 |

回傳 `{"job_id": ...}`；用 `GET /api/jobs/<job-id>` 輪詢（`status: done` 之後 `publish.status` 還會從 `pending` → `running` → `done` / `skipped` / `error`），`mode_b.results[]` 是每個模型**每次 run** 的結果（扁平、依模型再依 run 排序：`model`、`model_index`、`run`、`runs`…），`mode_b.summary[]` 是每個模型的彙總（通過率等，見上）。圖在 `GET /files/<job-id>/<name>`。

## 費用備註

- 每個 Mode B run = 1 次影像生成（驗證不過會再多 1 次）。費用取決於模型與尺寸：頁面每欄顯示 OpenRouter 回應的 `usage.cost`（有重試時是兩次加總，並列出各次），回應沒有帶 cost 的模型只顯示 tokens。OpenRouter 文件提到 images 路徑的定價可能依解析度分級（例如 2K、4K），所以選大尺寸通常更貴。**費用 = 模型數 × 每個模型連跑的次數 × 每個 run 的 1–2 次嘗試**：4 個模型各跑 3 次最壞是 24 次生成。
- 驗證 = 每個 job 1 次來源圖 Vision（共用）+ 每張輸出圖（每次嘗試）1 次 Vision（依 Google 當時的計價）。
- **judge = 每次嘗試 1 次 Claude 呼叫**（預設 `claude-sonnet-5-5`，2 / 10 USD 每百萬 tokens；`effort: medium`，只送 OCR 文字）。每次嘗試粗估幾美分以內（0.01–0.06 USD，**估計不是實測**），所以一個 run 最多 2 次 judge。不想付這筆錢就把頁面的「語意審查」取消勾選（或 API `judge=0`），代價是抓不到亂碼。
- Mode A = 1 次 Vision `document_text_detection`（與 Mode B 的來源圖 OCR 共用，不會重複打）+ 1 次 Claude（圖 + 字清單，輸出 JSON）。Claude 用 `effort: high`，思考 tokens 計入輸出，所以字多的圖可能比想像貴；實際數字以頁面顯示的 tokens 與估計費用為準（估計只依上面的價目表，沒算快取與長上下文加價）。
- 每次按「開始」，選了的模型、次數與 Mode 都會各打一次付費 API。沒有快取；自動重試最多每個 run 一次。
- **並行與 429**：不管選了幾個模型、連跑幾次，同時進行中的圖片生成最多 4 個（跨 job 共用），其餘排隊；驗證與 judge 不受這個限制。OpenRouter 回 429 時等 20 秒再試同一個請求一次，再 429 就算這個 run 失敗（不會被改走另一條 API 路徑掩蓋）。

## 這個工具「不是」什麼

- **不是正式版**：沒有 LINE、沒有 MongoDB、沒有佇列與限流、沒有使用者隔離（MinIO 只用來交付結果）。圖片與結果會留在 `./data/`，自己清。
- **inpaint 是 OpenCV Telea，不是生成式模型**：平坦背景幾乎看不出來（顏色可能有 ±幾階的差），但漸層、照片、複雜紋理上的大面積文字會糊、會留下痕跡。
- Mode A 的譯文只用一種顏色、一種字型家族（Noto Sans CJK / DejaVu Sans）畫，不會還原原本的字型；字體粗細只靠角色與筆畫粗細估計。
- 驗證是靠 OCR 比對加一個 LLM judge，不是人眼：OCR 讀錯（手寫字、很小的字）會造成誤判，judge 讀的是 OCR 文字而不是圖，也可能誤報或漏報，判定是提示不是定論；短數字（2–3 位）的檢查較寬鬆；低信心的手寫數字被改掉只會是警告；中日韓的單字元詞不計入殘留比例。通過率只有 1–3 次的樣本。
- 整張圖會被送到 Anthropic（Mode A）、OpenRouter / 其後的模型供應商（Mode B）與 Google Vision，也會（設了 MinIO 時）上傳到你的 MinIO，含圖上的所有個資，請自行評估。Mode B 的 judge 只把來源與輸出圖的 OCR **文字**送給 Anthropic，不送圖。
- 字清單太大（估計超過約 12k tokens）只會提醒，不會丟字；超過 150k tokens 會直接拒絕。

## 疑難排解

- Mode A / 驗證：`Google Vision credentials are not usable`：確認 `GOOGLE_APPLICATION_CREDENTIALS` 指到容器內存在的檔案，或看上面「憑證怎麼給」。驗證的話徽章會是 `unverified`。
- Mode A：`Claude refused ...`：模型安全分類器拒絕，頁面會顯示類別；預設已開 server-side fallback。
- Mode B：`the model returned no image (it said: ...)`：該模型沒有輸出圖片，換一個模型，或把 `OPENROUTER_IMAGE_API` 設成 `images` / `chat` 試另一條路。
- Mode B 的 judge 顯示 `unavailable`：頁面會寫原因——沒設 `ANTHROPIC_API_KEY`、Claude API 錯誤、被拒絕、回傳的 JSON 不合 schema。這不影響生成與其他檢查，只是這次抓不到亂碼；補好金鑰後重跑。
- Mode B 被判 `garbled` 但圖看起來沒問題：看亂碼清單與「judge 的參考翻譯」。OCR 把裝飾圖案或很小的字讀成字、或 `coverage` 因為 judge 比較嚴格而低於 0.90，都會這樣；以眼睛為準，必要時把 `CLAUDE_JUDGE_MODEL` 換成別的型號比較。
- Mode B 出現 `HTTP 429`：OpenRouter 的速率限制。工具已經等 20 秒重試過一次還是 429，才會顯示；減少模型數或連跑次數，或稍後再試。
- 「給審閱者的連結」顯示缺變數：補齊 `.env` 後重啟容器（`docker compose up -d`）。顯示上傳失敗：看頁面的「提醒」（例如 bucket 不存在、金鑰錯誤；金鑰本身不會出現在訊息裡）。
- 連結審閱者打不開：確認用的是 `MINIO_PUBLIC_ENDPOINT` 簽的（警告區有提醒的話表示簽成了內部位址），且沒有過期。
- 下拉選單只有預設模型：OpenRouter 模型清單抓取失敗（狀態列會顯示原因），不影響執行。
- 測試：`docker compose run --rm lab pytest -q`（容器內也有測試；README 與 prompt 一致性的那個測試會因為映像沒有 README 而略過）。
