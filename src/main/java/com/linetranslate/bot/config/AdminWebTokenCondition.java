package com.linetranslate.bot.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches only when the shared admin web token is configured with a non-blank value. A plain
 * {@code @ConditionalOnProperty} is not enough because it treats an empty string as present, and
 * the deployment passes {@code ADMIN_WEB_TOKEN=} when the secret is unset.
 */
public class AdminWebTokenCondition implements Condition {

    public static final String TOKEN_PROPERTY = "app.admin.web.token";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String token = context.getEnvironment().getProperty(TOKEN_PROPERTY);
        return token != null && !token.isBlank();
    }
}
