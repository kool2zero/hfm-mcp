package com.hfmmcp.daemon.service;

import com.hfmmcp.daemon.http.ApiException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * Edits Financial Reporting Book ({@code <BOOK>}) XML definitions while preserving Studio's
 * structure and invariants:
 * <ul>
 *   <li>Chapter ordering, insertion, removal, and report reference swapping ({@code <CHAPTER>}).</li>
 *   <li>Chapter member selections ({@code <CHAPTER_MEMSELS>/<DIMENSION MEM_SEL="...">}),
 *       supporting single members, multi-member {@code List} queries, and {@code Book Point of View}.</li>
 *   <li>Automatic recalculation of {@code REF_COUNT} on {@code <BOOK_POV>/<DATASOURCE>/<DIMENSION>}
 *       for all dimensions inheriting {@code Book Point of View}.</li>
 * </ul>
 */
final class BookEditor {

    static final class Result {
        String xml;
        final List<String> notes = new ArrayList<>();
        final List<Map<String, Object>> checks = new ArrayList<>();
        final Set<String> touchedReports = new LinkedHashSet<>();
    }

    private BookEditor() {
    }

    static Result apply(String xml, List<Map<String, Object>> edits) {
        if (xml == null || xml.trim().isEmpty()) {
            throw bad("Book XML is empty.");
        }
        if (edits == null || edits.isEmpty()) {
            throw bad("bookEdits must contain at least one edit.");
        }

        Document doc = parse(xml);
        Element book = doc.getDocumentElement();
        if (!"BOOK".equals(book.getTagName())) {
            throw bad("bookEdits apply to FR_BOOK (<BOOK>) definitions only; got <" + book.getTagName() + ">.");
        }

        Element bookPov = firstChild(book, "BOOK_POV");
        if (bookPov == null) {
            throw bad("Book XML has no <BOOK_POV> element.");
        }
        Element povDs = firstChild(bookPov, "DATASOURCE");
        if (povDs == null) {
            throw bad("Book XML <BOOK_POV> has no <DATASOURCE> element.");
        }
        String dsId = povDs.getAttribute("DATASOURCE_ID");
        Element povEl = firstChild(povDs, "POV");
        List<String> bookDimensions = readBookDimensions(povEl, book);

        Result res = new Result();
        for (int i = 0; i < edits.size(); i++) {
            Map<String, Object> edit = edits.get(i);
            if (edit == null || edit.isEmpty()) {
                throw bad("bookEdits[" + i + "] is empty.");
            }
            String action = str(edit, "action");
            if (action == null) {
                action = str(edit, "op");
            }
            if (action == null) {
                throw bad("bookEdits[" + i + "] requires 'action' (move, remove, add, swap_report, set_member_selection).");
            }
            switch (action.trim().toLowerCase()) {
                case "move":
                case "reorder":
                    applyMove(book, edit, i, res);
                    break;
                case "remove":
                case "delete":
                    applyRemove(book, edit, i, res);
                    break;
                case "add":
                case "insert":
                    applyAdd(doc, book, dsId, bookDimensions, edit, i, res);
                    break;
                case "swap_report":
                case "replace_report":
                    applySwapReport(book, edit, i, res);
                    break;
                case "set_member_selection":
                case "set_selection":
                    applySetMemberSelection(doc, book, dsId, edit, i, res);
                    break;
                default:
                    throw bad("bookEdits[" + i + "]: unsupported action '" + action + "'. Supported: "
                            + "move, remove, add, swap_report, set_member_selection.");
            }
        }

        recalculateRefCounts(doc, povDs, book);
        runSelfChecks(book, povDs, res);
        res.xml = serialize(doc);
        return res;
    }

    // ------------------------------------------------------------------ edit actions

    private static void applyMove(Element book, Map<String, Object> edit, int idx, Result res) {
        List<Element> chapters = children(book, "CHAPTER");
        if (chapters.isEmpty()) {
            throw bad("bookEdits[" + idx + "]: book has no chapters to move.");
        }
        int from = resolveChapterIndex(chapters, edit, "fromIndex", "report", idx);
        int to;
        Integer toObj = optInt(edit, "toIndex");
        if (toObj != null) {
            to = toObj;
        } else {
            String dir = str(edit, "direction");
            if (dir == null) {
                throw bad("bookEdits[" + idx + "]: move requires 'toIndex' or 'direction' ('up'/'down').");
            }
            int steps = optInt(edit, "steps") != null ? optInt(edit, "steps") : 1;
            if (steps < 1) {
                throw bad("bookEdits[" + idx + "]: steps must be >= 1.");
            }
            if ("up".equalsIgnoreCase(dir)) {
                to = from - steps;
            } else if ("down".equalsIgnoreCase(dir)) {
                to = from + steps;
            } else {
                throw bad("bookEdits[" + idx + "]: direction must be 'up' or 'down', got '" + dir + "'.");
            }
        }
        if (to < 0 || to >= chapters.size()) {
            throw bad("bookEdits[" + idx + "]: target index " + to + " out of range 0.." + (chapters.size() - 1));
        }
        if (from == to) {
            res.notes.add("Chapter " + from + " (" + chapters.get(from).getAttribute("REPORTNAME") + ") already at index " + to + ".");
            return;
        }
        Element moving = chapters.get(from);
        book.removeChild(moving);
        List<Element> remaining = children(book, "CHAPTER");
        if (to >= remaining.size()) {
            book.appendChild(moving);
        } else {
            book.insertBefore(moving, remaining.get(to));
        }
        res.notes.add("Moved chapter '" + moving.getAttribute("REPORTNAME") + "' from index " + from + " to " + to + ".");
    }

    private static void applyRemove(Element book, Map<String, Object> edit, int idx, Result res) {
        List<Element> chapters = children(book, "CHAPTER");
        if (chapters.isEmpty()) {
            throw bad("bookEdits[" + idx + "]: book has no chapters to remove.");
        }
        boolean removeAll = Boolean.TRUE.equals(edit.get("all")) || "true".equalsIgnoreCase(str(edit, "all"));
        if (removeAll) {
            String report = reqStr(edit, "report", idx);
            int removed = 0;
            for (Element c : chapters) {
                if (reportMatches(c.getAttribute("REPORTNAME"), report)) {
                    book.removeChild(c);
                    removed++;
                }
            }
            if (removed == 0) {
                throw bad("bookEdits[" + idx + "]: no chapter matched report '" + report + "'.");
            }
            res.notes.add("Removed " + removed + " chapter(s) matching '" + report + "'.");
            return;
        }
        int targetIdx = resolveChapterIndex(chapters, edit, "index", "report", idx);
        Element target = chapters.get(targetIdx);
        String name = target.getAttribute("REPORTNAME");
        book.removeChild(target);
        res.notes.add("Removed chapter " + targetIdx + " ('" + name + "').");
    }

    private static void applyAdd(Document doc, Element book, String dsId, List<String> dims,
                                 Map<String, Object> edit, int idx, Result res) {
        String report = reqStr(edit, "report", idx);
        List<Element> chapters = children(book, "CHAPTER");
        Integer posObj = optInt(edit, "position");
        int pos = posObj == null ? chapters.size() : posObj;
        if (pos < 0 || pos > chapters.size()) {
            throw bad("bookEdits[" + idx + "]: add position " + pos + " out of range 0.." + chapters.size());
        }

        Element ch = doc.createElement("CHAPTER");
        ch.setAttribute("EMBED_IN_WEB_PAGE", "0");
        ch.setAttribute("INCLUDE_WORD_TOC", "0");
        ch.setAttribute("IS_COVER_PAGE", "0");
        ch.setAttribute("PROCESS_TYPE", "26");
        ch.setAttribute("RELATED_CONTENT_IN_TOC", "0");
        ch.setAttribute("REPORTNAME", report);
        ch.setAttribute("TYPE", "2");

        Map<String, List<String>> selections = parseSelectionsMap(edit.get("selections"), idx);
        Element memsels = doc.createElement("CHAPTER_MEMSELS");
        memsels.setAttribute("DIMCOUNT", String.valueOf(dims.size()));
        memsels.setAttribute("VERSION", "2.0");

        for (String dim : dims) {
            Element dEl = doc.createElement("DIMENSION");
            dEl.setAttribute("DIMENSION_KEY", dsId + ":" + dim);
            dEl.setAttribute("DISPLAY_IN_TOC", "1");
            List<String> members = selections.get(dim);
            if (members == null || members.isEmpty()) {
                dEl.setAttribute("MEM_SEL", buildBookPovMemSel(dim));
            } else {
                dEl.setAttribute("MEM_SEL", buildMembersMemSel(dim, members));
            }
            memsels.appendChild(dEl);
        }
        ch.appendChild(memsels);

        if (pos >= chapters.size()) {
            book.appendChild(ch);
        } else {
            book.insertBefore(ch, chapters.get(pos));
        }
        res.touchedReports.add(report);
        res.notes.add("Added chapter '" + report + "' at index " + pos + ".");
    }

    private static void applySwapReport(Element book, Map<String, Object> edit, int idx, Result res) {
        String fromReport = reqStr(edit, "fromReport", idx);
        String toReport = reqStr(edit, "toReport", idx);
        List<Element> chapters = children(book, "CHAPTER");
        Integer targetIdx = optInt(edit, "index");
        int count = 0;
        if (targetIdx != null) {
            if (targetIdx < 0 || targetIdx >= chapters.size()) {
                throw bad("bookEdits[" + idx + "]: index " + targetIdx + " out of range 0.." + (chapters.size() - 1));
            }
            Element c = chapters.get(targetIdx);
            if (!reportMatches(c.getAttribute("REPORTNAME"), fromReport)) {
                throw bad("bookEdits[" + idx + "]: chapter " + targetIdx + " is '" + c.getAttribute("REPORTNAME")
                        + "', expected '" + fromReport + "'.");
            }
            c.setAttribute("REPORTNAME", toReport);
            count = 1;
        } else {
            for (Element c : chapters) {
                if (reportMatches(c.getAttribute("REPORTNAME"), fromReport)) {
                    c.setAttribute("REPORTNAME", toReport);
                    count++;
                }
            }
        }
        if (count == 0) {
            throw bad("bookEdits[" + idx + "]: no chapter matched report '" + fromReport + "'.");
        }
        res.touchedReports.add(toReport);
        res.notes.add("Swapped " + count + " chapter(s) from '" + fromReport + "' to '" + toReport + "'.");
    }

    private static void applySetMemberSelection(Document doc, Element book, String dsId,
                                                Map<String, Object> edit, int idx, Result res) {
        List<Element> chapters = children(book, "CHAPTER");
        if (chapters.isEmpty()) {
            throw bad("bookEdits[" + idx + "]: book has no chapters.");
        }
        int targetIdx = resolveChapterIndex(chapters, edit, "index", "report", idx);
        String dim = reqStr(edit, "dimension", idx);
        List<String> members = parseMembersList(edit.get("members"), idx);
        if (members.isEmpty()) {
            throw bad("bookEdits[" + idx + "]: 'members' cannot be empty.");
        }

        Element ch = chapters.get(targetIdx);
        Element memsels = firstChild(ch, "CHAPTER_MEMSELS");
        if (memsels == null) {
            memsels = doc.createElement("CHAPTER_MEMSELS");
            memsels.setAttribute("DIMCOUNT", "1");
            memsels.setAttribute("VERSION", "2.0");
            ch.appendChild(memsels);
        }

        Element targetDim = null;
        for (Element d : children(memsels, "DIMENSION")) {
            String key = d.getAttribute("DIMENSION_KEY");
            String dName = key.contains(":") ? key.substring(key.lastIndexOf(':') + 1) : key;
            if (dim.equalsIgnoreCase(dName)) {
                targetDim = d;
                dim = dName;
                break;
            }
        }
        if (targetDim == null) {
            targetDim = doc.createElement("DIMENSION");
            targetDim.setAttribute("DIMENSION_KEY", dsId + ":" + dim);
            targetDim.setAttribute("DISPLAY_IN_TOC", "1");
            memsels.appendChild(targetDim);
            memsels.setAttribute("DIMCOUNT", String.valueOf(children(memsels, "DIMENSION").size()));
        }

        targetDim.setAttribute("MEM_SEL", buildMembersMemSel(dim, members));
        res.notes.add("Updated chapter " + targetIdx + " ('" + ch.getAttribute("REPORTNAME")
                + "') " + dim + " selection to " + String.join(", ", members) + ".");
    }

    // ------------------------------------------------------------------ REF_COUNT & XML builders

    private static void recalculateRefCounts(Document doc, Element povDs, Element book) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Element ch : children(book, "CHAPTER")) {
            Element memsels = firstChild(ch, "CHAPTER_MEMSELS");
            if (memsels == null) {
                continue;
            }
            for (Element d : children(memsels, "DIMENSION")) {
                String ms = d.getAttribute("MEM_SEL");
                if (ms.contains("<STRING>Book Point of View</STRING>")) {
                    String key = d.getAttribute("DIMENSION_KEY");
                    String dim = key.contains(":") ? key.substring(key.lastIndexOf(':') + 1) : key;
                    counts.merge(dim, 1, Integer::sum);
                }
            }
        }

        List<Element> existingRefs = children(povDs, "DIMENSION");
        Set<String> updated = new LinkedHashSet<>();
        for (Element d : existingRefs) {
            String dim = d.getAttribute("NAME");
            Integer cnt = counts.get(dim);
            if (cnt == null || cnt == 0) {
                povDs.removeChild(d);
            } else {
                d.setAttribute("REF_COUNT", String.valueOf(cnt));
                updated.add(dim);
            }
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (!updated.contains(e.getKey()) && e.getValue() > 0) {
                Element d = doc.createElement("DIMENSION");
                d.setAttribute("NAME", e.getKey());
                d.setAttribute("REF_COUNT", String.valueOf(e.getValue()));
                povDs.appendChild(d);
            }
        }
    }

    static String buildMembersMemSel(String dim, List<String> members) {
        if (members.size() == 1 && "Book Point of View".equalsIgnoreCase(members.get(0).trim())) {
            return buildBookPovMemSel(dim);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<COMPOSITEOPERATION TYPE=\"MemberQuery\">");
        sb.append("<OPERATION TYPE=\"Select\"><MULTIVALUE><STRING>*</STRING></MULTIVALUE>");
        sb.append("<STRING>").append(escapeXml(dim)).append("</STRING></OPERATION>");
        sb.append("<COMPOSITEOPERATION TYPE=\"Filter\">");
        if (members.size() == 1) {
            sb.append("<OPERATION TYPE=\"Member\"><STRING>")
                    .append(escapeXml(members.get(0)))
                    .append("</STRING><STRING>")
                    .append(escapeXml(dim))
                    .append("</STRING></OPERATION>");
        } else {
            sb.append("<COMPOSITEOPERATION TYPE=\"List\">");
            for (String m : members) {
                sb.append("<OPERATION TYPE=\"Member\"><STRING>")
                        .append(escapeXml(m))
                        .append("</STRING><STRING>")
                        .append(escapeXml(dim))
                        .append("</STRING></OPERATION>");
            }
            sb.append("</COMPOSITEOPERATION>");
        }
        sb.append("</COMPOSITEOPERATION></COMPOSITEOPERATION>");
        return sb.toString();
    }

    static String buildBookPovMemSel(String dim) {
        return "<COMPOSITEOPERATION TYPE=\"MemberQuery\"><OPERATION TYPE=\"Select\"><MULTIVALUE><STRING>Name</STRING></MULTIVALUE>"
                + "<STRING>" + escapeXml(dim) + "</STRING></OPERATION><COMPOSITEOPERATION TYPE=\"Filter\">"
                + "<OPERATION TYPE=\"Member\"><STRING>Book Point of View</STRING><STRING>Dim</STRING></OPERATION>"
                + "</COMPOSITEOPERATION></COMPOSITEOPERATION>";
    }

    private static void runSelfChecks(Element book, Element povDs, Result res) {
        List<Element> chapters = children(book, "CHAPTER");
        boolean allNamed = true;
        for (Element c : chapters) {
            if (c.getAttribute("REPORTNAME").trim().isEmpty()) {
                allNamed = false;
                break;
            }
        }
        Map<String, Object> c1 = new LinkedHashMap<>();
        c1.put("check", "chapters_have_report_names");
        c1.put("ok", allNamed);
        c1.put("detail", chapters.size() + " chapter(s), allNamed=" + allNamed);
        res.checks.add(c1);
        if (!allNamed) {
            throw bad("Book self-check failed: chapter missing REPORTNAME.");
        }

        // Verify REF_COUNTs match actual chapter inheritances
        Map<String, Integer> expected = new LinkedHashMap<>();
        for (Element ch : chapters) {
            Element memsels = firstChild(ch, "CHAPTER_MEMSELS");
            if (memsels != null) {
                for (Element d : children(memsels, "DIMENSION")) {
                    if (d.getAttribute("MEM_SEL").contains("<STRING>Book Point of View</STRING>")) {
                        String key = d.getAttribute("DIMENSION_KEY");
                        String dim = key.contains(":") ? key.substring(key.lastIndexOf(':') + 1) : key;
                        expected.merge(dim, 1, Integer::sum);
                    }
                }
            }
        }
        Map<String, Integer> actual = new LinkedHashMap<>();
        for (Element d : children(povDs, "DIMENSION")) {
            actual.put(d.getAttribute("NAME"), Integer.parseInt(d.getAttribute("REF_COUNT")));
        }
        boolean refsOk = expected.equals(actual);
        Map<String, Object> c2 = new LinkedHashMap<>();
        c2.put("check", "book_pov_ref_counts_consistent");
        c2.put("ok", refsOk);
        c2.put("detail", actual.size() + " dimension REF_COUNT(s) verified");
        res.checks.add(c2);
        if (!refsOk) {
            throw bad("Book self-check failed: BOOK_POV REF_COUNT mismatch.");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> readBookDimensions(Element povEl, Element book) {
        List<Element> chapters = children(book, "CHAPTER");
        if (!chapters.isEmpty()) {
            Element memsels = firstChild(chapters.get(0), "CHAPTER_MEMSELS");
            if (memsels != null) {
                List<String> dims = new ArrayList<>();
                for (Element d : children(memsels, "DIMENSION")) {
                    String key = d.getAttribute("DIMENSION_KEY");
                    dims.add(key.contains(":") ? key.substring(key.lastIndexOf(':') + 1) : key);
                }
                if (!dims.isEmpty()) {
                    return dims;
                }
            }
        }
        List<String> dims = new ArrayList<>();
        if (povEl != null) {
            for (Element d : children(povEl, "DIMENSION")) {
                if (!"1".equals(d.getAttribute("HIDDEN"))) {
                    dims.add(d.getAttribute("NAME"));
                }
            }
        }
        return dims;
    }

    private static int resolveChapterIndex(List<Element> chapters, Map<String, Object> edit,
                                           String idxKey, String reportKey, int editIdx) {
        Integer explicitIdx = optInt(edit, idxKey);
        if (explicitIdx != null) {
            if (explicitIdx < 0 || explicitIdx >= chapters.size()) {
                throw bad("bookEdits[" + editIdx + "]: " + idxKey + " " + explicitIdx
                        + " out of range 0.." + (chapters.size() - 1));
            }
            return explicitIdx;
        }
        String report = str(edit, reportKey);
        if (report == null) {
            throw bad("bookEdits[" + editIdx + "] requires '" + idxKey + "' or '" + reportKey + "'.");
        }
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) {
            if (reportMatches(chapters.get(i).getAttribute("REPORTNAME"), report)) {
                matches.add(i);
            }
        }
        if (matches.isEmpty()) {
            throw bad("bookEdits[" + editIdx + "]: no chapter matched report '" + report + "'.");
        }
        if (matches.size() > 1) {
            throw bad("bookEdits[" + editIdx + "]: report '" + report + "' appears at multiple indices "
                    + matches + "; specify '" + idxKey + "' explicitly.");
        }
        return matches.get(0);
    }

    private static boolean reportMatches(String reportName, String query) {
        if (reportName == null || query == null) {
            return false;
        }
        String rn = reportName.trim();
        String q = query.trim();
        if (rn.equalsIgnoreCase(q)) {
            return true;
        }
        int slash = rn.lastIndexOf('/');
        String base = slash >= 0 ? rn.substring(slash + 1) : rn;
        return !q.contains("/") && base.equalsIgnoreCase(q);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> parseSelectionsMap(Object raw, int idx) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof Map)) {
            throw bad("bookEdits[" + idx + "]: 'selections' must be a map of dimension -> members.");
        }
        Map<String, Object> m = (Map<String, Object>) raw;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            out.put(e.getKey(), parseMembersList(e.getValue(), idx));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseMembersList(Object raw, int idx) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (raw instanceof List) {
            for (Object item : (List<Object>) raw) {
                if (item != null && !item.toString().trim().isEmpty()) {
                    out.add(item.toString().trim());
                }
            }
        } else if (raw instanceof String) {
            for (String s : ((String) raw).split(",")) {
                if (!s.trim().isEmpty()) {
                    out.add(s.trim());
                }
            }
        } else {
            throw bad("bookEdits[" + idx + "]: 'members' must be a list or comma-separated string.");
        }
        return out;
    }

    private static String reqStr(Map<String, Object> m, String key, int idx) {
        String v = str(m, key);
        if (v == null || v.isEmpty()) {
            throw bad("bookEdits[" + idx + "]: '" + key + "' is required.");
        }
        return v;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString().trim();
    }

    private static Integer optInt(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            throw bad("Expected integer for '" + key + "', got '" + v + "'.");
        }
    }

    private static Element firstChild(Element parent, String tag) {
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element && tag.equals(((Element) c).getTagName())) {
                return (Element) c;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> list = new ArrayList<>();
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element && tag.equals(((Element) c).getTagName())) {
                list.add((Element) c);
            }
        }
        return list;
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw bad("Failed to parse book XML: " + e.getMessage());
        }
    }

    private static String serialize(Document doc) {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.STANDALONE, "no");
            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize book XML", e);
        }
    }

    private static ApiException bad(String msg) {
        return ApiException.badRequest("bad_request", msg);
    }
}
