package org.remus.giteabot.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotMentionTest {

    @Test
    void matchesWholeMention() {
        assertTrue(BotMention.isMentioned("@ai_bot please review", "@ai_bot"));
        assertTrue(BotMention.isMentioned("cc @ai_bot", "@ai_bot"));
        assertTrue(BotMention.isMentioned("(@ai_bot)", "@ai_bot"));
        assertTrue(BotMention.isMentioned("line one\n@ai_bot clarify", "@ai_bot"));
    }

    @Test
    void matchesMentionFollowedByPunctuation() {
        assertTrue(BotMention.isMentioned("thanks @ai_bot.", "@ai_bot"));
        assertTrue(BotMention.isMentioned("@ai_bot, can you check?", "@ai_bot"));
        assertTrue(BotMention.isMentioned("@ai_bot: rerun-tests", "@ai_bot"));
    }

    @Test
    void ignoresLongerNamesStartingWithAlias() {
        assertFalse(BotMention.isMentioned("@ai_bot2 please review", "@ai_bot"));
        assertFalse(BotMention.isMentioned("@ai_bot_helper hi", "@ai_bot"));
        assertFalse(BotMention.isMentioned("@ai_bot-dev hi", "@ai_bot"));
        assertFalse(BotMention.isMentioned("@ai_bot.helper hi", "@ai_bot"));
    }

    @Test
    void ignoresEmailAddresses() {
        assertFalse(BotMention.isMentioned("mail me@ai_bot.com", "@ai_bot"));
    }

    @Test
    void staysCaseSensitive() {
        assertFalse(BotMention.isMentioned("@AI_BOT hi", "@ai_bot"));
    }

    @Test
    void blankOrNullNeverMatches() {
        assertFalse(BotMention.isMentioned("@ai_bot hi", ""));
        assertFalse(BotMention.isMentioned("@ai_bot hi", null));
        assertFalse(BotMention.isMentioned(null, "@ai_bot"));
    }

    @Test
    void aliasIsMatchedLiterally() {
        assertTrue(BotMention.isMentioned("@bot.v2 hi", "@bot.v2"));
        assertFalse(BotMention.isMentioned("@botxv2 hi", "@bot.v2"));
    }

    @Test
    void replaceMentionOnlyReplacesWholeMentions() {
        assertEquals("  and @ai_bot2", BotMention.replaceMention("@ai_bot and @ai_bot2", "@ai_bot", " "));
        assertEquals("hi $1", BotMention.replaceMention("hi @ai_bot", "@ai_bot", "$1"));
        assertEquals("hi @ai_bot", BotMention.replaceMention("hi @ai_bot", "", " "));
    }
}
