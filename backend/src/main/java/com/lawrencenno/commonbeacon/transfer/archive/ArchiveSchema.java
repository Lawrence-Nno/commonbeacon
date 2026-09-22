package com.lawrencenno.commonbeacon.transfer.archive;

import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Validator for the deliberately small vocabulary used by our bundled schemas.
 * Not a general-purpose JSON Schema implementation. Never loads a supplied schema. */
final class ArchiveSchema {
    private final Map<String, JsonNode> schemas = new HashMap<>();
    ArchiveSchema(JsonMapper mapper) {
        for (var profile : ArchiveFormat.Profile.values()) {
            for (var entity : ArchiveFormat.Entity.values()) {
                load(mapper, profile + "-" + entity);
            }
        }
        load(mapper, "manifest");
    }
    private void load(JsonMapper mapper, String name) {
        try (var input = getClass().getResourceAsStream("/data-transfer/v1/" + name + ".schema.json")) {
            if (input != null) schemas.put(name, mapper.readTree(input));
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot load archive schema", e); }
    }
    boolean valid(String name, JsonNode value) {
        return schemas.containsKey(name) && matches(schemas.get(name), value);
    }
    private boolean matches(JsonNode s, JsonNode v) {
        if (v == null) return false;
        if (s.has("anyOf")) {
            for (var alternative : s.get("anyOf")) if (matches(alternative, v)) return true;
            return false;
        }
        String type = s.path("type").asText();
        if (!(switch (type) {
            case "object" -> v.isObject(); case "array" -> v.isArray();
            case "string" -> v.isString(); case "integer" -> v.isIntegralNumber();
            case "boolean" -> v.isBoolean(); case "null" -> v.isNull(); default -> false;
        })) return false;
        if (s.has("const") && !s.get("const").equals(v)) return false;
        if (s.has("enum")) {
            boolean found = false;
            for (var option : s.get("enum")) found |= option.equals(v);
            if (!found) return false;
        }
        if (v.isObject()) {
            var props = s.get("properties");
            for (var key : s.get("required")) if (!v.has(key.asText())) return false;
            for (var entry : v.properties()) {
                if (!props.has(entry.getKey()) || !matches(props.get(entry.getKey()), entry.getValue())) return false;
            }
        } else if (v.isArray()) {
            if (v.size() > s.path("maxItems").asInt()) return false;
            for (var item : v) if (!matches(s.get("items"), item)) return false;
        } else if (v.isString()) {
            String text = v.asText();
            int points = text.codePointCount(0, text.length());
            if (s.has("x-minUtf16Length") && text.length() < s.get("x-minUtf16Length").asInt()) return false;
            if (s.has("minLength") && points < s.get("minLength").asInt()) return false;
            if (s.has("maxLength") && points > s.get("maxLength").asInt()) return false;
            if (s.has("x-maxUtf16Length") && text.length() > s.get("x-maxUtf16Length").asInt()) return false;
            if (s.path("x-trimmed").asBoolean() && (!text.equals(text.trim()) || text.isBlank())) return false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == 0) return false;
                if (Character.isHighSurrogate(c)) {
                    if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
                } else if (Character.isLowSurrogate(c)) return false;
            }
            if (s.has("pattern") && !text.matches(s.get("pattern").asText())) return false;
            try {
                if ("uuid".equals(s.path("format").asText()) && !UUID.fromString(text).toString().equals(text)) return false;
                if ("date-time".equals(s.path("format").asText())) {
                    if (!text.endsWith("Z") || Instant.parse(text).getNano() % 1000 != 0) return false;
                }
            } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) { return false; }
        } else if (v.isIntegralNumber()) {
            if (!v.canConvertToLong()) return false;
            if (s.has("minimum") && v.asLong() < s.get("minimum").asLong()) return false;
            if (s.has("maximum") && v.asLong() > s.get("maximum").asLong()) return false;
        }
        return true;
    }
}
