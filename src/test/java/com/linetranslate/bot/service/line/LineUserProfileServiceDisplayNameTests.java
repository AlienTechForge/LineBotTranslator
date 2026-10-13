package com.linetranslate.bot.service.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.ai.AiProviderAdapter;
import com.linetranslate.bot.service.preference.UserPreferencesModule;

class LineUserProfileServiceDisplayNameTests {

    private static final String USER_ID = "U-display-name";

    private UserProfileRepository repository;
    private LineUserProfileService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserProfileRepository.class);
        UserPreferencesModule preferences = new UserPreferencesModule(
                repository, () -> null, List.of(mock(AiProviderAdapter.class)));
        service = new LineUserProfileService(
                mock(MessagingApiClient.class), repository, mock(TranslationRecordRepository.class), preferences);
    }

    @Test
    void existingProfileGetsOnlyAnAtomicDisplayNameWrite() {
        UserProfile existing = UserProfile.builder().id("id-1").userId(USER_ID).displayName("Old").build();
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        when(repository.setDisplayName(USER_ID, "New Name")).thenReturn(true);

        String reply = service.setUserDisplayName(USER_ID, "  New Name  ");

        assertThat(reply).startsWith("✅ 已將用戶").endsWith("New Name");
        verify(repository).setDisplayName(USER_ID, "New Name");
        verify(repository, never()).save(any(UserProfile.class));
        verify(repository, never()).insert(any(UserProfile.class));
    }

    @Test
    void missingProfileIsCreatedByTheIdempotentCreatorThenNamedAtomically() {
        UserProfile created = UserProfile.builder().id("id-2").userId(USER_ID).build();
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(repository.insert(any(UserProfile.class))).thenReturn(created);
        when(repository.setDisplayName(USER_ID, "Fresh")).thenReturn(true);

        String reply = service.setUserDisplayName(USER_ID, "Fresh");

        assertThat(reply).startsWith("✅ 已為用戶").endsWith("Fresh");
        verify(repository).insert(any(UserProfile.class));
        verify(repository).setDisplayName(USER_ID, "Fresh");
        verify(repository, never()).save(any(UserProfile.class));
    }

    @Test
    void losingTheUniqueIndexRaceStillSetsTheNameInsteadOfReportingFailure() {
        // Two admins name the same never-seen user at once: both find no profile, the loser's
        // insert hits the unique userId index and must adopt the winner's document.
        UserProfile winner = UserProfile.builder().id("winner").userId(USER_ID).build();
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty(), Optional.empty(), Optional.of(winner));
        when(repository.insert(any(UserProfile.class))).thenThrow(new DuplicateKeyException("E11000"));
        when(repository.setDisplayName(USER_ID, "Racer")).thenReturn(true);

        String reply = service.setUserDisplayName(USER_ID, "Racer");

        assertThat(reply).startsWith("✅").doesNotContain("❌");
        verify(repository, times(1)).setDisplayName(USER_ID, "Racer");
        verify(repository, never()).save(any(UserProfile.class));
    }

    @Test
    void blankNameIsRejectedBeforeAnyStorageAccess() {
        assertThat(service.setUserDisplayName(USER_ID, "   ")).startsWith("❌");
        assertThat(service.setUserDisplayName(USER_ID, null)).startsWith("❌");

        verifyNoInteractions(repository);
    }

    @Test
    void storageFailureIsReportedAsAFailureReply() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(UserProfile.builder().userId(USER_ID).build()));
        when(repository.setDisplayName(USER_ID, "Name")).thenThrow(new IllegalStateException("mongo down"));

        assertThat(service.setUserDisplayName(USER_ID, "Name")).startsWith("❌");
    }

    @Test
    void profileThatVanishesBetweenCreationAndWriteIsReportedAsAFailureReply() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(UserProfile.builder().userId(USER_ID).build()));
        when(repository.setDisplayName(USER_ID, "Name")).thenReturn(false);

        assertThat(service.setUserDisplayName(USER_ID, "Name")).startsWith("❌");
    }
}
