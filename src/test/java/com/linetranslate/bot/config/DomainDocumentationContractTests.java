package com.linetranslate.bot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class DomainDocumentationContractTests {

    @Test
    void contextDefinesSharedDomainLanguageAndInvariants() throws IOException {
        String context = Files.readString(Path.of("CONTEXT.md"));

        assertThat(context).contains(
                "Translation Request",
                "Translation Result",
                "AI Provider",
                "Language Detection",
                "User Preferences",
                "Image Translation",
                "Translation Record",
                "Runtime Settings",
                "Provider Attempt",
                "Usage Event",
                "Webhook Receipt",
                "LINE Intent",
                "## 核心不變量",
                "## 端到端流程");
    }

    @Test
    void contextRecordsMaintainerAcceptance() throws IOException {
        String context = Files.readString(Path.of("CONTEXT.md"));

        assertThat(context)
                .contains("> 狀態：Accepted（maintainer 於 2026-08-11 確認）")
                .doesNotContain("- [ ]");
    }

    @Test
    void adrDirectoryContainsTemplateIndexAndAcceptedDecisions() throws IOException {
        Path adrDirectory = Path.of("docs", "adr");
        assertThat(adrDirectory.resolve("README.md")).exists();
        assertThat(adrDirectory.resolve("0000-template.md")).exists();

        List<Path> decisions;
        try (var paths = Files.list(adrDirectory)) {
            decisions = paths
                    .filter(path -> path.getFileName().toString().matches("(?!0000)\\d{4}-.*\\.md"))
                    .toList();
        }
        assertThat(decisions).hasSizeGreaterThanOrEqualTo(10);
        for (Path decision : decisions) {
            String content = Files.readString(decision).replace("\r\n", "\n");
            assertThat(content)
                    .as(decision.toString())
                    .contains("## Status", "## Context", "## Decision", "## Consequences")
                    .containsAnyOf("## Status\n\nAccepted", "## Status\n\nSuperseded by");
        }
    }

    @Test
    void readmeLinksDomainDocsAndOnlyDocumentsCurrentCommands() throws IOException {
        String readme = Files.readString(Path.of("README.md"));

        assertThat(readme)
                .contains("[Domain glossary 與系統脈絡](CONTEXT.md)")
                .contains("[Architecture Decision Records](docs/adr/README.md)")
                .contains("/外文翻譯 [語言]")
                .contains("/中文翻譯 [語言]")
                .contains("/models [關鍵字]")
                .contains("/model [完整模型 slug]")
                .doesNotContain("/setlang", "/setai");
    }
}
