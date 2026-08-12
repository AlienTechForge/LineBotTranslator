# ADR-0009: Use an explicit stateless image translation pipeline

- Date: 2026-08-11

## Status

Accepted

## Context

圖片下載 bytes、storage URL、OCR text 與 translation state 若存在 singleton/ThreadLocal，concurrent LINE events 可能互相污染。MinIO 或 Google Vision failure 也不應被模糊成單一 generic exception。

## Decision

`ImageTranslationPipeline` 使用 immutable/explicit request、downloaded image、storage result、context、failure stage 與 outcome。Pipeline 不在 thread 或 singleton field 保存 request state。MinIO storage 是 non-blocking side effect；configured OCR 不可用時可 fallback 到 AI image recognition；辨識成功後進入 shared Translation Workflow。

Located OCR 必須保留 stable Region ID、provider vertex order、word/symbol polygons、known confidence、block type 與 detected languages。OCR 後處理先把 word polygon 映射到 paragraph 的本地閱讀座標，因此水平、垂直與斜向拍攝皆使用同一套分列／分欄規則；具有一致短字、明顯間距與可靠 word geometry 的離散 UI label 可拆成 child Region。對同一 provider paragraph 內含多個視覺列、且至少一列有明顯欄間距的表格／菜單，依本地座標的字框重疊分列、依相對字高的間距分欄；單列含明確價格欄位的菜單亦可拆分。一般自動換行 prose 不拆。所有 child 保留 group identity、取得唯一 reading order，並以相鄰文字中點取得 bounded cell polygon，再映射回原圖方向。只有 centralized qualification policy 判定為 `TRANSLATE` 的 Region 可進入 versioned structured translation contract；`PRESERVE`／`REJECT` 不得清除原圖。Provider 回應以 exact Region ID set 驗證，禁止以換行或 list index 猜配。

Google Vision（configured OCR）負責 located geometry/language metadata；使用者選定的 OpenRouter Translation Model 只負責翻譯，不被當成 OCR geometry 或獨立 language detector 的來源。沒有 located Regions 的 AI recognition fallback 僅提供純文字翻譯。

Overlay 採 fail-closed。Centralized safety policy 在 renderer 前檢查 kill switch、confidence 與 geometry；單一 OCR 區域上限以原始 paragraph polygon 計算，整張實際修改上限則以 padded masks 計算。低彩度、單一主背景、至少三個高信心水平文字區的文件可進入 `DOCUMENT` coverage profile；它只提高總覆蓋與單區面積上限，不放寬 confidence、geometry、font 或 text-fit。Overlap 與 total coverage 採逐區保留，單一衝突不可把其他安全區域整批拒絕。每次拒絕以不含 OCR 原文的結構化 log 記錄 region ID、reason、ratio、threshold、mode 與圖片尺寸。原始 paragraph polygons 也負責判斷語意區域 overlap，避免相鄰文字行只因清邊 padding 接觸而整張誤判。來源樣式估算可使用 word/symbol geometry；清除原文優先使用每個 symbol polygon，只有 symbol geometry 不完整時才回退到該 word polygon，避免覆蓋表格格線、價格徽章或鄰近圖示。新譯文仍以完整 child/paragraph polygon 排版，不受稀疏 glyph masks 裁切，並保留 local rotation、選擇具完整 glyph coverage 的 Noto font。最低可讀字級仍無法完整容納時必須保留原文，不得用 ellipsis 產生不完整譯文。Java2D 邊界像素保護只掃描當次 overlay 周圍，不得對整張圖片與累積複雜遮罩逐像素運算；renderer 與 translated-image upload 需分別記錄耗時。OCR provider 不提供 font family metadata，因此字型名稱只能 best-effort 選擇，不能宣稱精確還原。任何 mapping、policy、font、text-fit、renderer 或 translated-image storage 問題均以 typed degradation reason 降級為成功的純文字翻譯；coverage、font、geometry 等原因不得顯示成低信心。

Thread interruption 必須保留，LINE/OCR streams 必須關閉。

## Consequences

### Positive

- Concurrent request data 保持隔離，failure stage 可測試與穩定呈現。
- Storage/OCR degraded 不必阻止可完成的翻譯。
- OCR false positive、旋轉 bounds 或 provider 格式漂移不再破壞原圖。

### Negative / trade-offs

- Pipeline 需要較多 typed value objects。
- 每個新增 stage 都要定義 failure policy 與 resource lifecycle。
- Fail-closed 可能犧牲部分 overlay；安全時仍保留完整純文字結果。

## Alternatives considered

- 使用 ThreadLocal 傳遞中間 state：拒絕，async/concurrent execution 容易洩漏或錯配。
- MinIO/OCR 任一失敗即終止：拒絕，optional capability 不應破壞核心流程。

## Related

- Issue #19
- PR #65
- Issues #80–#85
