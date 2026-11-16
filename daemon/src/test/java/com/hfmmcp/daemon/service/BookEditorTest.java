package com.hfmmcp.daemon.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hfmmcp.daemon.http.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * Golden and unit tests for {@link BookEditor}. Ground truth = Financial Reporting Studio
 * ({@code ME_SPORT.xml} edited into {@code TEST_BOOK.xml}).
 */
class BookEditorTest {

    private static String res(String name) throws Exception {
        try (InputStream in = BookEditorTest.class.getResourceAsStream("/books/" + name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static List<String> canonBook(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        Document d = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        List<String> out = new ArrayList<>();
        walk(d.getDocumentElement(), d.getDocumentElement().getTagName(), out);
        return out;
    }

    private static void walk(Element e, String path, List<String> out) {
        Map<String, String> attrs = new TreeMap<>();
        for (int i = 0; i < e.getAttributes().getLength(); i++) {
            Node a = e.getAttributes().item(i);
            attrs.put(a.getNodeName(), a.getNodeValue());
        }
        if ("BOOK".equals(e.getTagName())) {
            attrs.remove("NAME");
        }
        // Normalize MEM_SEL XML whitespace so equivalent queries compare cleanly
        if (attrs.containsKey("MEM_SEL")) {
            attrs.put("MEM_SEL", attrs.get("MEM_SEL").replace("\r", "").replace("\n", ""));
        }
        StringBuilder text = new StringBuilder();
        List<Element> kids = new ArrayList<>();
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element) {
                kids.add((Element) c);
            } else if (c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(c.getNodeValue());
            }
        }
        String t = text.toString().replace("\r", "").trim();
        out.add(path + " " + attrs + (t.isEmpty() ? "" : " text=" + t));

        // Sort REF_COUNT <DIMENSION> siblings under BOOK_POV/DATASOURCE by NAME since Studio writes them in HashMap order
        if ("DATASOURCE".equals(e.getTagName()) && path.contains("/BOOK_POV")) {
            List<Element> povs = new ArrayList<>();
            Map<String, Element> refs = new TreeMap<>();
            for (Element k : kids) {
                if ("DIMENSION".equals(k.getTagName())) {
                    refs.put(k.getAttribute("NAME"), k);
                } else {
                    povs.add(k);
                }
            }
            int idx = 0;
            for (Element k : povs) {
                walk(k, path + "/" + k.getTagName() + "[" + (idx++) + "]", out);
            }
            int dIdx = 0;
            for (Element k : refs.values()) {
                walk(k, path + "/DIMENSION[" + (dIdx++) + "]", out);
            }
            return;
        }

        Map<String, Integer> seen = new TreeMap<>();
        for (Element k : kids) {
            int n = seen.merge(k.getTagName(), 1, Integer::sum) - 1;
            walk(k, path + "/" + k.getTagName() + "[" + n + "]", out);
        }
    }

    @Test
    void meSportToTestBook_exactMatchWithStudio() throws Exception {
        String sport = res("ME_SPORT.xml");
        String studioBook = res("TEST_BOOK.xml");

        List<Map<String, Object>> edits = Arrays.asList(
                mapOf("action", "move", "fromIndex", 1, "direction", "up"),
                mapOf("action", "set_member_selection", "index", 2, "dimension", "Custom2",
                        "members", Arrays.asList("PSP", "TOTC2")),
                mapOf("action", "remove", "index", 10)
        );
        BookEditor.Result r = BookEditor.apply(sport, edits);
        assertEquals(3, r.notes.size());
        assertEquals(2, r.checks.size());
        for (Map<String, Object> c : r.checks) {
            assertEquals(Boolean.TRUE, c.get("ok"), String.valueOf(c));
        }

        List<String> mine = canonBook(r.xml);
        List<String> studio = canonBook(studioBook);
        List<String> diffs = new ArrayList<>();
        int max = Math.max(mine.size(), studio.size());
        for (int i = 0; i < max; i++) {
            String a = i < mine.size() ? mine.get(i) : "<missing>";
            String b = i < studio.size() ? studio.get(i) : "<missing>";
            if (!a.equals(b)) {
                diffs.add("line " + i + "\n  mine  : " + a + "\n  studio: " + b);
            }
        }
        assertTrue(diffs.isEmpty(), diffs.size() + " element(s) differ from Studio:\n"
                + String.join("\n", diffs.subList(0, Math.min(10, diffs.size()))));
    }

    @Test
    void addChapter_updatesRefCountsAndInsertsAtPosition() throws Exception {
        String sport = res("ME_SPORT.xml");
        List<Map<String, Object>> edits = Collections.singletonList(
                mapOf("action", "add", "report", "/Monthly Comparatives/MONCOMP_NI_JG",
                        "position", 0,
                        "selections", mapOf("Entity", "US"))
        );
        BookEditor.Result r = BookEditor.apply(sport, edits);
        assertTrue(r.touchedReports.contains("/Monthly Comparatives/MONCOMP_NI_JG"));
        // Year was 26 -> now 27; Entity was 18 -> stays 18 because overridden to US
        assertTrue(r.xml.contains("NAME=\"Year\" REF_COUNT=\"27\""), r.xml);
        assertTrue(r.xml.contains("NAME=\"Entity\" REF_COUNT=\"18\""), r.xml);
    }

    @Test
    void swapReport_replacesAllMatchingChapters() throws Exception {
        String sport = res("ME_SPORT.xml");
        List<Map<String, Object>> edits = Collections.singletonList(
                mapOf("action", "swap_report",
                        "fromReport", "/Monthly Comparatives/MONCOMP_BC",
                        "toReport", "/Monthly Comparatives/MONCOMP_BC_linked")
        );
        BookEditor.Result r = BookEditor.apply(sport, edits);
        assertTrue(r.touchedReports.contains("/Monthly Comparatives/MONCOMP_BC_linked"));
        assertTrue(r.notes.get(0).contains("Swapped 6 chapter(s)"), r.notes.get(0));
        assertTrue(!r.xml.contains("\"/Monthly Comparatives/MONCOMP_BC\""));
    }

    @Test
    void setMemberSelection_toBookPointOfView_incrementsRefCount() throws Exception {
        String sport = res("ME_SPORT.xml");
        List<Map<String, Object>> edits = Collections.singletonList(
                mapOf("action", "set_member_selection", "index", 0, "dimension", "Custom2",
                        "members", Collections.singletonList("Book Point of View"))
        );
        BookEditor.Result r = BookEditor.apply(sport, edits);
        assertTrue(r.xml.contains("NAME=\"Custom2\" REF_COUNT=\"1\""), r.xml);
    }

    @Test
    void invalidRequests_areRefused() throws Exception {
        String sport = res("ME_SPORT.xml");
        // Ambiguous report name without index
        assertThrows(ApiException.class, () -> BookEditor.apply(sport,
                Collections.singletonList(mapOf("action", "remove", "report", "MONCOMP_BC"))));
        // Out of range index
        assertThrows(ApiException.class, () -> BookEditor.apply(sport,
                Collections.singletonList(mapOf("action", "move", "fromIndex", 99, "toIndex", 0))));
        // Unknown action
        assertThrows(ApiException.class, () -> BookEditor.apply(sport,
                Collections.singletonList(mapOf("action", "explode"))));
        // Non-book XML
        assertThrows(ApiException.class, () -> BookEditor.apply("<DESIGN DESIGNNAME=\"x\"/>",
                Collections.singletonList(mapOf("action", "remove", "index", 0))));
    }
}
