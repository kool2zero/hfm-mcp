package com.hfmmcp.daemon.backend.oracle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks that the HFM and Thrift classes on this machine's classpath provide everything the
 * prebuilt Oracle backend links against: the manifest {@value #MANIFEST}, generated from the
 * backend's own bytecode (classes, constructors, methods and fields it references). Run by the
 * installer, and by the daemon at startup.
 *
 * <p>Usage: {@code java -cp <daemon jars>;<EPM classpath> com.hfmmcp.daemon.backend.oracle.ApiCheck}
 * — exit code 0 when everything matches, 1 with a list of differences otherwise.
 */
public final class ApiCheck {
    static final String MANIFEST = "hfm-api-manifest.txt";

    private static final Map<String, Class<?>> PRIMITIVES = new HashMap<>();

    static {
        for (Class<?> c : new Class<?>[] {boolean.class, byte.class, char.class, short.class, int.class,
                long.class, float.class, double.class, void.class}) {
            PRIMITIVES.put(c.getName(), c);
        }
    }

    private ApiCheck() {
    }

    public static void main(String[] args) {
        List<String> problems = verify();
        if (problems == null) {
            System.out.println("No API manifest in this build (backend compiled on this server): nothing to check.");
            System.exit(0);
        }
        if (problems.isEmpty()) {
            System.out.println("HFM API check passed: the prebuilt Oracle backend matches this server's EPM jars.");
            System.exit(0);
        }
        System.out.println("HFM API check FAILED (" + problems.size() + " difference(s)):");
        for (String p : problems) {
            System.out.println("  " + p);
        }
        System.exit(1);
    }

    /** Differences found, empty when all match; null when the jar carries no manifest. */
    public static List<String> verify() {
        ClassLoader loader = ApiCheck.class.getClassLoader();
        java.util.Set<String> problems = new java.util.LinkedHashSet<>();
        try (InputStream in = loader.getResourceAsStream(MANIFEST)) {
            if (in == null) {
                return null;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String problem = check(line.split(" "), loader);
                if (problem != null) {
                    problems.add(problem);
                }
            }
        } catch (IOException e) {
            problems.add("Cannot read " + MANIFEST + ": " + e.getMessage());
        }
        return new ArrayList<>(problems);
    }

    private static String check(String[] f, ClassLoader loader) {
        Class<?> c;
        try {
            c = Class.forName(f[1], false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return "missing class " + f[1] + " (is its jar on the EPM classpath?)";
        }
        try {
            switch (f[0]) {
                case "C": {
                    if ("any".equals(f[2])) {
                        return null;
                    }
                    String kind = c.isInterface() ? "interface" : "class";
                    return kind.equals(f[2]) ? null : f[1] + " is a " + kind + ", expected " + f[2];
                }
                case "F": {
                    java.lang.reflect.Field field = c.getField(f[3]);
                    if (Modifier.isStatic(field.getModifiers()) != "static".equals(f[2])) {
                        return f[1] + "." + f[3] + " should be " + f[2];
                    }
                    if (!field.getType().getName().equals(f[4])) {
                        return f[1] + "." + f[3] + " is " + field.getType().getName() + ", expected " + f[4];
                    }
                    return null;
                }
                case "K": {
                    Constructor<?> k = c.getConstructor(types(f[2], loader));
                    return k == null ? "missing constructor " + f[1] + "(" + f[2] + ")" : null;
                }
                case "M": {
                    Method m = c.getMethod(f[3], types(f[5], loader));
                    if (Modifier.isStatic(m.getModifiers()) != "static".equals(f[2])) {
                        return f[1] + "." + f[3] + " should be " + f[2];
                    }
                    if (!m.getReturnType().getName().equals(f[4])) {
                        return f[1] + "." + f[3] + "(" + f[5] + ") returns " + m.getReturnType().getName()
                                + ", expected " + f[4];
                    }
                    return null;
                }
                default:
                    return null;
            }
        } catch (NoSuchFieldException e) {
            return "missing field " + f[1] + "." + f[3];
        } catch (NoSuchMethodException e) {
            return "missing " + (f[0].equals("K") ? "constructor " + f[1] + "(" + f[2] + ")"
                    : "method " + f[1] + "." + f[3] + "(" + f[5] + ")");
        } catch (ClassNotFoundException | LinkageError e) {
            return "cannot resolve a type used by " + f[1] + ": " + e;
        }
    }

    private static Class<?>[] types(String list, ClassLoader loader) throws ClassNotFoundException {
        if ("-".equals(list)) {
            return new Class<?>[0];
        }
        String[] names = list.split(",");
        Class<?>[] out = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++) {
            Class<?> p = PRIMITIVES.get(names[i]);
            out[i] = p != null ? p : Class.forName(names[i], false, loader);
        }
        return out;
    }
}
