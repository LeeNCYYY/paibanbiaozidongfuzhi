package com.paiban.app;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** .xlsx 解析:把 zip 中的 XML 表格读成网格后交给 ScheduleModel 分析 */
public class XlsxParser {

    private static DocumentBuilderFactory dbf;
    static { dbf = DocumentBuilderFactory.newInstance(); }

    private final List<String> sharedStrings = new ArrayList<>();

    public static ScheduleModel.ParseResult parse(InputStream in) throws Exception {
        XlsxParser p = new XlsxParser();
        return p.doParse(in);
    }

    private ScheduleModel.ParseResult doParse(InputStream in) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zis.read(buf)) != -1) bos.write(buf, 0, n);
                entries.put(e.getName(), bos.toByteArray());
            }
        }
        if (entries.isEmpty()) throw new IOException("文件不是有效的 Excel 文件");

        byte[] ss = entries.get("xl/sharedStrings.xml");
        if (ss != null) {
            Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(ss));
            NodeList sis = doc.getElementsByTagName("si");
            for (int i = 0; i < sis.getLength(); i++) {
                Element si = (Element) sis.item(i);
                StringBuilder sb = new StringBuilder();
                NodeList ts = si.getElementsByTagName("t");
                for (int j = 0; j < ts.getLength(); j++) sb.append(ts.item(j).getTextContent());
                sharedStrings.add(sb.toString());
            }
        }

        List<String[]> sheetOrder = new ArrayList<>();
        byte[] wb = entries.get("xl/workbook.xml");
        if (wb != null) {
            Map<String, String> rels = new HashMap<>();
            byte[] rb = entries.get("xl/_rels/workbook.xml.rels");
            if (rb != null) {
                Document rd = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(rb));
                NodeList rs = rd.getElementsByTagName("Relationship");
                for (int i = 0; i < rs.getLength(); i++) {
                    Element rel = (Element) rs.item(i);
                    String t = rel.getAttribute("Target");
                    if (t.startsWith("/")) t = t.substring(1);
                    else if (!t.startsWith("xl/")) t = "xl/" + t;
                    rels.put(rel.getAttribute("Id"), t);
                }
            }
            Document wd = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(wb));
            NodeList sheets = wd.getElementsByTagName("sheet");
            for (int i = 0; i < sheets.getLength(); i++) {
                Element sh = (Element) sheets.item(i);
                String rid = sh.getAttribute("r:id");
                if (rid.isEmpty()) rid = sh.getAttributeNS(
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
                String target = rels.get(rid);
                if (target == null) continue;
                sheetOrder.add(new String[]{ sh.getAttribute("name"), target });
            }
        }
        if (sheetOrder.isEmpty()) {
            List<String> ws = new ArrayList<>();
            for (String k : entries.keySet())
                if (k.startsWith("xl/worksheets/") && k.endsWith(".xml")) ws.add(k);
            Collections.sort(ws);
            for (String n : ws) sheetOrder.add(new String[]{ n, n });
        }

        List<ScheduleModel.SheetData> result = new ArrayList<>();
        for (String[] ref : sheetOrder) {
            byte[] xml = entries.get(ref[1]);
            if (xml == null) continue;
            ScheduleModel.SheetData sd = parseSheet(ref[0], xml);
            if (sd != null) result.add(sd);
        }
        return new ScheduleModel.ParseResult(result);
    }

    private ScheduleModel.SheetData parseSheet(String name, byte[] xml) throws Exception {
        Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml));

        Map<Integer, Map<Integer, String>> grid = new HashMap<>();
        List<ScheduleModel.MergeRange> merges = new ArrayList<>();

        NodeList mc = doc.getElementsByTagName("mergeCell");
        for (int i = 0; i < mc.getLength(); i++) {
            String ref = ((Element) mc.item(i)).getAttribute("ref");
            if (ref == null) continue;
            String[] parts = ref.split(":");
            if (parts.length == 2) {
                int r1 = rowFromRef(parts[0]), c1 = colFromRef(parts[0]);
                int r2 = rowFromRef(parts[1]), c2 = colFromRef(parts[1]);
                if (r1 > 0 && c1 > 0 && r2 >= r1 && c2 >= c1) merges.add(new ScheduleModel.MergeRange(r1, c1, r2, c2));
            }
        }

        NodeList rows = doc.getElementsByTagName("row");
        for (int i = 0; i < rows.getLength(); i++) {
            Element rowEl = (Element) rows.item(i);
            String rr = rowEl.getAttribute("r");
            if (rr.isEmpty()) continue;
            int r;
            try { r = Integer.parseInt(rr); } catch (Exception e) { continue; }
            if (r <= 0) continue;
            Map<Integer, String> rowMap = new HashMap<>();
            NodeList cells = rowEl.getElementsByTagName("c");
            for (int j = 0; j < cells.getLength(); j++) {
                Element c = (Element) cells.item(j);
                int col = colFromRef(c.getAttribute("r"));
                if (col <= 0) continue;
                String v = cellValue(c);
                if (v != null && !v.trim().isEmpty()) rowMap.put(col, v.trim());
            }
            if (!rowMap.isEmpty()) grid.put(r, rowMap);
        }
        return ScheduleModel.analyzeSheet(name, grid, merges);
    }

    private String cellValue(Element c) {
        String type = c.getAttribute("t");
        NodeList vs = c.getElementsByTagName("v");
        if (type.equals("s")) {
            if (vs.getLength() == 0) return null;
            try {
                int idx = Integer.parseInt(vs.item(0).getTextContent().trim());
                return idx >= 0 && idx < sharedStrings.size() ? sharedStrings.get(idx) : null;
            } catch (Exception e) { return null; }
        }
        if (type.equals("inlineStr")) {
            NodeList ts = c.getElementsByTagName("t");
            return ts.getLength() == 0 ? null : ts.item(0).getTextContent();
        }
        if (vs.getLength() == 0) return null;
        return vs.item(0).getTextContent();
    }

    private static final Pattern REF = Pattern.compile("([A-Z]+)([0-9]+)");
    private static int colFromRef(String ref) {
        Matcher m = REF.matcher(ref);
        if (!m.find()) return -1;
        int col = 0;
        for (char ch : m.group(1).toCharArray()) col = col * 26 + (ch - 'A' + 1);
        return col;
    }
    private static int rowFromRef(String ref) {
        Matcher m = REF.matcher(ref);
        if (!m.find()) return -1;
        return Integer.parseInt(m.group(2));
    }
}
