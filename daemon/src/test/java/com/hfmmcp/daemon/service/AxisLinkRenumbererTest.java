package com.hfmmcp.daemon.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hfmmcp.daemon.http.ApiException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Golden tests for the Row-axis link renumbering. Ground truth = what Financial Reporting Studio saved
 * (fixtures under src/test/resources/axislink). Comparison is per element (path + sorted attributes),
 * ignoring whitespace, CRLF and the DESIGNNAME / REPORT_DESCRIPTION attributes.
 */
class AxisLinkRenumbererTest {

    private static String res(String name) throws Exception {
        try (InputStream in = AxisLinkRenumbererTest.class.getResourceAsStream("/axislink/" + name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static AxisLinkRenumberer.Options dcOptions(int headRows) {
        AxisLinkRenumberer.Options o = new AxisLinkRenumberer.Options();
        o.templateName = "/Users/test";
        o.keepIds.addAll(Arrays.asList("34,52,7,57,61,58,59,56,64,8,9".split(",")));
        o.segmentRows.put("8", 4);          // segment ID 8 renders 4 rows (41 rows = 38 segments + 3)
        o.studioHeadRows = headRows;
        return o;
    }

    // -------------------------------------------------------------------- canonical per-element diff

    static List<String> canon(String xml) throws Exception {
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
        if ("DESIGN".equals(e.getTagName())) {
            attrs.remove("DESIGNNAME");
            attrs.remove("REPORT_DESCRIPTION");
        }
        StringBuilder text = new StringBuilder();
        Map<String, Integer> seen = new TreeMap<>();
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
        for (Element k : kids) {
            int n = seen.merge(k.getTagName(), 1, Integer::sum) - 1;
            walk(k, path + "/" + k.getTagName() + "[" + n + "]", out);
        }
    }

    static List<String> diff(List<String> mine, List<String> studio) {
        List<String> d = new ArrayList<>();
        int n = Math.max(mine.size(), studio.size());
        for (int i = 0; i < n; i++) {
            String a = i < mine.size() ? mine.get(i) : "<missing>";
            String b = i < studio.size() ? studio.get(i) : "<missing>";
            if (!a.equals(b)) {
                d.add("line " + i + "\n   mine  : " + a + "\n   studio: " + b);
            }
        }
        return d;
    }

    private static void assertSameAsStudio(String actualXml, String studioXml) throws Exception {
        List<String> d = diff(canon(actualXml), canon(studioXml));
        assertTrue(d.isEmpty(), d.size() + " element(s) differ from the Studio file; first ones:\n"
                + String.join("\n", d.subList(0, Math.min(25, d.size()))));
    }

    // ------------------------------------------------------------------------------- tests

    @Test
    void dcPair_exactMatchWithStudio() throws Exception {
        AxisLinkRenumberer.Result r = AxisLinkRenumberer.link(res("MONCOMP_DC.original.xml"),
                res("DC_rows_template.xml"), dcOptions(3));
        assertSameAsStudio(r.xml, res("MONCOMP_DC_RF_V2.studio_linked.xml"));
    }

    /** Without the unexplained head rows only the style-carrying elements differ (documents the open item). */
    @Test
    void dcPair_followRowMappingDiffersOnlyInRowPropsAndStyles() throws Exception {
        AxisLinkRenumberer.Result r = AxisLinkRenumberer.link(res("MONCOMP_DC.original.xml"),
                res("DC_rows_template.xml"), dcOptions(0));
        List<String> mine = withoutStyleElements(canon(r.xml));
        List<String> studio = withoutStyleElements(canon(res("MONCOMP_DC_RF_V2.studio_linked.xml")));
        List<String> full = diff(canon(r.xml), canon(res("MONCOMP_DC_RF_V2.studio_linked.xml")));
        assertFalse(full.isEmpty(), "the open item should still show up as a difference");
        List<String> d = diff(mine, studio);
        assertTrue(d.isEmpty(), "differences outside ROW_PROP/STYLE_IDX/CELL_STYLE/COND_FORMAT:\n"
                + String.join("\n", d.subList(0, Math.min(10, d.size()))));
    }

    private static List<String> withoutStyleElements(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            String path = l.substring(0, l.indexOf(' '));
            if (!(path.contains("/ROW_PROP[") || path.contains("/STYLE_IDX[") || path.contains("/CELL_STYLE[")
                    || path.contains("/COND_FORMAT["))) {
                out.add(l);
            }
        }
        return out;
    }

    @Test
    void dcPair_checksReported_andDanglingReferencesFlagged() throws Exception {
        AxisLinkRenumberer.Result r = AxisLinkRenumberer.link(res("MONCOMP_DC.original.xml"),
                res("DC_rows_template.xml"), dcOptions(3));
        assertEquals(4, r.checks.size());
        for (Map<String, Object> c : r.checks) {
            if (!"formula_references_point_to_existing_rows".equals(c.get("check"))) {
                assertEquals(Boolean.TRUE, c.get("ok"), String.valueOf(c));
            }
        }
        // Studio's own file keeps [A,1]->[A,28], [27]->[54], [34]->[61]; the checker reports them.
        assertFalse(r.danglingRefs.isEmpty());
    }

    @Test
    void strictRefs_failsTheCallOnDanglingReferences() throws Exception {
        AxisLinkRenumberer.Options o = dcOptions(3);
        o.strictRefs = true;
        ApiException e = assertThrows(ApiException.class, () -> AxisLinkRenumberer.link(
                res("MONCOMP_DC.original.xml"), res("DC_rows_template.xml"), o));
        assertTrue(e.getMessage().contains("formula_references_point_to_existing_rows"), e.getMessage());
    }

    @Test
    void wrongKeepList_droppingRowWithCellOverrides_isRefused() throws Exception {
        AxisLinkRenumberer.Options o = dcOptions(3);
        o.keepIds.remove("34");              // segment 34 carries the GRIDCELL formulas ([27])
        ApiException e = assertThrows(ApiException.class, () -> AxisLinkRenumberer.link(
                res("MONCOMP_DC.original.xml"), res("DC_rows_template.xml"), o));
        assertTrue(e.getMessage().contains("GRIDCELL"), e.getMessage());
    }

    @Test
    void wrongKeepList_unknownSegmentId_isRefused() throws Exception {
        AxisLinkRenumberer.Options o = dcOptions(3);
        o.keepIds.add("9999");
        ApiException e = assertThrows(ApiException.class, () -> AxisLinkRenumberer.link(
                res("MONCOMP_DC.original.xml"), res("DC_rows_template.xml"), o));
        assertTrue(e.getMessage().contains("9999"), e.getMessage());
    }

    @Test
    void rowsNotAddingUp_isRefusedWithSegmentRowsHint() throws Exception {
        AxisLinkRenumberer.Options o = dcOptions(3);
        o.segmentRows.clear();
        ApiException e = assertThrows(ApiException.class, () -> AxisLinkRenumberer.link(
                res("MONCOMP_DC.original.xml"), res("DC_rows_template.xml"), o));
        assertTrue(e.getMessage().contains("segment_rows"), e.getMessage());
    }

    private static AxisLinkRenumberer.Options niOptions(int headRows) {
        AxisLinkRenumberer.Options o = new AxisLinkRenumberer.Options();
        o.templateName = "/Users/test2";
        o.position = 5;
        o.keepIds.addAll(Arrays.asList("0,1,2,3,4,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,52,53,54,55,38,39,40,41,42,43,44,45,46,47".split(",")));
        o.studioHeadRows = headRows;
        o.numericHeadingCol = false;
        o.segmentRows.put("21", 2);
        o.segmentRows.put("22", 2);
        o.segmentRows.put("23", 2);
        return o;
    }

    @Test
    void niPair_exactMatchWithStudio() throws Exception {
        AxisLinkRenumberer.Result r = AxisLinkRenumberer.link(res("MONCOMP_NI_JG.xml"),
                res("test2_rows_template.xml"), niOptions(3));
        assertSameAsStudio(r.xml, res("MONCOMP_NI_JG_LINKED.xml"));
    }

    @Test
    void niPair_followRowMappingDiffersOnlyInRowPropsAndStyles() throws Exception {
        AxisLinkRenumberer.Result r = AxisLinkRenumberer.link(res("MONCOMP_NI_JG.xml"),
                res("test2_rows_template.xml"), niOptions(0));
        List<String> mine = withoutStyleElements(canon(r.xml));
        List<String> studio = withoutStyleElements(canon(res("MONCOMP_NI_JG_LINKED.xml")));
        List<String> full = diff(canon(r.xml), canon(res("MONCOMP_NI_JG_LINKED.xml")));
        assertFalse(full.isEmpty(), "the boundary row item should still show up as a difference");
        List<String> d = diff(mine, studio);
        assertTrue(d.isEmpty(), "differences outside ROW_PROP/STYLE_IDX/CELL_STYLE/COND_FORMAT:\n"
                + String.join("\n", d.subList(0, Math.min(10, d.size()))));
    }

    private static AxisLinkRenumberer.Options bcOptions(int headRows) {
        AxisLinkRenumberer.Options o = new AxisLinkRenumberer.Options();
        o.templateName = "/Monthly Comparatives/test3";
        o.position = 21;
        o.keepIds.addAll(Arrays.asList("0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,26,27,28,29,30,31,32,33,34".split(",")));
        o.studioHeadRows = headRows;
        o.segmentRows.put("8", 4);
        o.studioResave = false;
        return o;
    }

    @Test
    void bcPair_exactMatchWithStudio() throws Exception {
        AxisLinkRenumberer.Result r = AxisLinkRenumberer.link(res("MONCOMP_BC.xml"),
                res("test3_rows_template.xml"), bcOptions(3));
        assertSameAsStudio(r.xml, res("MONCOMP_BC_linked.xml"));
    }

    @Test
    void bcPair_exactMatchWithStudio_headRowsZero() throws Exception {
        AxisLinkRenumberer.Result r = AxisLinkRenumberer.link(res("MONCOMP_BC.xml"),
                res("test3_rows_template.xml"), bcOptions(0));
        assertSameAsStudio(r.xml, res("MONCOMP_BC_linked.xml"));
    }

    @Test
    void negativePosition_isRefused() throws Exception {
        AxisLinkRenumberer.Options o = dcOptions(3);
        o.position = -1;
        ApiException e = assertThrows(ApiException.class, () -> AxisLinkRenumberer.link(
                res("MONCOMP_DC.original.xml"), res("DC_rows_template.xml"), o));
        assertTrue(e.getMessage().contains("non-negative POSITION"), e.getMessage());
    }
}
