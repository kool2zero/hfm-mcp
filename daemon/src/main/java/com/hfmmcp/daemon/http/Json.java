package com.hfmmcp.daemon.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Shared Jackson configuration. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<java.util.LinkedHashMap<String, Object>> OBJECT_MAP =
            new TypeReference<java.util.LinkedHashMap<String, Object>>() { };

    private Json() {
    }

    /**
     * Parses a JSON object into plain Java maps and lists. Code compiled outside this jar (the Oracle
     * backend) uses this instead of Jackson, whose packages are relocated when the jar is shaded.
     */
    public static java.util.Map<String, Object> parseObject(String json) throws java.io.IOException {
        return MAPPER.readValue(json, OBJECT_MAP);
    }
}
