package com.hfmmcp.daemon.pov;

import com.hfmmcp.daemon.backend.DimensionInfo;
import com.hfmmcp.daemon.http.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves user-supplied dimension keys (name, POV prefix, or {@code CustomN}) to dimensions and
 * builds complete POVs on top of the application's default POV.
 */
public final class PovResolver {
    private final List<DimensionInfo> dimensions;
    private final Map<String, DimensionInfo> byKey = new LinkedHashMap<>();

    public PovResolver(List<DimensionInfo> dimensions) {
        this.dimensions = dimensions;
        int custom = 0;
        for (DimensionInfo d : dimensions) {
            byKey.put(norm(d.prefix()), d);
            byKey.put(norm(d.name()), d);
            if (d.prefix().matches("C\\d+")) {
                byKey.put("custom" + d.prefix().substring(1), d);
            } else if (!isStandardPrefix(d.prefix())) {
                byKey.put("custom" + (++custom), d);
            }
        }
        if (byKey.containsKey("i")) {
            byKey.putIfAbsent("intercompany", byKey.get("i"));
        }
    }

    public List<DimensionInfo> dimensions() {
        return dimensions;
    }

    public DimensionInfo resolve(String key) {
        DimensionInfo d = key == null ? null : byKey.get(norm(key));
        if (d == null) {
            throw ApiException.badRequest("unknown_dimension",
                    "Unknown dimension '" + key + "'. Known dimensions: " + names());
        }
        return d;
    }

    /** The default POV with {@code overrides} applied, keyed by prefix in POV order. */
    public LinkedHashMap<String, String> basePov(Map<String, String> overrides) {
        LinkedHashMap<String, String> pov = new LinkedHashMap<>();
        for (DimensionInfo d : dimensions) {
            pov.put(d.prefix(), d.defaultMember());
        }
        if (overrides != null) {
            for (Map.Entry<String, String> e : overrides.entrySet()) {
                String member = e.getValue() == null ? "" : e.getValue().trim();
                if (member.isEmpty()) {
                    throw ApiException.badRequest("bad_pov", "Empty member for dimension '" + e.getKey() + "'");
                }
                pov.put(resolve(e.getKey()).prefix(), member);
            }
        }
        return pov;
    }

    /** Prefix-keyed POV → name-keyed POV, for responses. */
    public LinkedHashMap<String, String> byName(Map<String, String> prefixPov) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (DimensionInfo d : dimensions) {
            String m = prefixPov.get(d.prefix());
            if (m != null) {
                out.put(d.name(), m);
            }
        }
        return out;
    }

    private List<String> names() {
        List<String> n = new ArrayList<>();
        for (DimensionInfo d : dimensions) {
            n.add(d.name() + " (" + d.prefix() + ")");
        }
        return n;
    }

    private static boolean isStandardPrefix(String p) {
        return p.length() == 1 && "SYPWVEAI".indexOf(Character.toUpperCase(p.charAt(0))) >= 0;
    }

    private static String norm(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }
}
