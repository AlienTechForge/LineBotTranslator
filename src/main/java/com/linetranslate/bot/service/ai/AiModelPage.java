package com.linetranslate.bot.service.ai;

import java.util.List;

/** Bounded view of a model catalog suitable for chat rendering. */
public record AiModelPage(List<AiModelDescriptor> models, int total, boolean stale) {

    public AiModelPage {
        models = models == null ? List.of() : List.copyOf(models);
        total = Math.max(total, models.size());
    }
}
