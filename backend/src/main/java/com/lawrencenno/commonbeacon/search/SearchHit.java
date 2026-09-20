package com.lawrencenno.commonbeacon.search;

import java.util.UUID;

public record SearchHit(Kind kind, UUID id, String title, String snippet, String url, double rank) {
    public enum Kind { ARTICLE, QUESTION }

    // The database returns at most 241 code points; the API bounds UTF-16 units.
    static String snippet(String body) {
        if (body.length() <= 240) return body;
        int end = 239;
        if (Character.isHighSurrogate(body.charAt(end - 1))) end--;
        return body.substring(0, end) + "\u2026";
    }
}
