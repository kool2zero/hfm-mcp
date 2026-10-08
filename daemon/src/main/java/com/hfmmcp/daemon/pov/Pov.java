package com.hfmmcp.daemon.pov;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Parses and formats HFM POV strings such as {@code S#Actual.Y#2024.P#Jan.E#Group.UK.A#Sales}. */
public final class Pov {
    private static final Pattern PREFIX = Pattern.compile("[A-Za-z][A-Za-z0-9]*");

    private Pov() {
    }

    /**
     * Splits a POV string into prefix → member, preserving order.
     *
     * <p>Segments without a {@code #} belong to the previous member, because HFM allows an entity
     * to be qualified by its parent ({@code E#Group.UK}).
     */
    public static LinkedHashMap<String, String> parse(String pov) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        String key = null;
        StringBuilder member = null;
        for (String token : pov.trim().split("\\.", -1)) {
            int hash = token.indexOf('#');
            if (hash > 0 && PREFIX.matcher(token.substring(0, hash)).matches()) {
                if (key != null) {
                    out.put(key, member.toString());
                }
                key = token.substring(0, hash);
                member = new StringBuilder(token.substring(hash + 1));
            } else if (key != null) {
                member.append('.').append(token);
            } else {
                throw new IllegalArgumentException("Not a POV string: " + pov);
            }
        }
        if (key != null) {
            out.put(key, member.toString());
        }
        return out;
    }

    public static String format(Map<String, String> prefixToMember) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : prefixToMember.entrySet()) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(e.getKey()).append('#').append(e.getValue());
        }
        return sb.toString();
    }
}
