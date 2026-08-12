package com.linetranslate.bot.service.line;

import com.linecorp.bot.webhook.model.Source;
import com.linetranslate.bot.service.line.intent.LineIntent;

/** Optional user feedback shown before long-running LINE interactions. */
public interface LineLoadingFeedback {

    void beforeText(Source source, LineIntent intent);

    LineLoadingSession startImage(Source source);
}
