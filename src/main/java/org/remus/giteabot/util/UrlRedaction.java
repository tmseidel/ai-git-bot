package org.remus.giteabot.util;

import java.util.regex.Pattern;

/** Strips query strings and fragments from URLs: pre-signed URLs carry credentials there. */
public final class UrlRedaction {

    private static final Pattern URL_SECRET_PART =
            Pattern.compile("(?i)(\\bhttps?://[^\\s?#\"'`<>]*)[?#][^\\s\"'`<>]*");

    private UrlRedaction() {
    }

    public static String redact(String url) {
        if (url == null) {
            return null;
        }
        int cut = indexOfSecretPart(url);
        return cut < 0 ? url : url.substring(0, cut);
    }

    public static String redactAll(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return URL_SECRET_PART.matcher(text).replaceAll("$1");
    }

    private static int indexOfSecretPart(String url) {
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        if (query < 0) {
            return fragment;
        }
        return fragment < 0 ? query : Math.min(query, fragment);
    }
}
