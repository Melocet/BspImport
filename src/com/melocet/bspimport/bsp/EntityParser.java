package com.melocet.bspimport.bsp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The entity lump: { "key" "value" ... } blocks. Keys are lowercased. */
final class EntityParser {

    private EntityParser() {}

    static List<Map<String, String>> parse(String text) {
        List<Map<String, String>> out = new ArrayList<>();
        Map<String, String> current = null;
        String pendingKey = null;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '{') {
                current = new HashMap<>();
                pendingKey = null;
                i++;
            } else if (c == '}') {
                if (current != null) out.add(current);
                current = null;
                i++;
            } else if (c == '"') {
                int end = text.indexOf('"', i + 1);
                if (end < 0) break;
                String token = text.substring(i + 1, end);
                i = end + 1;
                if (current == null) continue;
                if (pendingKey == null) {
                    pendingKey = token.toLowerCase(Locale.ROOT);
                } else {
                    current.put(pendingKey, token);
                    pendingKey = null;
                }
            } else {
                i++;
            }
        }
        return out;
    }
}
