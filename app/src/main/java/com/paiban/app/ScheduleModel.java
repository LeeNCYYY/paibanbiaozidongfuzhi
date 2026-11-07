package com.paiban.app;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 排班表共享数据模型与分析器(与文件格式无关):
 *  - XlsxParser(.xlsx)与 XlsParser(.xls)都把表格读成"单元格文本表 + 合并区域"后,
 *    调用 analyzeSheet 做统一分析。
 *  - 行列号均为 1-based。
 */
public class ScheduleModel {

    public static final class MergeRange {
        public final int r1, c1, r2, c2;
        public MergeRange(int r1, int c1, int r2, int c2) { this.r1 = r1; this.c1 = c1; this.r2 = r2; this.c2 = c2; }
        public boolean contains(int r, int c) { return r >= r1 && r <= r2 && c >= c1 && c <= c2; }
    }

    /** 一个"排班条目列":表头标签 + 其横跨的列(表头合并时可能多列) */
    public static final class GroupCol {
        public final String label;
        public final List<Integer> cols;
        public GroupCol(String label, List<Integer> cols) { this.label = label; this.cols = cols; }
    }

    public static final class DayRow {
        public final LocalDate date;
        public final int rowIndex;      // 1-based
        public SheetData sheet;         // analyze 后回填
        public DayRow(int rowIndex, LocalDate date) { this.rowIndex = rowIndex; this.date = date; }
        public String cell(int col) { return sheet == null ? null : sheet.valueAt(rowIndex, col); }
    }

    public static final class SheetData {
        public final String name;
        public final int headerRow;     // 1-based
        public final int dateCol;       // 1-based
        public final int weekdayCol;    // 1-based, -1 表示无
        public final List<GroupCol> groups;
        public final List<DayRow> days;
        public final LocalDate firstDate;
        public final LocalDate lastDate;
        private final Map<Integer, Map<Integer, String>> grid;
        private final List<MergeRange> merges;
        private final String inferredYearText;

        SheetData(String name, int headerRow, int dateCol, int weekdayCol,
                  List<GroupCol> groups, List<DayRow> days,
                  Map<Integer, Map<Integer, String>> grid,
                  List<MergeRange> merges, String inferredYearText) {
            this.name = name; this.headerRow = headerRow; this.dateCol = dateCol;
            this.weekdayCol = weekdayCol; this.groups = groups;
            this.grid = grid; this.merges = merges; this.inferredYearText = inferredYearText;
            this.days = days;
            for (DayRow d : days) d.sheet = this;
            LocalDate f = null, l = null;
            for (DayRow d : days) {
                if (d.date == null) continue;
                if (f == null || d.date.isBefore(f)) f = d.date;
                if (l == null || d.date.isAfter(l)) l = d.date;
            }
            this.firstDate = f; this.lastDate = l;
        }

        /** 行列值(合并回退),1-based */
        public String valueAt(int r, int c) { return ScheduleModel.valueAt(grid, merges, r, c); }

        public String weekdayText(DayRow row) {
            if (weekdayCol > 0) {
                String w = row.cell(weekdayCol);
                if (w != null && !w.trim().isEmpty()) return normalizeLabel(w);
            }
            return row.date == null ? "" : cnWeekday(row.date);
        }

        /** 单元格所属合并区域的“锚列”(该区域内最左列);不在合并区则为其自身 */
        private int anchorCol(int r, int c) {
            for (MergeRange m : merges) {
                if (m.r1 <= r && r <= m.r2 && m.c1 <= c && c <= m.c2) return m.c1;
            }
            return c;
        }

        /** 某行某排班条目的内容:该条目横跨各列非空值,以、连接(同一合并区域只取一次) */
        public String groupContent(DayRow row, GroupCol g) {
            StringBuilder sb = new StringBuilder();
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (int c : g.cols) {
                int a = anchorCol(row.rowIndex, c);
                if (!seen.add(a)) continue;
                String v = row.cell(c);
                if (v == null) continue;
                String t = v.trim();
                if (t.isEmpty()) continue;
                if (sb.length() > 0) sb.append('、');
                sb.append(t);
            }
            return sb.toString();
        }

        /** 排班条目的多段内容(同一合并区域只取一次) */
        public List<String> groupSegments(DayRow row, GroupCol g) {
            List<String> segs = new ArrayList<>();
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (int c : g.cols) {
                int a = anchorCol(row.rowIndex, c);
                if (!seen.add(a)) continue;
                String v = row.cell(c);
                if (v == null) continue;
                String t = v.trim();
                if (t.isEmpty()) continue;
                segs.add(t);
            }
            return segs;
        }
    }

    /**
     * "周期"类工作表里"值班组"出现过的人名集合。
     * 这些表通常没有"日期"列,不会被当成排班表;但"值班室值守"只能落在这张表在册的人身上。
     */
    public static final class DutyRoster {
        public final String sheetName;
        public final Set<String> names;
        public DutyRoster(String sheetName, Set<String> names) {
            this.sheetName = sheetName == null ? "" : sheetName;
            this.names = names == null ? new LinkedHashSet<>() : names;
        }
    }

    public static final class ParseResult {
        public final List<SheetData> sheets;
        /** 全部"周期"表的值班组人员并集(可能为空) */
        public final List<DutyRoster> rosters;
        public ParseResult(List<SheetData> sheets) { this(sheets, new ArrayList<>()); }
        public ParseResult(List<SheetData> sheets, List<DutyRoster> rosters) {
            this.sheets = sheets;
            this.rosters = rosters == null ? new ArrayList<>() : rosters;
        }
        public LocalDate overallFirst() {
            LocalDate f = null;
            for (SheetData s : sheets) if (s.firstDate != null && (f == null || s.firstDate.isBefore(f))) f = s.firstDate;
            return f;
        }
        public LocalDate overallLast() {
            LocalDate l = null;
            for (SheetData s : sheets) if (s.lastDate != null && (l == null || s.lastDate.isAfter(l))) l = s.lastDate;
            return l;
        }
    }

    // ---------------- 通用取值 ----------------

    static String valueAt(Map<Integer, Map<Integer, String>> grid, List<MergeRange> merges, int r, int c) {
        Map<Integer, String> row = grid.get(r);
        if (row != null) {
            String v = row.get(c);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        for (MergeRange m : merges) {
            if (m.contains(r, c)) {
                Map<Integer, String> top = grid.get(m.r1);
                if (top != null) {
                    String t = top.get(m.c1);
                    if (t != null && !t.trim().isEmpty()) return t.trim();
                }
            }
        }
        return null;
    }

    // ---------------- 统一分析 ----------------

    /** 归一化表头标签:去掉所有空白(含全角空格、换行) */
    public static String normalizeLabel(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", "").replace("\u00a0", "").replace("\u3000", "").trim();
    }

    /**
     * 分析一张表:定位表头(含"日期")行、日期/星期列、各条目列(表头非空且非日期/星期),
     * 解析数据行。结构不对返回 null。
     */
    public static SheetData analyzeSheet(String name,
                                         Map<Integer, Map<Integer, String>> grid,
                                         List<MergeRange> merges) {
        int maxRow = 0;
        for (int r : grid.keySet()) if (r > maxRow) maxRow = r;

        int headerRow = -1, dateCol = -1;
        int scanLimit = Math.min(maxRow, 15);
        for (int r = 1; r <= scanLimit && headerRow < 0; r++) {
            Map<Integer, String> row = grid.get(r);
            if (row == null) continue;
            for (Map.Entry<Integer, String> en : row.entrySet()) {
                String v = valueAt(grid, merges, r, en.getKey());
                if (normalizeLabel(en.getValue()).equals("日期")) { headerRow = r; dateCol = en.getKey(); break; }
                if (normalizeLabel(en.getValue()).equals("日期时间")) { headerRow = r; dateCol = en.getKey(); break; }
            }
        }
        if (headerRow <= 0 || dateCol <= 0) return null;

        int weekdayCol = -1;
        Map<Integer, String> header = grid.get(headerRow);
        if (header == null) return null;
        for (Map.Entry<Integer, String> en : header.entrySet()) {
            if (normalizeLabel(en.getValue()).equals("星期")) { weekdayCol = en.getKey(); break; }
        }

        // 年份推断:首行标题 + 表名
        StringBuilder titleInfo = new StringBuilder();
        Map<Integer, String> titleRow = grid.get(1);
        if (titleRow != null) for (String s : titleRow.values()) titleInfo.append(s).append(' ');
        titleInfo.append(name == null ? "" : name).append(' ');
        String inferredYearText = "";
        Matcher y = Pattern.compile("(20|19)\\d{2}").matcher(titleInfo.toString());
        if (y.find()) inferredYearText = y.group();

        // 条目列:表头非空且不是 日期/星期
        List<Integer> groupCols = new ArrayList<>();
        List<Integer> sortedCols = new ArrayList<>(header.keySet());
        Collections.sort(sortedCols);
        for (int c : sortedCols) {
            if (c == dateCol || c == weekdayCol) continue;
            String h = normalizeLabel(header.get(c));
            if (h.isEmpty()) continue;
            if (h.equals("日期") || h.equals("日期时间") || h.equals("星期")) continue;
            groupCols.add(c);
        }
        List<GroupCol> groups = new ArrayList<>();
        for (int c : groupCols) {
            List<Integer> span = new ArrayList<>();
            span.add(c);
            for (MergeRange m : merges) {
                if (headerRow >= m.r1 && headerRow <= m.r2 && c >= m.c1 && c <= m.c2) {
                    if (m.c1 == c) {
                        for (int cc = m.c1 + 1; cc <= m.c2; cc++) span.add(cc);
                    }
                    break;
                }
            }
            Collections.sort(span);
            groups.add(new GroupCol(normalizeLabel(header.get(c)), span));
        }

        List<DayRow> days = new ArrayList<>();
        for (int r = headerRow + 1; r <= maxRow; r++) {
            String d = valueAt(grid, merges, r, dateCol);
            if (d == null) continue;
            LocalDate date = parseDate(d, inferredYearText);
            if (date == null) continue;
            days.add(new DayRow(r, date));
        }
        return new SheetData(name == null ? "" : name, headerRow, dateCol, weekdayCol,
                groups, days, grid, merges, inferredYearText);
    }

    /** 表名是否"周期"类工作表:值守名单只从这类表收集 */
    public static boolean isCycleSheetName(String name) {
        return name != null && normalizeLabel(name).contains("周期");
    }

    /**
     * 分析"周期"工作表里的值班组名单。
     *  1) 优先:在前 10 行找表头文本含"值班组"的行,把这些列下方的人名汇总;
     *     合并表头(A1:C1 之类)会经 valueAt 回退到锚点,因此同一组的各列都会被收进来。
     *  2) 兜底:表头没认出来、或该表头列下一个人名都没取到时,
     *     把 A/B/C 三列(第 1~3 列)的所有非表头文本当人名。
     * 非"周期"表一律返回 null —— 没识别到周期工作表,调用方就不加值守标记。
     */
    public static DutyRoster analyzeDutyRoster(String name,
                                               Map<Integer, Map<Integer, String>> grid,
                                               List<MergeRange> merges) {
        if (!isCycleSheetName(name)) return null;
        int maxRow = 0;
        for (int r : grid.keySet()) if (r > maxRow) maxRow = r;
        if (maxRow <= 0) return null;

        int headerRow = -1;
        List<Integer> cols = new ArrayList<>();
        int scanLimit = Math.min(maxRow, 10);
        for (int r = 1; r <= scanLimit; r++) {
            Map<Integer, String> row = grid.get(r);
            if (row == null) continue;
            List<Integer> hit = new ArrayList<>();
            for (int c : row.keySet()) {
                String v = valueAt(grid, merges, r, c);
                if (v != null && normalizeLabel(v).contains(DUTY_LABEL)) hit.add(c);
            }
            if (!hit.isEmpty()) { headerRow = r; cols = hit; break; }
        }

        Set<String> names = new LinkedHashSet<>();
        if (headerRow > 0 && !cols.isEmpty()) {
            for (int r = headerRow + 1; r <= maxRow; r++) {
                for (int c : cols) {
                    String v = valueAt(grid, merges, r, c);
                    if (v == null) continue;
                    names.addAll(splitNames(v));
                }
            }
        }
        if (names.isEmpty()) {
            // 兜底:周期表里没读到"值班组",就把 A/B/C 三列当值班组名单
            for (int r = 1; r <= maxRow; r++) {
                for (int c = 1; c <= 3; c++) {
                    String v = valueAt(grid, merges, r, c);
                    if (v == null) continue;
                    String n = normalizeLabel(v);
                    if (n.isEmpty() || n.contains(DUTY_LABEL) || isRosterHeaderWord(n)) continue;   // 跳过表头
                    names.addAll(splitNames(v));
                }
            }
        }
        if (names.isEmpty()) return null;
        return new DutyRoster(name, names);
    }

    /** 兜底取 A/B/C 列时要跳过的常见表头词(避免把"人员""姓名"等当成名单里的人) */
    private static boolean isRosterHeaderWord(String n) {
        return "人员".equals(n) || "姓名".equals(n) || "名单".equals(n) || "组员".equals(n)
                || "成员".equals(n) || "序号".equals(n) || "编号".equals(n) || "备注".equals(n);
    }

    /** 所有"周期"表值班组人员的并集;没有周期表时返回空集合 */
    public static Set<String> dutyRosterNames(ParseResult pr) {
        Set<String> all = new LinkedHashSet<>();
        if (pr == null) return all;
        for (DutyRoster dr : pr.rosters) all.addAll(dr.names);
        return all;
    }

    // ---------------- 日期 ----------------

    private static final Pattern YMD = Pattern.compile("(20|19)\\d{2}\\s*[年/.-]\\s*(\\d{1,2})\\s*[月/.-]\\s*(\\d{1,2})\\s*日?");
    private static final Pattern MD = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日?");
    private static final Pattern NUMERIC = Pattern.compile("-?\\d+(\\.\\d+)?");

    public static LocalDate parseDate(String raw, String inferredYear) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        if (NUMERIC.matcher(t).matches()) {
            try {
                double d = Double.parseDouble(t);
                if (d >= 1 && d <= 60000) {
                    long days = Math.round(d);
                    return LocalDate.of(1899, 12, 30).plusDays(days);
                }
            } catch (Exception ignored) { }
        }
        Matcher m = YMD.matcher(t);
        if (m.find()) {
            try {
                int y = Integer.parseInt(m.group(1));
                int mo = Integer.parseInt(m.group(2));
                int day = Integer.parseInt(m.group(3));
                if (mo >= 1 && mo <= 12 && day >= 1 && day <= 31) return LocalDate.of(y, mo, day);
            } catch (Exception ignored) { }
        }
        Matcher m2 = MD.matcher(t);
        if (m2.find()) {
            try {
                int mo = Integer.parseInt(m2.group(1));
                int day = Integer.parseInt(m2.group(2));
                int year = LocalDate.now().getYear();
                if (inferredYear != null && !inferredYear.isEmpty()) year = Integer.parseInt(inferredYear);
                if (mo >= 1 && mo <= 12 && day >= 1 && day <= 31) return LocalDate.of(year, mo, day);
            } catch (Exception ignored) { }
        }
        return null;
    }

    /** 查找目标日排班;多张表都有该日时,优先表名含目标年月/月的工作表 */
    public static DayRow findDay(ParseResult pr, LocalDate target) {
        if (pr == null) return null;
        DayRow best = null;
        int bestScore = -1;
        for (SheetData sd : pr.sheets) {
            for (DayRow dr : sd.days) {
                if (dr.date == null || !dr.date.equals(target)) continue;
                int score = monthScore(sd.name, target);
                if (score > bestScore) { bestScore = score; best = dr; }
            }
        }
        return best;
    }

    /** 表名与目标日期匹配度:含 "yyyy年M月"/"yyyy年M月份"/"M月"/"M月份" 加分 */
    static int monthScore(String sheetName, LocalDate target) {
        if (sheetName == null) return 0;
        String n = normalizeLabel(sheetName);
        String ym = target.getYear() + "年" + target.getMonthValue() + "月";
        String m = target.getMonthValue() + "月";
        int score = 0;
        if (n.contains(ym)) score += 10;
        if (n.contains(m)) score += 5;
        // 年份一致但月份不同则减分
        String y = String.valueOf(target.getYear());
        if (n.contains(y) && !n.contains(ym)) score -= 3;
        return score;
    }

    public static String cnDate(LocalDate d) {
        if (d == null) return "";
        return d.getYear() + "年" + d.getMonthValue() + "月" + d.getDayOfMonth() + "日";
    }

    public static String cnWeekday(LocalDate d) {
        if (d == null) return "";
        String[] w = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
        return w[d.getDayOfWeek().getValue() - 1];
    }

    private static final String[] SOLAR_TERM_NAMES = {
            "小寒", "大寒", "立春", "雨水", "惊蛰", "春分",
            "清明", "谷雨", "立夏", "小满", "芒种", "夏至",
            "小暑", "大暑", "立秋", "处暑", "白露", "秋分",
            "寒露", "霜降", "立冬", "小雪", "大雪", "冬至"
    };

    private static final int[][] SOLAR_TERM_DATES_2026 = {
            {1, 5}, {1, 20}, {2, 4}, {2, 18}, {3, 5}, {3, 20},
            {4, 5}, {4, 20}, {5, 5}, {5, 21}, {6, 5}, {6, 21},
            {7, 7}, {7, 23}, {8, 7}, {8, 23}, {9, 7}, {9, 23},
            {10, 8}, {10, 23}, {11, 7}, {11, 22}, {12, 7}, {12, 22}
    };

    private static final int[][] SOLAR_TERM_DATES_2027 = {
            {1, 5}, {1, 20}, {2, 4}, {2, 19}, {3, 6}, {3, 21},
            {4, 5}, {4, 20}, {5, 6}, {5, 21}, {6, 6}, {6, 21},
            {7, 7}, {7, 23}, {8, 8}, {8, 23}, {9, 8}, {9, 23},
            {10, 8}, {10, 23}, {11, 7}, {11, 22}, {12, 7}, {12, 22}
    };

    /** 返回日期对应的二十四节气名称;当前内置 2026、2027 年,其他年份返回空串。 */
    public static String solarTerm(LocalDate date) {
        if (date == null) return "";
        int[][] dates;
        if (date.getYear() == 2026) dates = SOLAR_TERM_DATES_2026;
        else if (date.getYear() == 2027) dates = SOLAR_TERM_DATES_2027;
        else return "";
        int month = date.getMonthValue(), day = date.getDayOfMonth();
        for (int i = 0; i < dates.length; i++) {
            if (dates[i][0] == month && dates[i][1] == day) return SOLAR_TERM_NAMES[i];
        }
        return "";
    }

    /** 在星期文本后面追加当天的二十四节气。 */
    public static String weekdayWithSolarTerm(String weekday, LocalDate date) {
        return weekdayWithHolidayOrSolarTerm(weekday, "", date);
    }

    /**
     * 在星期文本后追加节假日或节气名称。
     * 法定节假日优先;没有法定节假日时使用二十四节气。
     */
    public static String weekdayWithHolidayOrSolarTerm(String weekday, String holiday, LocalDate date) {
        String w = weekday == null ? "" : weekday.trim();
        String mark = holiday == null ? "" : holiday.trim();
        if (mark.isEmpty()) mark = solarTerm(date);
        if (mark.isEmpty()) return w;
        return w.isEmpty() ? mark : w + " " + mark;
    }

    // ---------------- 护学岗:由"值班组"派生 ----------------

    /** 表头里的值班组列名 */
    public static final String DUTY_LABEL = "值班组";

    /** 单元格文字以"备注"开头时,该行标题统一用这两个字 */
    public static final String REMARK_LABEL = "备注";

    /** "当天排班"里由值班组派生的两段标签 */
    public static final String GUARD_AM = "早上护学岗";
    public static final String GUARD_PM = "下午护学岗";

    /** 表头条目是否是"值班组"列(归一化后包含"值班组"即可) */
    public static boolean isDutyLabel(String label) {
        return label != null && normalizeLabel(label).contains(DUTY_LABEL);
    }

    /** 界面上派生的护学岗两段标签;源表里的列名一律不匹配(源表列按普通列原样显示) */
    public static boolean isGuardLabel(String label) {
        return GUARD_AM.equals(label) || GUARD_PM.equals(label);
    }

    /**
     * 源表里的护学岗列(可能写作护校岗/护学岗)。
     * 当天排班页不读取、不展示、不复制这些列——它们已被派生的"早上/下午护学岗"取代;
     * 当月排班页仍按原表原样展示。
     */
    public static boolean isSourceGuardColumn(String label) {
        return label != null && (label.contains("护校岗") || label.contains("护学岗"));
    }

    /** 源表是否包含护学岗/护校岗列;没有时不派生、不展示、不复制护学岗 */
    public static boolean hasSourceGuardColumn(SheetData sheet) {
        if (sheet == null) return false;
        for (GroupCol g : sheet.groups) {
            if (isSourceGuardColumn(g.label)) return true;
        }
        return false;
    }

    /** 当天源表是否实际安排了护学岗:护学岗/护校岗列存在且对应单元格非空 */
    public static boolean hasScheduledGuard(DayRow row) {
        if (row == null || row.sheet == null) return false;
        for (GroupCol g : row.sheet.groups) {
            if (!isSourceGuardColumn(g.label)) continue;
            String text = row.sheet.groupContent(row, g);
            if (text != null && !text.trim().isEmpty()) return true;
        }
        return false;
    }

    /** 人名拆分:分隔符兼容 、 ， , ; ； / 与空白(含换行、全角空格) */
    public static List<String> splitNames(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String p : text.split("[、,，;；/\\s\u3000]+")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 值班组的后三人:不足 3 个就全取;文本为空返回空串 */
    public static String lastThree(String text) {
        List<String> all = splitNames(text);
        if (all.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, all.size() - 3); i < all.size(); i++) {
            if (sb.length() > 0) sb.append('、');
            sb.append(all.get(i));
        }
        return sb.toString();
    }

    /**
     * 从值班组中按原顺序最多取三个同时存在于"周期"表值班组名单的人。
     * 不在名册中的人一律跳过;不足三人时返回全部命中人员;名册为空时返回空串。
     */
    public static String firstThreeInRoster(String text, Set<String> roster) {
        if (roster == null || roster.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String name : splitNames(text)) {
            if (!roster.contains(name)) continue;
            if (sb.length() > 0) sb.append('、');
            sb.append(name);
            if (++count == 3) break;
        }
        return sb.toString();
    }

    /** 由值班组派生、仅用于复制文本的值守人员行 */
    public static final String DUTY_ROOM_LABEL = "值班室值守";

    /**
     * 在值班组名单里选定"值班室值守"的人。
     * 从名单第 1 人开始往后找,取第一个同时出现在"周期"表值班组名单(roster)里的人。
     * 没有人同时在两个名单中,或 roster 为空时返回空串。
     */
    public static String dutyRoomName(String names, Set<String> roster) {
        if (names == null) return "";
        List<String> all = splitNames(names);
        if (all.isEmpty() || roster == null || roster.isEmpty()) return "";
        for (String name : all) {
            if (roster.contains(name)) return name;
        }
        return "";
    }

    /** 该单元格文本是否以"备注"开头(此时表头列名作废,该行标题改用"备注") */
    public static boolean isRemarkEntry(String text) {
        if (text == null) return false;
        return normalizeLabel(text).startsWith("备注");
    }

    /**
     * 去掉开头的"备注"二字及紧随其后的冒号/顿号等分隔符,
     * 避免标题已经是"备注"、内容又带"备注："时出现"备注：备注：xxx"。
     * 不以"备注"开头时原样返回(仅去首尾空白)。
     */
    public static String stripRemarkPrefix(String text) {
        if (text == null) return "";
        String s = text.trim();
        if (!isRemarkEntry(s)) return s;
        return s.replaceFirst("^\\s*备\\s*注\\s*[：:、,，.。;；]*\\s*", "").trim();
    }

    /** 某天某一项条目的文本;该天不存在、或该天表里没有这个条目时返回空串 */
    public static String groupText(DayRow row, String label) {
        if (row == null) return "";
        for (GroupCol g : row.sheet.groups) {
            if (g.label.equals(label)) {
                String c = row.sheet.groupContent(row, g);
                return c == null ? "" : c;
            }
        }
        return "";
    }

    /** 某天"值班组"列的表格原值;该天无排班、或表里没有值班组列时返回空串 */
    public static String dutyTextOfDay(ParseResult pr, LocalDate date) {
        DayRow row = findDay(pr, date);
        if (row == null) return "";
        for (GroupCol g : row.sheet.groups) {
            if (isDutyLabel(g.label)) return groupText(row, g.label);
        }
        return "";
    }

    /**
     * 目标日两段护学岗:{早上, 下午}。
     * 早上取目标日【前一天】的值班组;下午取目标日【当天】的值班组。
     * 两段都只取"周期"表名册中的前三人,跳过不在名册中的人。
     * 目标日所在源表没有护学岗/护校岗列时,两段都为空。
     * 这里只按表格原值算;界面上若有编辑,调用方用 firstThreeInRoster(编辑值) 覆盖。
     */
    public static String[] guardDuty(ParseResult pr, LocalDate target) {
        String[] r = { "", "" };
        if (pr == null || target == null) return r;
        DayRow targetRow = findDay(pr, target);
        if (targetRow == null || !hasSourceGuardColumn(targetRow.sheet)) return r;
        Set<String> roster = dutyRosterNames(pr);
        r[0] = firstThreeInRoster(dutyTextOfDay(pr, target.minusDays(1)), roster);
        r[1] = firstThreeInRoster(dutyTextOfDay(pr, target), roster);
        return r;
    }
}
