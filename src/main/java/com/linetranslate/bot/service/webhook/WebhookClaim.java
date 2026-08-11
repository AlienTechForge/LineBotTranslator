package com.linetranslate.bot.service.webhook;

public record WebhookClaim(String eventId, String claimToken, boolean claimed) {

    public WebhookClaim {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("Webhook claim requires event ID");
        }
        if (claimed && (claimToken == null || claimToken.isBlank())) {
            throw new IllegalArgumentException("Claimed webhook requires a claim token");
        }
        if (!claimed) {
            claimToken = null;
        }
    }

    public static WebhookClaim claimed(String eventId, String claimToken) {
        return new WebhookClaim(eventId, claimToken, true);
    }

    public static WebhookClaim duplicate(String eventId) {
        return new WebhookClaim(eventId, null, false);
    }
}
