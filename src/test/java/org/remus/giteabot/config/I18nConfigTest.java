package org.remus.giteabot.config;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.util.Locale;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I18nConfigTest {

    private final I18nConfig config = new I18nConfig();

    @Test
    void resolveLocale_cookiePresent_usesCookieLocale() {
        LocaleResolver resolver = config.localeResolver();

        MockHttpServletRequest fr = new MockHttpServletRequest();
        fr.setCookies(new Cookie(I18nConfig.LOCALE_COOKIE, "fr"));
        assertEquals(Locale.FRENCH, resolver.resolveLocale(fr));

        MockHttpServletRequest zh = new MockHttpServletRequest();
        zh.setCookies(new Cookie(I18nConfig.LOCALE_COOKIE, "zh_CN"));
        assertEquals(Locale.SIMPLIFIED_CHINESE, resolver.resolveLocale(zh));
    }

    @Test
    void resolveLocale_noCookie_supportedBrowserLocale_used() {
        LocaleResolver resolver = config.localeResolver();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addPreferredLocale(Locale.GERMAN);
        assertEquals(Locale.GERMAN, resolver.resolveLocale(request));
    }

    @Test
    void resolveLocale_noCookie_unsupportedBrowserLocale_fallsBackToEnglish() {
        LocaleResolver resolver = config.localeResolver();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addPreferredLocale(Locale.ITALIAN);
        assertEquals(Locale.ENGLISH, resolver.resolveLocale(request));
    }

    @Test
    void supportedLocaleOrEnglish_mapsLanguagesAndRequiresCNForChinese() {
        assertEquals(Locale.GERMAN, I18nConfig.supportedLocaleOrEnglish(Locale.GERMANY));
        assertEquals(Locale.JAPANESE, I18nConfig.supportedLocaleOrEnglish(Locale.JAPAN));
        assertEquals(Locale.ENGLISH, I18nConfig.supportedLocaleOrEnglish(Locale.US));
        assertEquals(Locale.SIMPLIFIED_CHINESE, I18nConfig.supportedLocaleOrEnglish(Locale.of("zh", "CN")));
        assertEquals(Locale.ENGLISH, I18nConfig.supportedLocaleOrEnglish(Locale.of("zh", "TW")));
        assertEquals(Locale.ENGLISH, I18nConfig.supportedLocaleOrEnglish(Locale.of("zh")));
        assertEquals(Locale.ENGLISH, I18nConfig.supportedLocaleOrEnglish(null));
    }

    @Test
    void localeChangeInterceptor_usesLangParam() {
        LocaleChangeInterceptor interceptor = config.localeChangeInterceptor();
        assertEquals(I18nConfig.LOCALE_PARAM, interceptor.getParamName());
    }

    @Test
    void supportedLocales_containsAllSeven() {
        assertEquals(7, I18nConfig.SUPPORTED.size());
        assertEquals("en", I18nConfig.SUPPORTED.get(0).code());
        assertEquals("zh_CN", I18nConfig.SUPPORTED.get(6).code());
    }

    @Test
    void textOnlyMessages_containNoMarkupInAnySupportedLocale() {
        for (I18nConfig.LocaleOption option : I18nConfig.SUPPORTED) {
            Locale locale = Locale.forLanguageTag(option.code().replace('_', '-'));
            ResourceBundle messages = ResourceBundle.getBundle("messages", locale);
            for (String key : new String[]{"help.ai.apiKeyRequiredNew", "help.hook.customHeaders"}) {
                String message = messages.getString(key);
                assertFalse(message.contains("<") || message.contains(">"),
                        () -> option.code() + ": " + key + " contains markup: " + message);
            }
        }
    }

    @Test
    void localeOption_matches_ignoresChineseScriptVariant() {
        var zh = I18nConfig.SUPPORTED.get(6);
        assertEquals("zh_CN", zh.code());
        assertTrue(zh.matches(Locale.SIMPLIFIED_CHINESE));
        assertTrue(zh.matches(Locale.forLanguageTag("zh-Hans-CN")));
        assertFalse(zh.matches(Locale.FRENCH));

        assertTrue(I18nConfig.SUPPORTED.get(0).matches(Locale.ENGLISH));
        assertTrue(I18nConfig.SUPPORTED.get(1).matches(Locale.FRENCH));
        assertTrue(I18nConfig.SUPPORTED.get(2).matches(Locale.GERMAN));
    }
}
