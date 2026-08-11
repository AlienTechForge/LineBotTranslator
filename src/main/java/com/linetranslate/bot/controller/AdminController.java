package com.linetranslate.bot.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.linecorp.bot.messaging.model.Message;
import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.service.AdminService;
import com.linetranslate.bot.service.line.AdminCardRenderer;
import com.linetranslate.bot.service.line.AdminInteractionModule;
import com.linetranslate.bot.service.line.intent.AdminIntentParser;

import lombok.extern.slf4j.Slf4j;

/** LINE Adapter from raw administrator commands to validated domain intents. */
@Component
@Slf4j
public class AdminController {

    private final AdminIntentParser intentParser;
    private final AdminInteractionModule interactionModule;

    @Autowired
    public AdminController(
            AdminIntentParser intentParser,
            AdminInteractionModule interactionModule) {
        this.intentParser = intentParser;
        this.interactionModule = interactionModule;
    }

    /** Compatibility seam for focused controller tests. */
    public AdminController(AdminService adminService, AdminCardRenderer cardRenderer) {
        this(new AdminIntentParser(), new AdminInteractionModule(adminService, cardRenderer));
    }

    public Message handleCommand(String userId, String command) {
        log.info("處理管理員命令: user={}, command={}",
                SafeLog.user(userId), SafeLog.content(command));
        return interactionModule.execute(userId, intentParser.parse(command));
    }
}
