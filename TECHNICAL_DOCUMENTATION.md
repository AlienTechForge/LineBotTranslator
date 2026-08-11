# LINE Bot 翻譯機器人技術文檔

本文檔詳細記錄 LINE Bot 翻譯機器人的技術架構、實現細節和資料庫設計。

## 系統架構

### 整體架構

本專案採用典型的 Spring Boot MVC 架構，主要分為以下幾層：

1. **控制器層（Controller）**：處理來自 LINE 平台的請求
2. **服務層（Service）**：實現業務邏輯，包括翻譯、OCR、用戶管理等
3. **資料訪問層（Repository）**：與 MongoDB 資料庫交互
4. **模型層（Model）**：定義資料模型
5. **配置層（Config）**：系統配置和外部服務整合

### 主要組件

#### 控制器層

- **LineBotController**：處理 LINE 平台的消息事件，包括文字消息和圖片消息
  - 使用 `@LineMessageHandler` 和 `@EventMapping` 註解處理不同類型的 LINE 事件
  - 支持命令處理（如 `/help`、`/models`、`/model` 等）
  - 支持管理員特殊命令

#### 服務層

- **TranslationService**：核心翻譯服務
  - 處理文字翻譯請求
  - 支持多種翻譯格式解析
  - 自動語言檢測和目標語言選擇
  - 翻譯記錄管理

- **ImageTranslationService**：圖片翻譯服務
  - 處理圖片 OCR 和翻譯
  - 整合 Google Cloud Vision API 或 AI 模型進行文字識別

- **LanguageDetectionService**：語言檢測服務
  - 使用 language-detector 庫檢測文字語言

- **AiProviderAdapter**：AI 執行邊界 Interface
  - `OpenRouterService` 是唯一 Implementation，負責 Chat Completions wire contract
  - `AiProviderExecutionModule` 統一模型驗證、失敗正規化與用量記錄

- **AiModelCatalog**：模型探索 Seam
  - `OpenRouterModelCatalog` 從 `/api/v1/models` 取得 capability 與 pricing
  - 以 TTL cache、stale fallback 與 bounded search 保持對話回覆穩定

- **LineUserProfileService**：LINE 用戶資料服務
  - 獲取和管理 LINE 用戶資料

- **AdminService**：管理員服務
  - 提供系統統計資訊
  - 管理員權限控制

#### 資料訪問層

- **UserProfileRepository**：用戶資料存取
- **TranslationRecordRepository**：翻譯記錄存取
- **TranslationActionClaimRepository**：翻譯結果 action 的 durable idempotency claim

#### 配置層

- **MongoConfig**：MongoDB 資料庫配置
- **AppConfig**：應用程式全局配置
- **OpenRouterConfig**：OpenRouter API、預設模型與 catalog TTL 配置
- **GoogleVisionConfig**：Google Cloud Vision API 配置

## 資料庫設計

### MongoDB 連接配置

專案使用 Spring Data MongoDB 進行資料庫連接，配置在 `MongoConfig.java` 中：

```java
@Configuration
@EnableMongoRepositories(basePackages = "com.linetranslate.bot.repository")
public class MongoConfig extends AbstractMongoClientConfiguration {

    @Value("${mongodb.uri:${MONGODB_URI:mongodb://localhost:27017/linebot_translator}}")
    private String mongoUri;

    @Value("${mongodb.database:${MONGODB_DATABASE:linebot_translator}}")
    private String mongoDatabaseName;

    @Override
    protected String getDatabaseName() {
        return mongoDatabaseName;
    }

    @Override
    public MongoClient mongoClient() {
        ConnectionString connectionString = new ConnectionString(mongoUri);
        MongoClientSettings mongoClientSettings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .build();

        return MongoClients.create(mongoClientSettings);
    }
}
```

連接字串和資料庫名稱可通過環境變數或配置檔案設定：
- 預設連接字串：`mongodb://localhost:27017/linebot_translator`
- 預設資料庫名稱：`linebot_translator`

以下列出使用者與翻譯互動相關的主要集合（其他 runtime、usage 與 webhook 集合見 `CONTEXT.md`）：

使用者資料（user_profiles）：
{
  "_id": "自動生成的MongoDB ID",
  "userId": "LINE用戶ID",
  "displayName": "用戶顯示名稱",
  "pictureUrl": "用戶頭像URL",
  "statusMessage": "用戶狀態訊息",
  "preferredLanguage": "偏好的翻譯語言",
  "preferredModel": "偏好的 OpenRouter 模型 slug",
  "preferredChineseTargetLanguage": "中文原文的偏好目標語言",
  "recentTranslations": ["最近的翻譯1", "最近的翻譯2", ...],
  "recentLanguages": ["最近使用的語言1", "最近使用的語言2", ...],
  "firstInteractionAt": "首次互動時間",
  "lastInteractionAt": "最後互動時間",
  "totalTranslations": 翻譯總次數,
  "textTranslations": 文字翻譯次數,
  "imageTranslations": 圖片翻譯次數
}

翻譯記錄（translation_records）：
{
  "_id": "自動生成的MongoDB ID",
  "userId": "LINE用戶ID",
  "sourceText": "原始文字",
  "sourceLanguage": "原始語言",
  "targetLanguage": "目標語言",
  "translatedText": "翻譯後文字",
  "aiProvider": "使用的AI提供者",
  "modelName": "使用的模型名稱",
  "createdAt": "創建時間",
  "processingTimeMs": 處理時間(毫秒),
  "isImageTranslation": 是否為圖片翻譯,
  "imageUrl": "圖片URL(如果是圖片翻譯)",
  "timestamp": "時間戳"
}

翻譯 action claim（translation_action_claims）：
{
  "_id": "userId + sourceRecordId + targetLanguage 的 SHA-256",
  "userId": "LINE 用戶 ID",
  "sourceRecordId": "來源 Translation Record ID",
  "targetLanguage": "目標語言",
  "status": "PROCESSING | COMPLETED | FAILED",
  "resultRecordId": "完成後的 Translation Record ID",
  "createdAt": "建立時間",
  "updatedAt": "更新時間"
}

此集合不保存原文、譯文、LINE payload、postback data 或 secrets；Mongo `_id` 唯一性在 provider 呼叫前阻止同一 action 重複執行。

### 資料模型

#### 用戶資料模型 (UserProfile)

```java
@Document("user_profiles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfile {

    @Id
    private String id;

    private String userId;                    // LINE 用戶 ID
    private String displayName;               // 顯示名稱
    private String pictureUrl;                // 頭像 URL
    private String statusMessage;             // 狀態訊息
    private String preferredLanguage;         // 偏好的翻譯語言
    private String preferredModel;            // 偏好的 OpenRouter 模型 slug
    private String preferredChineseTargetLanguage; // 中文原文的偏好目標語言

    @Builder.Default
    private List<String> recentTranslations = new ArrayList<>();  // 最近的翻譯

    @Builder.Default
    private Set<String> recentLanguages = new LinkedHashSet<>();  // 最近使用的語言

    @Builder.Default
    private LocalDateTime firstInteractionAt = LocalDateTime.now();  // 首次互動時間

    private LocalDateTime lastInteractionAt;  // 最後互動時間

    @Builder.Default
    private int totalTranslations = 0;        // 翻譯總次數

    @Builder.Default
    private int textTranslations = 0;         // 文字翻譯次數

    @Builder.Default
    private int imageTranslations = 0;        // 圖片翻譯次數

    // 方法：添加最近使用的語言
    public void addRecentLanguage(String languageCode) {
        // 實現邏輯
    }

    // 方法：獲取最近使用的語言列表
    public List<String> getRecentLanguagesList() {
        return new ArrayList<>(recentLanguages);
    }
}
```

#### 翻譯記錄模型 (TranslationRecord)

```java
@Document("translation_records")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranslationRecord {

    @Id
    private String id;

    private String userId;                // LINE 用戶 ID
    private String sourceText;            // 原始文字
    private String sourceLanguage;        // 原始語言
    private String targetLanguage;        // 目標語言
    private String translatedText;        // 翻譯後文字
    private String aiProvider;            // openrouter（歷史資料可能保留舊維度）
    private String modelName;             // 使用的模型名稱
    private LocalDateTime createdAt;      // 創建時間
    private double processingTimeMs;      // 處理時間 (毫秒)
    private boolean isImageTranslation;   // 是否為圖片翻譯
    private String imageUrl;              // 圖片 URL (如果是圖片翻譯)

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();  // 時間戳
}
```

### 資料庫查詢

#### 用戶資料查詢

```java
@Repository
public interface UserProfileRepository extends MongoRepository<UserProfile, String> {

    Optional<UserProfile> findByUserId(String userId);

    boolean existsByUserId(String userId);

    List<UserProfile> findTop10ByOrderByTotalTranslationsDesc();

    List<UserProfile> findByLastInteractionAtAfter(LocalDateTime dateTime);

    long countByLastInteractionAtBetween(LocalDateTime start, LocalDateTime end);
}
```

#### 翻譯記錄查詢

```java
@Repository
public interface TranslationRecordRepository extends MongoRepository<TranslationRecord, String> {

    List<TranslationRecord> findByUserId(String userId);

    List<TranslationRecord> findByUserIdOrderByTimestampDesc(String userId);

    List<TranslationRecord> findByUserIdAndTimestampBetween(String userId, LocalDateTime start, LocalDateTime end);

    List<TranslationRecord> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    List<TranslationRecord> findBySourceTextAndSourceLanguageAndTargetLanguage(
            String sourceText, String sourceLanguage, String targetLanguage);

    long countByAiProvider(String aiProvider);

    long countByIsImageTranslation(boolean isImageTranslation);

    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
}
```

## 核心功能實現

### 翻譯流程

1. **文字翻譯流程**：
   - 用戶發送文字訊息
   - `LineBotController` 接收訊息並調用 `TranslationService`
   - `TranslationService` 解析翻譯請求格式
   - 使用 `LanguageDetectionService` 檢測源語言
   - 根據用戶偏好或默認規則選擇目標語言
   - `UserPreferencesModule` resolve 目標語言、OpenRouter model 與預設翻譯風格
   - 單次 `/translate-style <id> <text>` 可覆蓋本次風格，不修改預設值
   - `CachedTranslationAdapter` 以實際 style ID/prompt version 查 bounded、model-aware cache
   - `AiProviderExecutionModule` 驗證 model capability 並呼叫唯一 OpenRouter Adapter
   - 成功後保存實際 provider/model metadata，並更新用戶資料

2. **圖片翻譯流程**：
   - 用戶發送圖片
   - `LineBotController` 接收圖片並調用 `ImageTranslationService`
   - 使用 LINE Blob Client 獲取圖片內容
   - 使用 Google Cloud Vision API 或 AI 模型進行 OCR
   - 檢測識別出的文字語言
   - 翻譯文字並返回結果
   - 保存翻譯記錄並更新用戶資料

### AI 服務整合

1. **AiProviderAdapter Interface**：
   ```java
   public interface AiProviderAdapter {
       String providerName();
       String defaultModel();
       Set<String> availableModels();
       Set<AiProviderOperation> capabilities();
       AiProviderResponse execute(AiProviderRequest request);
   }
   ```

2. **OpenRouter Implementation**：
   - `OpenRouterService` 是唯一 Adapter，呼叫 `/api/v1/chat/completions`
   - 文字翻譯、AI language detection 與 image-capable model 共用 typed request/outcome
   - Authorization 使用 `OPEN_ROUTE_API_KEY`，provider response body 與 key 不進 logs

3. **Model Catalog Seam**：
   - `OpenRouterModelCatalog` 從 `/api/v1/models` 探索 text-output models
   - catalog 保存 capability/pricing snapshot，採 TTL cache 與 stale/default fallback
   - LINE `/models [關鍵字]` 搜尋，`/model <slug>` 保存個人選擇

4. **Translation Style Presets**：
   - 穩定 ID：`faithful`、`natural`、`casual`、`formal`、`business`、`subtitle`
   - 每個 preset 具有中英文名稱、版本化 prompt rule；只由 server allowlist 建立 system instruction
   - `/styles` 列出風格，`/style <id>` 保存個人預設，`/translate-style <id> <text>` 僅套用一次
   - Translation Record、cache identity 與安全 restyle action 都保存／使用實際 preset ID 與 prompt version

### OCR 實現

1. **Google Cloud Vision API**：
   - 主要 OCR 實現方式
   - 高精度文字識別

2. **AI 模型備用方案**：
   - 當 Google Cloud Vision API 不可用時
   - 使用已選擇且支援 image input 的 OpenRouter model 進行辨識

## 系統性能與優化

### 緩存策略

`CachedTranslationAdapter` 使用 Caffeine bounded cache。Identity 包含 source digest、target、planned provider/model、實際 Translation Style Preset ID、該 preset 的 prompt version 與 glossary version；只保存 model identity 一致的成功結果，failure 與 safety blocked 不寫入。TTL 與 maximum entries 由部署 variables 控制。

### 異步處理

`WebhookIngestionModule` 使用 bounded `Executor` 非同步處理已 claim 的 LINE event。Queue 滿時釋放 claim 並回 `503`，由 LINE redelivery；reply retry 不重複執行 business operation。

## 安全性考慮

1. **API 密鑰管理**：
   - 使用環境變數存儲敏感信息
   - 避免硬編碼 API 密鑰

2. **用戶權限控制**：
   - 管理員功能限制
   - 用戶資料保護

## 擴展性設計

1. **模塊化架構**：
   - 各功能模塊解耦
   - 介面定義清晰

2. **動態 AI model catalog**：
   - 通過 `AiModelCatalog` 探索與驗證 OpenRouter models
   - 新 model 不需新增 provider-specific connector

3. **配置驅動**：
   - 大部分功能可通過配置啟用/禁用
   - 便於根據需求調整系統行為
