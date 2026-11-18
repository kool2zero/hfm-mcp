package com.hfmmcp.daemon.service;

import com.hfmmcp.daemon.http.ApiException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Row-axis "insert a row template at position 0, keep some of the report's own rows" renumbering,
 * modelled on what Financial Reporting Studio saves. See docs/axis-link-renumbering.md for the
 * evidence behind each rule (one verified pair so far: MONCOMP_DC).
 *
 * <p>Rules (E = evidence):
 * <ol>
 *   <li>R1 SEGMENTSET link tag in SEGMENTSETS, dropped GRIDSEGMENTs (E: DC pair, exact).</li>
 *   <li>R2 GRIDOBJECT ROW_COUNT = rendered rows of the kept own segments, template rows not counted
 *       (E: DC pair, 41 to 14).</li>
 *   <li>R3 ROW_PROP / STYLE_IDX: one entry per own row, renumbered 0..n-1 (E: DC pair). The source
 *       row of each entry is the original row of the kept segment, EXCEPT the first
 *       {@code studio_head_rows} entries, which Studio fills from original rows 0..h-1 (E: DC
 *       pair only, h=3, reason unknown; default 0 = follow the row).</li>
 *   <li>R4 CELL_STYLE / COND_FORMAT: only entries reachable from the kept STYLE_IDX rows (closure
 *       over the COND_FORMAT and COMP_CONDITION STYLE_INDEX links) survive, renumbered in original
 *       order (E: DC pair, 124 to 108 styles and 23 to 22 formats, exact). Template styles are NOT
 *       merged in.</li>
 *   <li>R5 GRIDCELL ROW of kept rows renumbered to the new own row; ROW -1/-2 unchanged (E: DC).</li>
 *   <li>R6 every row reference in a DERIVEDEXPRESSION ([n] and [Col,n]) is shifted by the template
 *       segment count, whether or not the target row survives (E: DC pair, +27; this is also what
 *       leaves the references dangling).</li>
 * </ol>
 */
final class AxisLinkRenumberer {

    private AxisLinkRenumberer() {
    }

    static final class Options {
        String templateName;
        String axisId = "2";
        String formatSegment;                 // default: position - 1 when position > 0, else template segment count
        int position = 0;
        Set<String> keepIds = new HashSet<>();
        Map<String, Integer> segmentRows = new LinkedHashMap<>();
        int studioHeadRows = 0;
        boolean strictRefs = false;
        boolean studioResave = true;          // R7
        boolean numericHeadingCol = true;
        boolean studioInsertBeforeDeleteShift = false;
    }

    static final class Result {
        String xml;
        final List<String> notes = new ArrayList<>();
        final List<Map<String, Object>> checks = new ArrayList<>();
        final List<String> danglingRefs = new ArrayList<>();
    }

    private static final Pattern REF_ROW = Pattern.compile("\\[(\\d+)\\]");
    private static final Pattern REF_COLROW = Pattern.compile("\\[([A-Za-z]+)\\s*,\\s*(\\d+)\\]");

    static Result link(String xml, String templateXml, Options o) {
        if (o.position < 0) {
            throw bad("renumber supports non-negative POSITION only.");
        }
        if (o.keepIds.isEmpty()) {
            throw bad("renumber needs keep_segment_ids (the report's own rows to keep).");
        }
        Document doc = parse(xml);
        Document tdoc = parse(templateXml);

        Element root = tdoc.getDocumentElement();
        if (!"SEGMENTSET".equals(root.getTagName())) {
            throw bad("Template is not a row/column template (root element " + root.getTagName() + ").");
        }
        int tSegs = 0;
        for (Element c : children(root, "GRIDSEGMENT")) {
            tSegs++;
        }
        int tStyleRows = children(root, "STYLE_IDX").size();
        if (tSegs == 0 || tSegs != tStyleRows) {
            throw bad("Template has " + tSegs + " segments but " + tStyleRows + " STYLE_IDX rows; templates "
                    + "whose segments expand to several rows are not supported (no verified example).");
        }

        NodeList grids = doc.getElementsByTagName("GRIDOBJECT");
        List<Element> own = new ArrayList<>();      // linked/object-link grids (e.g. header templates) have no axes
        for (int i = 0; i < grids.getLength(); i++) {
            Element g = (Element) grids.item(i);
            if (!children(g, "GRIDAXIS").isEmpty()) {
                own.add(g);
            }
        }
        if (own.size() != 1) {
            throw bad("Expected exactly one GRIDOBJECT with axes in the report, found " + own.size() + ".");
        }
        Element grid = own.get(0);
        Element rowAxis = axis(grid, "Row");
        if (rowAxis == null) {
            throw bad("Report has no Row axis.");
        }
        Element sets = firstChild(grid, "SEGMENTSETS");
        if (sets != null) {
            for (Element ss : children(sets, "SEGMENTSET")) {
                if (o.axisId.equals(ss.getAttribute("AXIS"))) {
                    throw bad("Row axis already has a linked SEGMENTSET; renumber supports an unlinked axis only.");
                }
            }
        }

        // ---- segment -> row map
        List<Element> segs = children(rowAxis, "GRIDSEGMENT");
        List<Integer> rowsPer = new ArrayList<>();
        int total = 0;
        Set<String> ids = new HashSet<>();
        for (Element sg : segs) {
            String id = sg.getAttribute("ID");
            ids.add(id);
            Integer n = o.segmentRows.get(id);
            rowsPer.add(n == null ? 1 : n);
            total += n == null ? 1 : n;
        }
        for (String k : o.keepIds) {
            if (!ids.contains(k)) {
                throw bad("keep_segment_ids contains " + k + ", which is not a segment of the Row axis.");
            }
        }
        for (String k : o.segmentRows.keySet()) {
            if (!ids.contains(k)) {
                throw bad("segment_rows names " + k + ", which is not a segment of the Row axis.");
            }
        }
        int rowCount = parseInt(grid.getAttribute("ROW_COUNT"), -1);
        if (rowCount != total) {
            throw bad("The Row axis has " + segs.size() + " segments but ROW_COUNT is " + rowCount
                    + "; some segments render several rows. The rows each segment renders cannot be read from "
                    + "the XML. Pass segment_rows as 'ID:rows,ID:rows' so the rows add up to " + rowCount
                    + " (currently " + total + ").");
        }

        List<Integer> keptRows = new ArrayList<>();            // original row index, in order
        Map<Integer, Integer> newRowOf = new LinkedHashMap<>();
        Set<Integer> droppedRows = new HashSet<>();
        List<Element> dropSegs = new ArrayList<>();
        int cursor = 0;
        for (int i = 0; i < segs.size(); i++) {
            boolean keep = o.keepIds.contains(segs.get(i).getAttribute("ID"));
            for (int r = 0; r < rowsPer.get(i); r++) {
                if (keep) {
                    newRowOf.put(cursor + r, keptRows.size());
                    keptRows.add(cursor + r);
                } else {
                    droppedRows.add(cursor + r);
                }
            }
            if (!keep) {
                dropSegs.add(segs.get(i));
            }
            cursor += rowsPer.get(i);
        }
        int n = keptRows.size();
        if (o.studioHeadRows < 0 || o.studioHeadRows > n) {
            throw bad("studio_head_rows must be between 0 and " + n + ".");
        }

        // cells sitting on dropped rows would be lost
        Element matrix = firstChild(grid, "GRIDMATRIX");
        List<Element> cells = new ArrayList<>();
        if (matrix != null) {
            NodeList cl = matrix.getElementsByTagName("GRIDCELL");
            for (int i = 0; i < cl.getLength(); i++) {
                cells.add((Element) cl.item(i));
            }
        }
        for (Element c : cells) {
            int r = parseInt(c.getAttribute("ROW"), -1);
            if (r >= 0 && droppedRows.contains(r)) {
                throw bad("keep_segment_ids drops original row " + r + ", which carries GRIDCELL formula/format "
                        + "overrides (segment type 1). Keep that segment or the overrides would be lost.");
            }
        }

        // ---- R3 ROW_PROP / STYLE_IDX
        Map<Integer, Element> rp = byRow(children(grid, "ROW_PROP"));
        Map<Integer, Element> si = byRow(children(grid, "STYLE_IDX"));
        if (rp.size() != rowCount || si.size() != rowCount) {
            throw bad("Original ROW_PROP/STYLE_IDX counts (" + rp.size() + "/" + si.size()
                    + ") differ from ROW_COUNT " + rowCount + "; the report is already inconsistent.");
        }
        List<Element> newRp = new ArrayList<>();
        List<Element> newSi = new ArrayList<>();
        int boundary = Math.min(n, o.position + o.studioHeadRows);
        for (int i = 0; i < n; i++) {
            int src = i < boundary ? i : keptRows.get(i);
            Element a = (Element) rp.get(src).cloneNode(true);
            a.setAttribute("ROW", String.valueOf(i));
            newRp.add(a);
            Element b = (Element) si.get(src).cloneNode(true);
            b.setAttribute("ROW", String.valueOf(i));
            newSi.add(b);
        }

        // ---- R4 style / conditional-format garbage collection
        Map<String, Element> styles = new TreeMap<>(NUM);
        for (Element c : children(grid, "CELL_STYLE")) {
            styles.put(c.getAttribute("INDEX"), c);
        }
        Map<String, Element> cfs = new TreeMap<>(NUM);
        for (Element c : children(grid, "COND_FORMAT")) {
            cfs.put(c.getAttribute("INDEX"), c);
        }
        Set<String> keepStyle = new HashSet<>();
        for (Element b : newSi) {
            for (String s : b.getAttribute("COL_IDX").split(",")) {
                if (!styles.containsKey(s)) {
                    throw bad("STYLE_IDX row " + b.getAttribute("ROW") + " references missing CELL_STYLE " + s);
                }
                keepStyle.add(s);
            }
        }
        Set<String> keepCf = new HashSet<>();
        for (int r : keptRows) {
            Element rowSi = si.get(r);
            if (rowSi != null) {
                for (String s : rowSi.getAttribute("COL_IDX").split(",")) {
                    Element cs = styles.get(s);
                    if (cs != null) {
                        String cf = cs.getAttribute("COND_FORMAT");
                        if (!cf.isEmpty()) {
                            keepCf.add(cf);
                        }
                    }
                }
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String s : new ArrayList<>(keepStyle)) {
                String cf = styles.get(s).getAttribute("COND_FORMAT");
                if (!cf.isEmpty()) {
                    if (!cfs.containsKey(cf)) {
                        throw bad("CELL_STYLE " + s + " references missing COND_FORMAT " + cf);
                    }
                    if (keepCf.add(cf)) {
                        changed = true;
                    }
                }
            }
            for (String cf : new ArrayList<>(keepCf)) {
                NodeList cc = cfs.get(cf).getElementsByTagName("COMP_CONDITION");
                for (int i = 0; i < cc.getLength(); i++) {
                    String st = ((Element) cc.item(i)).getAttribute("STYLE_INDEX");
                    if (!styles.containsKey(st)) {
                        throw bad("COND_FORMAT " + cf + " references missing CELL_STYLE " + st);
                    }
                    if (keepStyle.add(st)) {
                        changed = true;
                    }
                }
            }
        }
        Map<String, String> smap = new LinkedHashMap<>();
        for (String k : styles.keySet()) {
            if (keepStyle.contains(k)) {
                smap.put(k, String.valueOf(smap.size()));
            }
        }
        Map<String, String> cmap = new LinkedHashMap<>();
        for (String k : cfs.keySet()) {
            if (keepCf.contains(k)) {
                cmap.put(k, String.valueOf(cmap.size()));
            }
        }
        for (Element b : newSi) {
            StringBuilder sb = new StringBuilder();
            for (String s : b.getAttribute("COL_IDX").split(",")) {
                sb.append(sb.length() == 0 ? "" : ",").append(smap.get(s));
            }
            b.setAttribute("COL_IDX", sb.toString());
        }
        for (Map.Entry<String, Element> e : styles.entrySet()) {
            Element c = e.getValue();
            if (smap.containsKey(e.getKey())) {
                c.setAttribute("INDEX", smap.get(e.getKey()));
                String cf = c.getAttribute("COND_FORMAT");
                if (!cf.isEmpty()) {
                    c.setAttribute("COND_FORMAT", cmap.get(cf));
                }
            } else {
                remove(c);
            }
        }
        for (Map.Entry<String, Element> e : cfs.entrySet()) {
            Element c = e.getValue();
            if (cmap.containsKey(e.getKey())) {
                c.setAttribute("INDEX", cmap.get(e.getKey()));
                NodeList cc = c.getElementsByTagName("COMP_CONDITION");
                for (int i = 0; i < cc.getLength(); i++) {
                    Element x = (Element) cc.item(i);
                    x.setAttribute("STYLE_INDEX", smap.get(x.getAttribute("STYLE_INDEX")));
                }
            } else {
                remove(c);
            }
        }
        replaceGroup(grid, "ROW_PROP", newRp);
        replaceGroup(grid, "STYLE_IDX", newSi);

        // ---- R5 cells
        for (Element c : cells) {
            int r = parseInt(c.getAttribute("ROW"), -1);
            if (r >= 0) {
                c.setAttribute("ROW", String.valueOf(newRowOf.get(r)));
            }
        }

        // ---- R6 formula references
        int refsShifted = 0;
        NodeList des = grid.getElementsByTagName("DERIVEDEXPRESSION");
        List<Integer> originalTargets = new ArrayList<>();
        int formulaShift = o.studioInsertBeforeDeleteShift ? tSegs : (tSegs - dropSegs.size());
        for (int i = 0; i < des.getLength(); i++) {
            Element d = (Element) des.item(i);
            String v = d.getAttribute("VALUE");
            if (v.isEmpty()) {
                continue;
            }
            StringBuffer sb = new StringBuffer();
            Matcher m = REF_ROW.matcher(v);
            while (m.find()) {
                int t = Integer.parseInt(m.group(1));
                originalTargets.add(t);
                int shift = (t < o.position) ? 0 : formulaShift;
                if (shift != 0) {
                    refsShifted++;
                }
                m.appendReplacement(sb, "[" + (t + shift) + "]");
            }
            m.appendTail(sb);
            StringBuffer sb2 = new StringBuffer();
            Matcher m2 = REF_COLROW.matcher(sb.toString());
            // note: the column-row form is applied to the original text, not the already shifted one
            Matcher m3 = REF_COLROW.matcher(v);
            while (m3.find()) {
                originalTargets.add(Integer.parseInt(m3.group(2)));
            }
            while (m2.find()) {
                int t = Integer.parseInt(m2.group(2));
                int shift = (t < o.position) ? 0 : formulaShift;
                if (shift != 0) {
                    refsShifted++;
                }
                m2.appendReplacement(sb2, Matcher.quoteReplacement("[" + m2.group(1) + "," + (t + shift) + "]"));
            }
            m2.appendTail(sb2);
            String nv = sb2.toString();
            if (!nv.equals(v)) {
                d.setAttribute("VALUE", nv);
            }
        }

        // ---- R1 row axis segments and SEGMENTSET
        for (Element sg : dropSegs) {
            remove(sg);
        }
        Element tag = doc.createElement("SEGMENTSET");
        tag.setAttribute("AXIS", o.axisId);
        tag.setAttribute("FORMAT_PRECEDENCE", "1");
        String defaultFormatSeg = o.position > 0 ? String.valueOf(o.position - 1) : String.valueOf(tSegs);
        tag.setAttribute("FORMAT_SEGMENT", o.formatSegment != null ? o.formatSegment : defaultFormatSeg);
        tag.setAttribute("FORMULA_PRECEDENCE", "1");
        tag.setAttribute("OBJECT_LINKNAME", o.templateName);
        tag.setAttribute("POSITION", String.valueOf(o.position));
        if (sets == null) {
            sets = doc.createElement("SEGMENTSETS");
            sets.appendChild(doc.createTextNode("\n"));
            sets.appendChild(tag);
            sets.appendChild(doc.createTextNode("\n"));
            grid.insertBefore(sets, firstAxisElement(grid));
            grid.insertBefore(doc.createTextNode("\n"), firstAxisElement(grid));
        } else {
            sets.appendChild(tag);
            sets.appendChild(doc.createTextNode("\n"));
        }

        // ---- R2
        grid.setAttribute("ROW_COUNT", String.valueOf(n));

        // ---- R7 what Studio also changes when it re-saves this report (one example, unrelated to the link)
        if (o.studioResave) {
            if (!grid.hasAttribute("EXPAND_PLACEMENT")) {
                grid.setAttribute("EXPAND_PLACEMENT", "0");
            }
            NodeList mex = grid.getElementsByTagName("METADATAEXPRESSION");
            for (int i = 0; i < mex.getLength(); i++) {
                Element me = (Element) mex.item(i);
                String h = me.getAttribute("HEADING");
                if (!h.isEmpty()) {
                    String nh = studioHeading(h, o.numericHeadingCol);
                    if (!nh.equals(h)) {
                        me.setAttribute("HEADING", nh);
                    }
                }
            }
        }

        // ---- self-checks
        Result res = new Result();
        int totalRuntimeSegments = tSegs + (segs.size() - dropSegs.size());
        runChecks(res, grid, n, formulaShift, totalRuntimeSegments, o.position, originalTargets,
                newRowOf.keySet(), o.strictRefs, o.studioInsertBeforeDeleteShift);
        for (String d : res.danglingRefs) {
            res.notes.add("Dangling formula reference: " + d);
        }
        res.notes.add("Row axis now links to " + o.templateName + ": " + dropSegs.size() + " segment(s) removed, "
                + (segs.size() - dropSegs.size()) + " kept; " + n + " own rows (ROW_COUNT), template adds "
                + tSegs + " segments; " + refsShifted + " row reference(s) shifted by " + formulaShift + "; "
                + smap.size() + " cell styles and " + cmap.size() + " conditional formats retained.");
        res.xml = serialize(doc);
        return res;
    }

    private static final Pattern GET_HEADING = Pattern.compile(
            "GetHeading\\(\\s*\"?([^\",()]+?)\"?\\s*,\\s*(\\d+)\\s*,\\s*([A-Za-z]+)\\s*,\\s*(\\d+)\\s*\\)");

    /** GetHeading("Grid1",1,F,3) becomes GetHeading(Grid1, 1, 6, 3) or GetHeading(Grid1, 1, F, 3), as Studio writes it. */
    static String studioHeading(String h, boolean numericCol) {
        Matcher m = GET_HEADING.matcher(h);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String colStr;
            if (numericCol) {
                int col = 0;
                for (char ch : m.group(3).toUpperCase(java.util.Locale.ROOT).toCharArray()) {
                    col = col * 26 + (ch - 'A' + 1);
                }
                colStr = String.valueOf(col);
            } else {
                colStr = m.group(3);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement("GetHeading(" + m.group(1) + ", " + m.group(2) + ", "
                    + colStr + ", " + m.group(4) + ")"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------ self-checks

    private static void runChecks(Result res, Element grid, int n, int formulaShift, int totalRuntimeSegments,
                                  int position, List<Integer> originalTargets,
                                  Set<Integer> keptOriginalRows, boolean strictRefs,
                                  boolean studioInsertBeforeDeleteShift) {
        List<String> failures = new ArrayList<>();
        // 1 ROW_PROP / STYLE_IDX counts
        int rpN = children(grid, "ROW_PROP").size();
        int siN = children(grid, "STYLE_IDX").size();
        boolean contiguous = contiguousRows(children(grid, "ROW_PROP")) && contiguousRows(children(grid, "STYLE_IDX"));
        boolean counts = rpN == n && siN == n && contiguous
                && parseInt(grid.getAttribute("ROW_COUNT"), -1) == n;
        add(res, failures, "row_count_matches_row_props_and_style_idx", counts,
                "ROW_COUNT=" + grid.getAttribute("ROW_COUNT") + ", ROW_PROP=" + rpN + ", STYLE_IDX=" + siN);
        // 2 GRIDCELL ROW range
        Element colAxis = axis(grid, "Column");
        int maxHeaderRows = colAxis == null ? 10 : Math.max(2, children(colAxis, "GRIDLAYER").size());
        boolean cellsOk = true;
        String badCell = "";
        Element matrix = firstChild(grid, "GRIDMATRIX");
        if (matrix != null) {
            NodeList cl = matrix.getElementsByTagName("GRIDCELL");
            for (int i = 0; i < cl.getLength(); i++) {
                int r = parseInt(((Element) cl.item(i)).getAttribute("ROW"), Integer.MIN_VALUE);
                if (r < -maxHeaderRows || r >= n) {
                    cellsOk = false;
                    badCell = String.valueOf(r);
                }
            }
        }
        add(res, failures, "gridcell_rows_in_range", cellsOk,
                cellsOk ? "all GRIDCELL ROW values within -" + maxHeaderRows + ".." + (n - 1)
                        : "GRIDCELL ROW " + badCell + " outside -" + maxHeaderRows + ".." + (n - 1));
        // 3 COND_FORMAT / CELL_STYLE contiguity and links
        boolean cfContig = contiguousIndex(children(grid, "COND_FORMAT"));
        boolean stContig = contiguousIndex(children(grid, "CELL_STYLE"));
        int cfN = children(grid, "COND_FORMAT").size();
        int stN = children(grid, "CELL_STYLE").size();
        boolean links = true;
        for (Element c : children(grid, "CELL_STYLE")) {
            String cf = c.getAttribute("COND_FORMAT");
            if (!cf.isEmpty() && parseInt(cf, -1) >= cfN) {
                links = false;
            }
        }
        NodeList cc = grid.getElementsByTagName("COMP_CONDITION");
        for (int i = 0; i < cc.getLength(); i++) {
            if (parseInt(((Element) cc.item(i)).getAttribute("STYLE_INDEX"), -1) >= stN) {
                links = false;
            }
        }
        for (Element b : children(grid, "STYLE_IDX")) {
            for (String s : b.getAttribute("COL_IDX").split(",")) {
                if (parseInt(s, -1) < 0 || parseInt(s, -1) >= stN) {
                    links = false;
                }
            }
        }
        add(res, failures, "cond_format_indexes_contiguous", cfContig && stContig && links,
                "COND_FORMAT=" + cfN + " contiguous=" + cfContig + ", CELL_STYLE=" + stN + " contiguous=" + stContig
                        + ", links valid=" + links);
        // 4 formula references. At runtime FR expands the linked template at POSITION before
        // evaluating DERIVEDEXPRESSION formulas, so 1-based [row] references resolve against the
        // expanded segment count (totalRuntimeSegments). When studioInsertBeforeDeleteShift is
        // enabled, Studio's insert-before-delete shift (+tSegs) is checked against keptOriginalRows.
        int combined = formulaShift + n;
        for (Integer t : originalTargets) {
            int shift = (t < position) ? 0 : formulaShift;
            int now = t + shift;
            if (studioInsertBeforeDeleteShift) {
                if (!keptOriginalRows.contains(t)) {
                    res.danglingRefs.add("original row " + t + " (now [" + now + "]) was dropped");
                } else if (now >= combined) {
                    res.danglingRefs.add("[" + now + "] is past the combined row count " + combined);
                }
            } else {
                if (now < 1 || now > totalRuntimeSegments) {
                    res.danglingRefs.add("[" + now + "] is outside the expanded runtime row range 1.."
                            + totalRuntimeSegments);
                }
            }
        }
        boolean refsOk = res.danglingRefs.isEmpty();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("check", "formula_references_point_to_existing_rows");
        m.put("ok", refsOk);
        m.put("enforced", strictRefs);
        m.put("detail", refsOk ? "all references resolve" : res.danglingRefs.size() + " reference(s): "
                + String.join("; ", res.danglingRefs));
        res.checks.add(m);
        if (!refsOk && strictRefs) {
            failures.add("formula_references_point_to_existing_rows: " + m.get("detail"));
        }
        if (!failures.isEmpty()) {
            throw bad("axisLinks renumber self-check failed, nothing was produced: " + String.join(" | ", failures));
        }
    }

    private static void add(Result res, List<String> failures, String name, boolean ok, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("check", name);
        m.put("ok", ok);
        m.put("enforced", true);
        m.put("detail", detail);
        res.checks.add(m);
        if (!ok) {
            failures.add(name + ": " + detail);
        }
    }

    private static boolean contiguousRows(List<Element> els) {
        for (int i = 0; i < els.size(); i++) {
            if (parseInt(els.get(i).getAttribute("ROW"), -1) != i) {
                return false;
            }
        }
        return true;
    }

    private static boolean contiguousIndex(List<Element> els) {
        for (int i = 0; i < els.size(); i++) {
            if (parseInt(els.get(i).getAttribute("INDEX"), -1) != i) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ DOM helpers

    private static final java.util.Comparator<String> NUM = new java.util.Comparator<String>() {
        @Override
        public int compare(String a, String b) {
            return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        }
    };

    private static Map<Integer, Element> byRow(List<Element> els) {
        Map<Integer, Element> m = new TreeMap<>();
        for (Element e : els) {
            m.put(parseInt(e.getAttribute("ROW"), -1), e);
        }
        return m;
    }

    private static Element axis(Element grid, String name) {
        for (Element a : children(grid, "GRIDAXIS")) {
            if (name.equalsIgnoreCase(a.getAttribute("AXISNAME"))) {
                return a;
            }
        }
        return null;
    }

    private static Element firstAxisElement(Element grid) {
        return children(grid, "GRIDAXIS").get(0);
    }

    static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element && tag.equals(((Element) c).getTagName())) {
                out.add((Element) c);
            }
        }
        return out;
    }

    private static Element firstChild(Element parent, String tag) {
        List<Element> l = children(parent, tag);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Removes an element together with the whitespace-only text node that follows it. */
    private static void remove(Element e) {
        Node parent = e.getParentNode();
        Node next = e.getNextSibling();
        parent.removeChild(e);
        if (next != null && next.getNodeType() == Node.TEXT_NODE && next.getNodeValue().trim().isEmpty()) {
            parent.removeChild(next);
        }
    }

    /** Replaces all children called {@code tag} by {@code fresh}, placed where the first one was. */
    private static void replaceGroup(Element parent, String tag, List<Element> fresh) {
        List<Element> old = children(parent, tag);
        Node anchor = old.isEmpty() ? null : old.get(0);
        // find the node after the whole group's first element, after removing the old ones
        Node marker = parent.getOwnerDocument().createTextNode("");
        parent.insertBefore(marker, anchor);
        for (Element e : old) {
            remove(e);
        }
        for (Element e : fresh) {
            parent.insertBefore(e, marker);
            parent.insertBefore(parent.getOwnerDocument().createTextNode("\n"), marker);
        }
        parent.removeChild(marker);
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setCoalescing(false);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(new InputSource(new StringReader(xml.startsWith("\uFEFF") ? xml.substring(1) : xml)));
        } catch (Exception ex) {
            throw bad("Report XML could not be parsed for renumbering: " + ex.getMessage());
        }
    }

    private static String serialize(Document doc) {
        try {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.INDENT, "no");
            t.setOutputProperty(OutputKeys.CDATA_SECTION_ELEMENTS, "DESIGN_RTF");
            doc.setXmlStandalone(false);
            StringWriter w = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(w));
            return w.toString().replace("\r\n", "\n").replace("\n", "\r\n");
        } catch (Exception ex) {
            throw bad("Could not serialize renumbered XML: " + ex.getMessage());
        }
    }

    private static int parseInt(String s, int dflt) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return dflt;
        }
    }

    private static ApiException bad(String msg) {
        return ApiException.badRequest("bad_request", msg);
    }
}
