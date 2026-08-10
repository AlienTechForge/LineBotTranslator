package com.linetranslate.bot.service.line;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.linecorp.bot.client.LineMessagingClient;
import com.linecorp.bot.model.profile.UserProfileResponse;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.logging.SafeLog;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class LineUserProfileService {

    private final LineMessagingClient lineMessagingClient;
    private final UserProfileRepository userProfileRepository;
    private final TranslationRecordRepository translationRecordRepository;

    @Autowired
    public LineUserProfileService(
            LineMessagingClient lineMessagingClient,
            UserProfileRepository userProfileRepository,
            TranslationRecordRepository translationRecordRepository) {
        this.lineMessagingClient = lineMessagingClient;
        this.userProfileRepository = userProfileRepository;
        this.translationRecordRepository = translationRecordRepository;
    }

    /**
     * 同步 LINE 用戶資料
     */
    public void syncUserProfile(String userId) {
        try {
            // 從 LINE 平台獲取用戶資料
            UserProfileResponse lineProfile = lineMessagingClient.getProfile(userId).get();

            // 檢查用戶是否已存在
            Optional<UserProfile> existingProfile = userProfileRepository.findByUserId(userId);

            if (existingProfile.isPresent()) {
                // 更新現有用戶資料
                UserProfile userProfile = existingProfile.get();
                userProfile.setDisplayName(lineProfile.getDisplayName());

                // 將 URI 轉換為 String
                if (lineProfile.getPictureUrl() != null) {
                    userProfile.setPictureUrl(lineProfile.getPictureUrl().toString());
                }

                userProfile.setStatusMessage(lineProfile.getStatusMessage());
                userProfileRepository.save(userProfile);
                log.info("已更新用戶資料: user={}", SafeLog.user(userId));
            } else {
                // 創建新用戶資料
                UserProfile.UserProfileBuilder builder = UserProfile.builder()
                        .userId(userId)
                        .displayName(lineProfile.getDisplayName());

                // 將 URI 轉換為 String
                if (lineProfile.getPictureUrl() != null) {
                    builder.pictureUrl(lineProfile.getPictureUrl().toString());
                }

                if (lineProfile.getStatusMessage() != null) {
                    builder.statusMessage(lineProfile.getStatusMessage());
                }

                UserProfile newProfile = builder.build();
                userProfileRepository.save(newProfile);
                log.info("已創建用戶資料: user={}", SafeLog.user(userId));
            }
        } catch (Exception e) {
            log.error("同步用戶資料失敗: user={}, failure={}",
                    SafeLog.user(userId), SafeLog.failure(e));
        }
    }

    /**
     * 獲取用戶資料對象
     * 
     * @param userId 用戶 ID
     * @return 用戶資料對象，如果不存在則返回 null
     */
    public UserProfile getUserProfile(String userId) {
        Optional<UserProfile> userProfileOptional = userProfileRepository.findByUserId(userId);
        
        if (userProfileOptional.isPresent()) {
            return userProfileOptional.get();
        } else {
            // 如果用戶不存在，嘗試同步用戶資料
            try {
                syncUserProfile(userId);
                return userProfileRepository.findByUserId(userId).orElse(null);
            } catch (Exception e) {
            log.error("無法獲取用戶資料: failure={}", SafeLog.failure(e));
                return null;
            }
        }
    }
    
    /**
     * 設置用戶的顯示名稱
     * 
     * @param userId 用戶 ID
     * @param displayName 新的顯示名稱
     * @return 操作結果訊息
     */
    public String setUserDisplayName(String userId, String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return "❌ 顯示名稱不能為空";
        }
        
        // 檢查用戶是否存在
        Optional<UserProfile> userProfileOptional = userProfileRepository.findByUserId(userId);
        
        if (userProfileOptional.isPresent()) {
            UserProfile userProfile = userProfileOptional.get();
            String oldDisplayName = userProfile.getDisplayName();
            userProfile.setDisplayName(displayName.trim());
            userProfileRepository.save(userProfile);
            
                log.info("已更改用戶顯示名稱: user={}, old={}, new={}",
                        SafeLog.user(userId), SafeLog.content(oldDisplayName), SafeLog.content(displayName.trim()));
            return "✅ 已將用戶 " + userId + " 的顯示名稱設置為: " + displayName.trim();
        } else {
            // 如果用戶不存在，嘗試先創建用戶資料
            try {
                UserProfile newProfile = UserProfile.builder()
                        .userId(userId)
                        .displayName(displayName.trim())
                        .build();
                userProfileRepository.save(newProfile);
                
                log.info("已創建用戶資料並設置顯示名稱: user={}, displayName={}",
                        SafeLog.user(userId), SafeLog.content(displayName.trim()));
                return "✅ 已為用戶 " + userId + " 創建資料並設置顯示名稱為: " + displayName.trim();
            } catch (Exception e) {
                log.error("設置用戶顯示名稱失敗: user={}, failure={}",
                        SafeLog.user(userId), SafeLog.failure(e));
                return "❌ 設置顯示名稱失敗，請稍後再試。";
            }
        }
    }
    
    /**
     * 獲取用戶資料信息
     */
    public String getUserProfileInfo(String userId) {
        Optional<UserProfile> userProfileOptional = userProfileRepository.findByUserId(userId);

        if (userProfileOptional.isPresent()) {
            UserProfile profile = userProfileOptional.get();

            // 獲取翻譯統計信息
            int totalTranslations = profile.getTotalTranslations();
            int textTranslations = profile.getTextTranslations();
            int imageTranslations = profile.getImageTranslations();

            StringBuilder info = new StringBuilder();
            info.append("【用戶資料】\n");

            // 顯示用戶名稱
            String displayName = profile.getDisplayName();
            if (displayName == null || displayName.isEmpty()) {
                // 如果用戶名稱為 null，嘗試重新同步用戶資料
                try {
                    syncUserProfile(userId);
                    // 重新獲取用戶資料
                    Optional<UserProfile> refreshedProfile = userProfileRepository.findByUserId(userId);
                    if (refreshedProfile.isPresent()) {
                        displayName = refreshedProfile.get().getDisplayName();
                    }
                } catch (Exception e) {
            log.error("重新同步用戶資料失敗: failure={}", SafeLog.failure(e));
                }
                
                // 如果仍然為 null，使用預設值
                if (displayName == null || displayName.isEmpty()) {
                    displayName = "用戶" + userId.substring(Math.max(0, userId.length() - 6));
                }
            }
            info.append("用戶：").append(displayName).append("\n\n");

            // 顯示翻譯統計
            info.append("【翻譯統計】\n");
            info.append("總翻譯次數：").append(totalTranslations).append("\n");
            info.append("文字翻譯：").append(textTranslations).append("\n");
            info.append("圖片翻譯：").append(imageTranslations).append("\n\n");

            // 顯示偏好設置
            // info.append("【系統設置】\n");
            // String aiProvider = profile.getPreferredAiProvider();
            // info.append("偏好的 AI 引擎：").append(aiProvider != null ? aiProvider : "預設").append("\n");

            return info.toString();
        } else {
            return "無法找到您的用戶資料。請嘗試發送一條訊息後再試。";
        }
    }
}
