# LINE Bot 翻譯機器人

這是一個基於 Spring Boot 開發的 LINE Bot 翻譯機器人，使用 OpenAI 和 Google Gemini 進行文字翻譯，以及 Google Cloud Vision API 進行圖片文字識別與翻譯。

## 功能特點

- **自動語言檢測**：自動識別輸入文字的語言，並選擇適當的目標語言進行翻譯
- **多語言翻譯**：支持多種語言之間的翻譯，包括中文、英文、日文、韓文等
- **圖片文字識別**：使用 OCR 技術識別圖片中的文字並翻譯
- **用戶偏好設定**：允許用戶設置偏好的 AI 引擎和默認翻譯語言
- **翻譯記憶**：記住用戶最近使用的語言，提供快速翻譯選項
- **管理員統計**：為管理員提供系統使用統計信息

## 技術架構

- **後端框架**：Spring Boot 4.1（Java 17+）
- **數據庫**：MongoDB
- **翻譯引擎**：OpenAI GPT-4o、Google Gemini
- **OCR 技術**：Google Cloud Vision API
- **消息平台**：LINE Messaging API（LINE Bot SDK for Java 10.1）

## 環境配置

在運行本項目前，請確保您已經設置了以下環境變數：

```
# 伺服器配置
SERVER_PORT=4040

# LINE Bot 配置
LINE_BOT_CHANNEL_TOKEN=your_line_bot_channel_token
LINE_BOT_CHANNEL_SECRET=your_line_bot_channel_secret

# MongoDB 配置
MONGODB_URI=mongodb://localhost:27017/linebot_translator
MONGODB_DATABASE=linebot_translator

# OpenAI 配置
OPENAI_API_KEY=your_openai_api_key
OPENAI_MODEL_NAME=gpt-4o

# Gemini 配置
GEMINI_API_KEY=your_gemini_api_key
GEMINI_MODEL_NAME=gemini-1.5-pro

# Google Cloud Vision API 配置 (OCR)
GOOGLE_CLOUD_VISION_API_KEY=your_google_cloud_vision_api_key

# 應用程式配置
OCR_ENABLED=true
AI_DEFAULT_PROVIDER=openai

# 管理員配置
ADMIN_USERS=U123456789abcdef,U987654321abcdef
```

## 建立與運行

使用 Maven 建立專案：

```bash
mvn clean package
```

運行應用程式：

```bash
java -jar target/linebot-translator-0.0.1-SNAPSHOT.jar
```

或使用 Spring Boot Maven 插件：

```bash
./mvnw spring-boot:run
```

## 部署

本應用程式可以部署在任何支持 Java 運行環境的伺服器上。建議使用以下方式部署：

1. **Docker 容器**：使用 Dockerfile 建立容器映像
2. **雲端平台**：如 AWS、Google Cloud、Heroku 等
3. **專用伺服器**：在 VPS 或實體伺服器上運行

維運日誌必須遵守[日誌資料安全政策](docs/logging-data-policy.md)，不得記錄 credentials、使用者原文、OCR 結果、signed URL 或第三方 API payload。

## 使用指南

### 文本翻譯

- **自動翻譯**：直接發送文字，機器人會自動檢測語言並翻譯
    - 中文 → 英文
    - 其他語言 → 中文

- **指定翻譯**：使用特定格式指定翻譯語言
    - 格式 1：`翻譯成[語言] [文字]`，例如：`翻譯成日文 你好`
    - 格式 2：`翻譯成[語言代碼] [文字]`，例如：`翻譯成ja 你好`
    - 格式 3：`快速翻譯:[語言代碼] [文字]`，例如：`快速翻譯:en 你好`

### 圖片翻譯

- 直接將包含文字的圖片發送給機器人，它會自動識別並翻譯圖中的文字

### 命令列表

- `/help` - 顯示幫助信息
- `/about` - 關於此機器人
- `/setai openai|gemini` - 設置偏好的 AI 引擎
- `/setlang [語言]` - 設置默認翻譯語言
- `/lang` - 顯示語言選擇菜單
- `/profile` - 查看用戶資料

### 管理員命令

- `/admin stats` - 顯示系統統計信息
- `/admin today` - 顯示今日統計信息

## 貢獻與支持

歡迎提出問題和建議，或者提交 Pull Request 來改進這個項目。
