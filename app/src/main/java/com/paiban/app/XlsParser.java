package com.paiban.app;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯 Java 解析 .xls(Excel 97-2003, BIFF8 + OLE2 复合文档),零第三方依赖。
 * 兼容 WPS 导出的目录条目变体布局。
 */
public class XlsParser {

    public static ScheduleModel.ParseResult parse(InputStream in) throws Exception {
        byte[] doc = readAll(in);
        if (doc.length < 512) throw new IOException("文件过短,不是有效的 .xls 文件");
        if (!(doc[0] == (byte) 0xD0 && doc[1] == (byte) 0xCF && doc[2] == 0x11 && doc[3] == (byte) 0xE0)) {
            throw new IOException("不是 OLE2 复合文档(.xls)");
        }
        int sectorSize = 1 << u16(doc, 0x1E);
        int numFat = u32(doc, 0x2C);
        int dirStart = u32(doc, 0x30);

        // FAT 扇区号:头部 DIFAT 位于 0x4C
        List<Integer> fatSectorList = new ArrayList<>();
        for (int i = 0; i < Math.min(numFat, 109); i++) {
            int fs = u32(doc, 0x4C + i * 4);
            if (fs >= 0) fatSectorList.add(fs);
        }
        if (fatSectorList.isEmpty()) throw new IOException("无法读取 FAT 表");
        long[] fat = new long[fatSectorList.size() * (sectorSize / 4)];
        int written = 0;
        for (int fs : fatSectorList) {
            int off = (fs + 1) * sectorSize;
            for (int e = 0; e < sectorSize / 4 && off + e * 4 + 4 <= doc.length && written < fat.length; e++) {
                fat[written++] = u32l(doc, off + e * 4);
            }
        }

        // 目录流
        byte[] dirData = readChain(doc, sectorSize, dirStart, fat, 1_000_000);

        // 找 Workbook 流(兼容两种目录条目布局)
        for (int i = 0; i + 128 <= dirData.length; i += 128) {
            String nameV = nulName(dirData, i, 32);
            int typeV = i + 66 < dirData.length ? (dirData[i + 66] & 0xFF) : -1;
            String nameS = nulName(dirData, i + 2, 32);
            int typeS = i + 64 < dirData.length ? (dirData[i + 64] & 0xFF) : -1;
            boolean vOk = nameV != null && (typeV == 1 || typeV == 2 || typeV == 5);
            boolean sOk = nameS != null && (typeS == 1 || typeS == 2 || typeS == 5);
            boolean vLenOk = i + 66 <= dirData.length && u16(dirData, i + 64) == 2 * (nameV == null ? 0 : nameV.length() + 1);
            String name; int startSector; long size;
            if (vOk && (!sOk || vLenOk)) {
                name = nameV;
                startSector = u32(dirData, i + 116);
                size = u64(dirData, i + 120);
            } else if (sOk) {
                name = nameS;
                startSector = u32(dirData, i + 114);
                size = u64(dirData, i + 118);
            } else {
                continue;
            }
            if ("Workbook".equalsIgnoreCase(name) || "Book".equalsIgnoreCase(name)) {
                byte[] stream = readChain(doc, sectorSize, startSector, fat,
                        size >= 0 && size < 500_000_000 ? (int) size : -1);
                return parseWorkbook(stream);
            }
        }
        throw new IOException("未找到 Workbook 数据流");
    }

    private static String nulName(byte[] b, int off, int maxChars) {
        if (off < 0 || off + 2 > b.length) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxChars; i++) {
            int p = off + i * 2;
            if (p + 2 > b.length) return null;
            int u = u16(b, p);
            if (u == 0) return sb.length() > 0 ? sb.toString() : null;
            if (u < 0x20 && u != 0) return null;
            sb.append((char) u);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    // ---------- OLE2 工具 ----------

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static byte[] readChain(byte[] doc, int sectorSize, int start, long[] fat, int maxLen) {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        int cur = start;
        int guard = 0;
        while (cur >= 0 && cur < fat.length && guard++ < 100000) {
            int off = (cur + 1) * sectorSize;
            if (off >= doc.length) break;
            int take = Math.min(sectorSize, doc.length - off);
            bos.write(doc, off, take);
            long next = fat[cur];
            if (next == 0xFFFFFFFEL || next == 0xFFFFFFFFL || next >= 1000000) break;
            cur = (int) next;
            if (maxLen >= 0 && bos.size() >= maxLen) break;
        }
        byte[] out = bos.toByteArray();
        if (maxLen >= 0 && out.length > maxLen) {
            byte[] t = new byte[maxLen];
            System.arraycopy(out, 0, t, 0, maxLen);
            out = t;
        }
        return out;
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }
    private static int u32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }
    private static long u32l(byte[] b, int o) {
        return ((long) b[o] & 0xFF) | (((long) b[o + 1] & 0xFF) << 8)
                | (((long) b[o + 2] & 0xFF) << 16) | (((long) b[o + 3] & 0xFF) << 24);
    }
    private static long u64(byte[] b, int o) {
        long v = 0;
        for (int i = 0; i < 8; i++) v |= ((long) (b[o + i] & 0xFF)) << (8 * i);
        return v;
    }

    // ---------- BIFF8 记录 ----------

    private static final class BiffRec {
        final int op;
        final byte[] d;
        BiffRec(int op, byte[] d) { this.op = op; this.d = d; }
        int rw() { return d.length >= 2 ? XlsParser.u16(d, 0) : -1; }
        int col() { return d.length >= 4 ? XlsParser.u16(d, 2) : -1; }
        int u16(int o) { return o + 1 < d.length ? XlsParser.u16(d, o) : 0; }
        int u32(int o) { return o + 3 < d.length ? XlsParser.u32(d, o) : 0; }
        double f64(int o) {
            if (o + 7 >= d.length) return 0;
            return Double.longBitsToDouble(u64(d, o));
        }
    }

    private static ScheduleModel.ParseResult parseWorkbook(byte[] wb) throws IOException {
        List<BiffRec> globals = new ArrayList<>();
        List<List<BiffRec>> sheets = new ArrayList<>();
        int i = 0;
        List<BiffRec> cur = globals;
        boolean inSheet = false;
        while (i + 4 <= wb.length) {
            int op = u16(wb, i);
            int len = u16(wb, i + 2);
            int dataStart = i + 4;
            if (dataStart + len > wb.length) break;
            byte[] data = new byte[len];
            System.arraycopy(wb, dataStart, data, 0, len);
            BiffRec r = new BiffRec(op, data);
            if (op == 0x0809 && data.length >= 4) { // BOF
                int dt = u16(data, 2);
                if (dt == 0x0005) { globals.clear(); cur = globals; inSheet = false; }
                else if (dt == 0x0010) {
                    if (inSheet && cur != globals) sheets.add(cur);
                    cur = new ArrayList<>();
                    inSheet = true;
                    cur.add(r);
                    i = dataStart + len;
                    continue;
                } else if (dt == 0x0020 || dt == 0x0040) {
                    if (inSheet && cur != globals) sheets.add(cur);
                    cur = globals;
                    inSheet = false;
                    i = dataStart + len;
                    continue;
                }
            } else if (op == 0x000A) { // EOF
                if (inSheet && cur != globals && cur.size() > 0) { sheets.add(cur); }
                inSheet = false;
                cur = globals;
                i = dataStart + len;
                continue;
            }
            cur.add(r);
            i = dataStart + len;
        }
        if (inSheet && cur != globals && cur.size() > 0) sheets.add(cur);

        // SST
        List<String> sst = new ArrayList<>();
        for (BiffRec r : globals) {
            if (r.op == 0x00FC) { sst = parseSst(globals); break; }
        }

        // BOUNDSHEET 名称(仅工作表类型):pos(4)+vis(2)+type(2)+cch(1)+utf16名称
        List<String> names = new ArrayList<>();
        for (BiffRec r : globals) {
            if (r.op == 0x0085 && r.d.length >= 9) {
                int type = u16(r.d, 4); // 布局:pos(4)+type(2)+vis(2)+name
                if (type != 0x0000) continue; // 只要工作表
                String nm = parseSheetName(r.d);
                if (nm.trim().isEmpty()) nm = "Sheet" + (names.size() + 1);
                names.add(nm);
            }
        }

        List<ScheduleModel.SheetData> result = new ArrayList<>();
        for (int s = 0; s < sheets.size(); s++) {
            Map<Integer, Map<Integer, String>> grid = new HashMap<>();
            List<ScheduleModel.MergeRange> merges = new ArrayList<>();
            for (BiffRec r : sheets.get(s)) {
                int row0 = r.rw(), col0 = r.col();
                if (row0 < 0 || col0 < 0) continue;
                switch (r.op) {
                    case 0x00E5: parseMerged(r, merges); break;
                    case 0x00FD: putCell(grid, row0, col0, sstGet(sst, r.u32(6))); break;
                    case 0x0203: putCell(grid, row0, col0, numToText(r.f64(6))); break;
                    case 0x027E: putCell(grid, row0, col0, numToText(rkDecode(r.u32(6)))); break;
                    case 0x00BD: parseMulRk(r, grid); break;
                    case 0x0204: putCell(grid, row0, col0, parseStr(r, 6)); break;
                    case 0x0207: putCell(grid, row0, col0, parseStr(r, 6)); break;
                    case 0x0006: parseFormula(r, grid); break;
                    default: break;
                }
            }
            String nm = s < names.size() ? names.get(s) : ("Sheet" + (s + 1));
            ScheduleModel.SheetData sd = ScheduleModel.analyzeSheet(nm, grid, merges);
            if (sd != null) result.add(sd);
        }
        return new ScheduleModel.ParseResult(result);
    }

    /** 解析 BOUNDSHEET 名称(兼容 带cch 与 不带cch 两种布局) */
    private static String parseSheetName(byte[] d) {
        String best = "";
        int bestScore = -1;
        // 布局A:名称从偏移8开始(整段 utf16)
        if (d.length > 8 && (d.length - 8) % 2 == 0) {
            String s = new String(d, 8, d.length - 8, java.nio.charset.StandardCharsets.UTF_16LE);
            int z = s.indexOf('\u0000');
            if (z >= 0) s = s.substring(0, z);
            if (score(s) > bestScore) { bestScore = score(s); best = s; }
        }
        // 布局B:偏移8为 cch,名称从9开始
        if (d.length >= 9) {
            int cch = d[8] & 0xFF;
            if (cch > 0 && cch <= 64 && 9 + cch * 2 <= d.length) {
                String s = new String(d, 9, cch * 2, java.nio.charset.StandardCharsets.UTF_16LE);
                if (score(s) > bestScore) { bestScore = score(s); best = s; }
            }
        }
        return best;
    }

    private static int score(String s) {
        int sc = 0;
        for (char ch : s.toCharArray()) {
            if (ch == 0) break;
            if (ch >= 0x4E00 && ch <= 0x9FFF) sc += 3;
            else if (Character.isLetterOrDigit(ch)) sc += 1;
            else if (Character.isWhitespace(ch) || ch == 0x3000) sc += 0;
            else sc -= 2;
        }
        return sc;
    }

    private static String sstGet(List<String> sst, int idx) {
        return idx >= 0 && idx < sst.size() ? sst.get(idx) : null;
    }

    private static void putCell(Map<Integer, Map<Integer, String>> grid, int row0, int col0, String val) {
        if (val == null) return;
        String t = val.trim();
        if (t.isEmpty()) return;
        grid.computeIfAbsent(row0 + 1, k -> new HashMap<>()).put(col0 + 1, t);
    }

    private static String numToText(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    private static double rkDecode(int raw) {
        boolean div100 = (raw & 0x02) != 0;
        double v;
        if ((raw & 0x01) != 0) {
            v = (raw >> 2);
        } else {
            v = Double.longBitsToDouble(((long) (raw & 0xFFFFFFFCL)) << 32);
        }
        return div100 ? v / 100.0 : v;
    }

    private static void parseMulRk(BiffRec r, Map<Integer, Map<Integer, String>> grid) {
        if (r.d.length < 6) return;
        int row = r.u16(0);
        int pos = 4;
        while (pos + 6 <= r.d.length) {
            int c = u16(r.d, pos);
            int raw = u32(r.d, pos + 2);
            putCell(grid, row, c, numToText(rkDecode(raw)));
            pos += 6;
        }
    }

    private static void parseMerged(BiffRec r, List<ScheduleModel.MergeRange> merges) {
        if (r.d.length < 2) return;
        int count = u16(r.d, 0);
        for (int k = 0; k < count && 2 + k * 8 + 8 <= r.d.length; k++) {
            int p = 2 + k * 8;
            int a = u16(r.d, p) + 1, b = u16(r.d, p + 2) + 1;
            int c = u16(r.d, p + 4) + 1, d = u16(r.d, p + 6) + 1;
            merges.add(new ScheduleModel.MergeRange(Math.min(a, b), Math.min(c, d), Math.max(a, b), Math.max(c, d)));
        }
    }

    /** LABEL / STRING 记录:cch(2) + grbit(1) + 字符 */
    private static String parseStr(BiffRec r, int start) {
        if (r.d.length < start + 3) return null;
        int cch = u16(r.d, start);
        int flags = r.d[start + 2] & 0xFF;
        int pos = start + 3;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cch; i++) {
            if ((flags & 0x01) != 0) {
                if (pos + 2 > r.d.length) break;
                sb.append((char) u16(r.d, pos));
                pos += 2;
            } else {
                if (pos >= r.d.length) break;
                sb.append((char) (r.d[pos] & 0xFF));
                pos += 1;
            }
        }
        return sb.toString();
    }

    private static void parseFormula(BiffRec r, Map<Integer, Map<Integer, String>> grid) {
        if (r.d.length < 14) return;
        boolean isString = (r.d[6] == (byte) 0xFF && r.d[7] == (byte) 0xFF);
        if (isString) return;
        double v = Double.longBitsToDouble(u64(r.d, 6));
        putCell(grid, r.rw(), r.col(), numToText(v));
    }

    // ---------- SST ----------

    private static List<String> parseSst(List<BiffRec> globals) {
        // 拼接 SST 及其后 CONTINUE 记录的数据
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        boolean collecting = false;
        for (BiffRec r : globals) {
            if (r.op == 0x00FC) { bos.write(r.d, 0, r.d.length); collecting = true; continue; }
            if (collecting && r.op == 0x003C) { bos.write(r.d, 0, r.d.length); continue; }
            collecting = false;
        }
        byte[] data = bos.toByteArray();
        List<String> out = new ArrayList<>();
        if (data.length < 8) return out;
        int unique = u32(data, 4);
        int p = 8;
        try {
            for (int s = 0; s < unique && p + 3 <= data.length; s++) {
                int cch = u16(data, p);
                int grbit = data[p + 2] & 0xFF;
                p += 3;
                boolean unicode = (grbit & 0x01) != 0;
                boolean rich = (grbit & 0x08) != 0;
                boolean ext = (grbit & 0x04) != 0;
                int richRuns = 0, extSize = 0;
                if (rich && p + 2 <= data.length) { richRuns = u16(data, p); p += 2; }
                if (ext && p + 4 <= data.length) { extSize = u32(data, p); p += 4; }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < cch; i++) {
                    if (unicode) {
                        if (p + 2 > data.length) break;
                        sb.append((char) u16(data, p));
                        p += 2;
                    } else {
                        if (p >= data.length) break;
                        sb.append((char) (data[p] & 0xFF));
                        p += 1;
                    }
                }
                p = Math.min(p + richRuns * 4 + extSize, data.length);
                out.add(sb.toString());
            }
        } catch (Exception ignored) { }
        return out;
    }
}