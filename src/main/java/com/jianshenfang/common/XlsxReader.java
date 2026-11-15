package com.jianshenfang.common;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 极简 xlsx 读取器：只用 JDK 自带的 zip + DOM，避免为一次导入引入 POI 依赖。
 *
 * <p>只读第一个工作表，按单元格引用（A1、B2…）定位列，缺失的列补空串，
 * 因此对「中间有空列」的表格也能正确对齐。
 */
public final class XlsxReader {

    private XlsxReader() {
    }

    /** 读取第一个工作表的全部行；每行是按下标排列的单元格文本 */
    public static List<List<String>> readFirstSheet(InputStream in) {
        byte[] sharedBytes = null;
        byte[] sheetBytes = null;
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                if ("xl/sharedStrings.xml".equals(name)) {
                    sharedBytes = readAll(zip);
                } else if (sheetBytes == null && name.startsWith("xl/worksheets/sheet")
                        && name.endsWith(".xml")) {
                    sheetBytes = readAll(zip);
                }
            }
        } catch (Exception ex) {
            throw new BusinessException("读取 Excel 失败，请确认文件是 .xlsx 格式");
        }
        if (sheetBytes == null) {
            throw new BusinessException("Excel 缺少工作表数据，请重新导出后再试");
        }
        List<String> shared = sharedBytes == null ? List.of() : parseShared(sharedBytes);
        return parseSheet(sheetBytes, shared);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            // 禁掉 DTD / 外部实体，避免 XXE
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new BusinessException("Excel 内容解析失败，请重新导出后再试");
        }
    }

    private static List<String> parseShared(byte[] xml) {
        Document doc = parse(xml);
        NodeList sis = doc.getElementsByTagNameNS("*", "si");
        List<String> list = new ArrayList<>(sis.getLength());
        for (int i = 0; i < sis.getLength(); i++) {
            list.add(textOf(sis.item(i)));
        }
        return list;
    }

    private static List<List<String>> parseSheet(byte[] xml, List<String> shared) {
        Document doc = parse(xml);
        NodeList rows = doc.getElementsByTagNameNS("*", "row");
        List<List<String>> out = new ArrayList<>(rows.getLength());
        for (int i = 0; i < rows.getLength(); i++) {
            Node rowNode = rows.item(i);
            if (!(rowNode instanceof Element row)) continue;
            List<String> cells = new ArrayList<>();
            NodeList cs = row.getElementsByTagNameNS("*", "c");
            for (int j = 0; j < cs.getLength(); j++) {
                Node cn = cs.item(j);
                if (!(cn instanceof Element c)) continue;
                int col = colIndex(c.getAttribute("r"));
                if (col < 0) continue;
                String type = c.getAttribute("t");
                String value = "";
                if ("s".equals(type)) {
                    String raw = firstChildText(c, "v");
                    int idx = parseInt(raw, -1);
                    if (idx >= 0 && idx < shared.size()) value = shared.get(idx);
                } else if ("inlineStr".equals(type)) {
                    value = textOf(c);
                } else {
                    value = firstChildText(c, "v");
                }
                while (cells.size() <= col) cells.add("");
                cells.set(col, value == null ? "" : value.trim());
            }
            out.add(cells);
        }
        return out;
    }

    private static String textOf(Node node) {
        NodeList ts = ((Element) node).getElementsByTagNameNS("*", "t");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ts.getLength(); i++) {
            String t = ts.item(i).getTextContent();
            if (t != null) sb.append(t);
        }
        return sb.toString();
    }

    private static String firstChildText(Element el, String tag) {
        NodeList ns = el.getElementsByTagNameNS("*", tag);
        return ns.getLength() == 0 ? "" : ns.item(0).getTextContent();
    }

    /** "AB12" → 27（0 基）；解析失败返回 -1 */
    private static int colIndex(String ref) {
        if (ref == null || ref.isEmpty()) return -1;
        int col = 0;
        boolean any = false;
        for (char ch : ref.toCharArray()) {
            if (ch >= 'A' && ch <= 'Z') {
                col = col * 26 + (ch - 'A' + 1);
                any = true;
            } else {
                break;
            }
        }
        return any ? col - 1 : -1;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
