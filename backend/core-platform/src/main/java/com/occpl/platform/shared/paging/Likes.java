package com.occpl.platform.shared.paging;

/** Builds safe SQL LIKE patterns from user input (escapes wildcards; prefix match keeps indexes usable). */
public final class Likes {

    private Likes() {
    }

    /** Returns {@code null} for blank input so optional query filters can be skipped. */
    public static String prefix(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String escaped = input.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return escaped + "%";
    }
}
