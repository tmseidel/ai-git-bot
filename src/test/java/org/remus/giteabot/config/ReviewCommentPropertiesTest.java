package org.remus.giteabot.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The footer default contains Markdown and a URL, so it is spelled as a placeholder in
 * {@code application.properties}. This pins that the placeholder resolves to the intended text
 * instead of something the property parser mangled.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReviewCommentPropertiesTest {

    @Autowired private ReviewCommentProperties properties;

    @Test
    void bindsTheMarkdownFooterDefaults() {
        assertEquals("*Agentic review by [AI Git Bot](https://gitbot.eu)*", properties.getFooter());
        assertEquals(ReviewCommentProperties.DEFAULT_FOOTER, properties.getFooter());

        assertEquals("*Agentic review follow-up by [AI Git Bot](https://gitbot.eu)*",
                properties.getClarificationFooter());
        assertEquals(ReviewCommentProperties.DEFAULT_CLARIFICATION_FOOTER,
                properties.getClarificationFooter());
    }
}
