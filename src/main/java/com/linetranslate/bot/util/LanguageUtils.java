package com.linetranslate.bot.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

/**
 * 語言工具類，用於語言代碼和名稱的轉換和映射
 */
public class LanguageUtils {

    // 語言名稱到語言代碼的映射
    private static final Map<String, String> LANGUAGE_NAME_TO_CODE = new HashMap<>();

    // 語言代碼到正式名稱的映射
    private static final Map<String, String> LANGUAGE_CODE_TO_NAME = new HashMap<>();

    static {
        // 初始化常見語言的映射
        addLanguageMapping("中文", "zh", "Chinese");
        addLanguageMapping("英文", "en", "English");
        addLanguageMapping("日文", "ja", "Japanese");
        addLanguageMapping("韓文", "ko", "Korean");
        addLanguageMapping("法文", "fr", "French");
        addLanguageMapping("德文", "de", "German");
        addLanguageMapping("西班牙文", "es", "Spanish");
        addLanguageMapping("葡萄牙文", "pt", "Portuguese");
        addLanguageMapping("義大利文", "it", "Italian");
        addLanguageMapping("俄文", "ru", "Russian");
        addLanguageMapping("阿拉伯文", "ar", "Arabic");
        addLanguageMapping("泰文", "th", "Thai");
        addLanguageMapping("越南文", "vi", "Vietnamese");
        addLanguageMapping("印尼文", "id", "Indonesian");
        addLanguageMapping("馬來文", "ms", "Malay");
        addLanguageMapping("繁體中文", "zh-TW", "Traditional Chinese");
        addLanguageMapping("簡體中文", "zh-CN", "Simplified Chinese");
        addLanguageMapping("荷蘭文", "nl", "Dutch");
        addLanguageMapping("希臘文", "el", "Greek");
        addLanguageMapping("波蘭文", "pl", "Polish");
        addLanguageMapping("土耳其文", "tr", "Turkish");
        addLanguageMapping("捷克文", "cs", "Czech");
        addLanguageMapping("瑞典文", "sv", "Swedish");
        addLanguageMapping("丹麥文", "da", "Danish");
        addLanguageMapping("芬蘭文", "fi", "Finnish");
        addLanguageMapping("印地文", "hi", "Hindi");

        // 添加別名
        LANGUAGE_NAME_TO_CODE.put("英語", "en");
        LANGUAGE_NAME_TO_CODE.put("日語", "ja");
        LANGUAGE_NAME_TO_CODE.put("韓語", "ko");
        LANGUAGE_NAME_TO_CODE.put("法語", "fr");
        LANGUAGE_NAME_TO_CODE.put("德語", "de");
        LANGUAGE_NAME_TO_CODE.put("西班牙語", "es");
        LANGUAGE_NAME_TO_CODE.put("葡萄牙語", "pt");
        LANGUAGE_NAME_TO_CODE.put("義大利語", "it");
        LANGUAGE_NAME_TO_CODE.put("俄語", "ru");
        LANGUAGE_NAME_TO_CODE.put("阿拉伯語", "ar");
        LANGUAGE_NAME_TO_CODE.put("泰語", "th");
        LANGUAGE_NAME_TO_CODE.put("越南語", "vi");
        LANGUAGE_NAME_TO_CODE.put("印尼語", "id");
        LANGUAGE_NAME_TO_CODE.put("馬來語", "ms");
        LANGUAGE_NAME_TO_CODE.put("荷蘭語", "nl");
        LANGUAGE_NAME_TO_CODE.put("希臘語", "el");
        LANGUAGE_NAME_TO_CODE.put("波蘭語", "pl");
        LANGUAGE_NAME_TO_CODE.put("土耳其語", "tr");
        LANGUAGE_NAME_TO_CODE.put("捷克語", "cs");
        LANGUAGE_NAME_TO_CODE.put("瑞典語", "sv");
        LANGUAGE_NAME_TO_CODE.put("丹麥語", "da");
        LANGUAGE_NAME_TO_CODE.put("芬蘭語", "fi");
        LANGUAGE_NAME_TO_CODE.put("印地語", "hi");
        LANGUAGE_NAME_TO_CODE.put("jp", "ja");
        LANGUAGE_NAME_TO_CODE.put("jap", "ja");
        LANGUAGE_NAME_TO_CODE.put("japan", "ja");
        LANGUAGE_NAME_TO_CODE.put("cn", "zh-CN");
        LANGUAGE_NAME_TO_CODE.put("tw", "zh-TW");
        LANGUAGE_NAME_TO_CODE.put("kr", "ko");

        // 添加 ISO 3166-1 國家代碼到語言代碼的映射
        // 亞洲
        LANGUAGE_NAME_TO_CODE.put("no", "nb");  // 挪威
        LANGUAGE_NAME_TO_CODE.put("nor", "nb"); // 挪威
        LANGUAGE_NAME_TO_CODE.put("se", "sv");  // 瑞典
        LANGUAGE_NAME_TO_CODE.put("swe", "sv"); // 瑞典
        LANGUAGE_NAME_TO_CODE.put("dk", "da");  // 丹麥
        LANGUAGE_NAME_TO_CODE.put("den", "da"); // 丹麥
        LANGUAGE_NAME_TO_CODE.put("fi", "fi");  // 芬蘭
        LANGUAGE_NAME_TO_CODE.put("fin", "fi"); // 芬蘭
        LANGUAGE_NAME_TO_CODE.put("is", "is");  // 冰島
        LANGUAGE_NAME_TO_CODE.put("ice", "is"); // 冰島
        LANGUAGE_NAME_TO_CODE.put("nl", "nl");  // 荷蘭
        LANGUAGE_NAME_TO_CODE.put("net", "nl"); // 荷蘭
        LANGUAGE_NAME_TO_CODE.put("be", "nl");  // 比利時（荷蘭語）
        LANGUAGE_NAME_TO_CODE.put("de", "de");  // 德國
        LANGUAGE_NAME_TO_CODE.put("ger", "de"); // 德國
        LANGUAGE_NAME_TO_CODE.put("at", "de");  // 奧地利（德語）
        LANGUAGE_NAME_TO_CODE.put("ch", "de");  // 瑞士（德語）
        LANGUAGE_NAME_TO_CODE.put("fr", "fr");  // 法國
        LANGUAGE_NAME_TO_CODE.put("fra", "fr"); // 法國
        LANGUAGE_NAME_TO_CODE.put("it", "it");  // 義大利
        LANGUAGE_NAME_TO_CODE.put("ita", "it"); // 義大利
        LANGUAGE_NAME_TO_CODE.put("es", "es");  // 西班牙
        LANGUAGE_NAME_TO_CODE.put("spa", "es"); // 西班牙
        LANGUAGE_NAME_TO_CODE.put("pt", "pt");  // 葡萄牙
        LANGUAGE_NAME_TO_CODE.put("por", "pt"); // 葡萄牙
        LANGUAGE_NAME_TO_CODE.put("br", "pt");  // 巴西（葡萄牙語）
        LANGUAGE_NAME_TO_CODE.put("gr", "el");  // 希臘
        LANGUAGE_NAME_TO_CODE.put("gre", "el"); // 希臘
        LANGUAGE_NAME_TO_CODE.put("pl", "pl");  // 波蘭
        LANGUAGE_NAME_TO_CODE.put("pol", "pl"); // 波蘭
        LANGUAGE_NAME_TO_CODE.put("cz", "cs");  // 捷克
        LANGUAGE_NAME_TO_CODE.put("cze", "cs"); // 捷克
        LANGUAGE_NAME_TO_CODE.put("hu", "hu");  // 匈牙利
        LANGUAGE_NAME_TO_CODE.put("hun", "hu"); // 匈牙利
        LANGUAGE_NAME_TO_CODE.put("ru", "ru");  // 俄羅斯
        LANGUAGE_NAME_TO_CODE.put("rus", "ru"); // 俄羅斯
        LANGUAGE_NAME_TO_CODE.put("ua", "uk");  // 烏克蘭
        LANGUAGE_NAME_TO_CODE.put("ukr", "uk"); // 烏克蘭
        LANGUAGE_NAME_TO_CODE.put("tr", "tr");  // 土耳其
        LANGUAGE_NAME_TO_CODE.put("tur", "tr"); // 土耳其
        LANGUAGE_NAME_TO_CODE.put("il", "he");  // 以色列（希伯來語）
        LANGUAGE_NAME_TO_CODE.put("he", "he");  // 希伯來語
        LANGUAGE_NAME_TO_CODE.put("heb", "he"); // 希伯來語
        LANGUAGE_NAME_TO_CODE.put("sa", "ar");  // 沙烏地阿拉伯（阿拉伯語）
        LANGUAGE_NAME_TO_CODE.put("ar", "ar");  // 阿拉伯語
        LANGUAGE_NAME_TO_CODE.put("ara", "ar"); // 阿拉伯語

        // 亞洲
        LANGUAGE_NAME_TO_CODE.put("cn", "zh-CN"); // 中國（簡體中文）
        LANGUAGE_NAME_TO_CODE.put("zh", "zh");    // 中文
        LANGUAGE_NAME_TO_CODE.put("chi", "zh");   // 中文
        LANGUAGE_NAME_TO_CODE.put("tw", "zh-TW"); // 台灣（繁體中文）
        LANGUAGE_NAME_TO_CODE.put("hk", "zh-TW"); // 香港（繁體中文）
        LANGUAGE_NAME_TO_CODE.put("mo", "zh-TW"); // 澳門（繁體中文）
        LANGUAGE_NAME_TO_CODE.put("sg", "zh-CN"); // 新加坡（簡體中文）
        LANGUAGE_NAME_TO_CODE.put("jp", "ja");    // 日本
        LANGUAGE_NAME_TO_CODE.put("ja", "ja");    // 日文
        LANGUAGE_NAME_TO_CODE.put("jap", "ja");   // 日文
        LANGUAGE_NAME_TO_CODE.put("kr", "ko");    // 韓國
        LANGUAGE_NAME_TO_CODE.put("ko", "ko");    // 韓文
        LANGUAGE_NAME_TO_CODE.put("kor", "ko");   // 韓文
        LANGUAGE_NAME_TO_CODE.put("th", "th");    // 泰國
        LANGUAGE_NAME_TO_CODE.put("tha", "th");   // 泰文
        LANGUAGE_NAME_TO_CODE.put("vn", "vi");    // 越南
        LANGUAGE_NAME_TO_CODE.put("vi", "vi");    // 越南文
        LANGUAGE_NAME_TO_CODE.put("vie", "vi");   // 越南文
        LANGUAGE_NAME_TO_CODE.put("id", "id");    // 印尼
        LANGUAGE_NAME_TO_CODE.put("ind", "id");   // 印尼文
        LANGUAGE_NAME_TO_CODE.put("my", "ms");    // 馬來西亞
        LANGUAGE_NAME_TO_CODE.put("ms", "ms");    // 馬來文
        LANGUAGE_NAME_TO_CODE.put("may", "ms");   // 馬來文
        LANGUAGE_NAME_TO_CODE.put("ph", "tl");    // 菲律賓（塔加拉族語）
        LANGUAGE_NAME_TO_CODE.put("tl", "tl");    // 塔加拉族語
        LANGUAGE_NAME_TO_CODE.put("in", "hi");    // 印度（印地文）
        LANGUAGE_NAME_TO_CODE.put("hi", "hi");    // 印地文
        LANGUAGE_NAME_TO_CODE.put("hin", "hi");   // 印地文

        // 北美洲
        LANGUAGE_NAME_TO_CODE.put("us", "en");    // 美國（英文）
        LANGUAGE_NAME_TO_CODE.put("usa", "en");   // 美國（英文）
        LANGUAGE_NAME_TO_CODE.put("en", "en");    // 英文
        LANGUAGE_NAME_TO_CODE.put("eng", "en");   // 英文
        LANGUAGE_NAME_TO_CODE.put("ca", "en");    // 加拿大（英文）
        LANGUAGE_NAME_TO_CODE.put("can", "en");   // 加拿大（英文）
        LANGUAGE_NAME_TO_CODE.put("mx", "es");    // 墨西哥（西班牙文）

        // 南美洲
        LANGUAGE_NAME_TO_CODE.put("br", "pt");    // 巴西（葡萄牙文）
        LANGUAGE_NAME_TO_CODE.put("bra", "pt");   // 巴西（葡萄牙文）
        LANGUAGE_NAME_TO_CODE.put("ar", "es");    // 阿根廷（西班牙文）
        LANGUAGE_NAME_TO_CODE.put("arg", "es");   // 阿根廷（西班牙文）

        // 大洋洲
        LANGUAGE_NAME_TO_CODE.put("au", "en");    // 澳大利亞（英文）
        LANGUAGE_NAME_TO_CODE.put("aus", "en");   // 澳大利亞（英文）
        LANGUAGE_NAME_TO_CODE.put("nz", "en");    // 紐西蘭（英文）
    }

    /**
     * 添加語言映射關係
     *
     * @param chineseName 中文名稱
     * @param code 語言代碼
     * @param englishName 英文名稱
     */
    private static void addLanguageMapping(String chineseName, String code, String englishName) {
        LANGUAGE_NAME_TO_CODE.put(chineseName, code);
        LANGUAGE_CODE_TO_NAME.put(code, chineseName);
    }

    /**
     * 將語言名稱或代碼轉換為標準語言代碼
     *
     * @param languageNameOrCode 語言名稱或代碼
     * @return 標準語言代碼，如果無法識別則返回原始輸入
     */
    public static String toLanguageCode(String languageNameOrCode) {
        if (languageNameOrCode == null || languageNameOrCode.trim().isEmpty()) {
            return "unknown";
        }

        String normalizedInput = languageNameOrCode.trim().toLowerCase(Locale.ROOT);

        // 如果輸入的是標準語言代碼
        String canonicalCode = canonicalCode(normalizedInput);
        if (canonicalCode != null) {
            return canonicalCode;
        }

        // 嘗試從大小寫不敏感的映射中找到對應的語言代碼
        for (Map.Entry<String, String> entry : LANGUAGE_NAME_TO_CODE.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).equals(normalizedInput)) {
                return entry.getValue();
            }
        }

        // 如果無法識別，則返回原始輸入
        return languageNameOrCode;
    }

    /**
     * 將語言代碼轉換為中文名稱
     *
     * @param languageCode 語言代碼
     * @return 中文名稱，如果無法識別則返回語言代碼
     */
    public static String toChineseName(String languageCode) {
        if (languageCode == null || languageCode.trim().isEmpty()) {
            return "未知語言";
        }

        String canonicalCode = canonicalCode(languageCode.trim());
        return canonicalCode == null
                ? languageCode
                : LANGUAGE_CODE_TO_NAME.get(canonicalCode);
    }

    /**
     * 檢查是否支持該語言
     *
     * @param languageNameOrCode 語言名稱或代碼
     * @return 是否支持
     */
    public static boolean isSupported(String languageNameOrCode) {
        if (languageNameOrCode == null || languageNameOrCode.trim().isEmpty()) {
            return false;
        }

        String normalizedInput = languageNameOrCode.trim().toLowerCase(Locale.ROOT);

        // 檢查代碼是否存在
        if (canonicalCode(normalizedInput) != null) {
            return true;
        }

        // 檢查名稱是否存在於 LANGUAGE_NAME_TO_CODE 中
        if (LANGUAGE_NAME_TO_CODE.containsKey(normalizedInput)) {
            return true;
        }

        // 檢查名稱是否存在（大小寫不敏感）
        for (String name : LANGUAGE_NAME_TO_CODE.keySet()) {
            if (name.toLowerCase(Locale.ROOT).equals(normalizedInput)) {
                return true;
            }
        }

        return false;
    }

    private static String canonicalCode(String code) {
        if (code == null) {
            return null;
        }
        return LANGUAGE_CODE_TO_NAME.keySet().stream()
                .filter(candidate -> candidate.equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElse(null);
    }
}
