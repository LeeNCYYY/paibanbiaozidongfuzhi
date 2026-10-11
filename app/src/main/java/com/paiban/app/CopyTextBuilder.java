package com.paiban.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 生成"一键复制"的最终文本 */
public class CopyTextBuilder {

    /**
     * 生成某天的复制文本(与"当天排班"页所见一致):
     * <pre>
     * [前置备注]
     * 2026年9月1日
     * 星期二（中秋节）
     * 值班组：张一、张二、张三
     * 下午护学岗：张三、张四、张五
     * 备注：请提前十分钟到岗
     * [后置备注]
     * </pre>
     * 规则:
     *  - 普通条目按源表列顺序输出,没有标题前缀
     *  - 源表的"护学岗/护校岗"列不读取、不输出(当天排班页已把它拆成早上/下午两段)
     *  - 内容以"备注"开头的那一栏:标题改用"备注",内容去掉重复的"备注"字样,
     *    并且无论它在源表哪一列,都固定排在最后(后置备注之前)
     *  - 护学岗两段排在普通条目之后;对应开关为 false 时不输出
     *  - 内容为空的行自动跳过
     *
     * @param row       目标日排班行
     * @param edited    该日的编辑覆盖(键=源表列名),可为 null
     * @param guard     护学岗两段 {早上, 下午},可为 null
     * @param guardAmOn 早上护学岗是否复制
     * @param guardPmOn 下午护学岗是否复制
     * @param holiday   法定节日名,可为 null/空
     * @param head      前置备注
     * @param tail      后置备注
     */
    public static String buildDayText(ScheduleModel.DayRow row, Map<String, String> edited,
                                      String[] guard, boolean guardAmOn, boolean guardPmOn,
                                      String holiday, String head, String tail) {
        if (row == null) return "";
        StringBuilder sb = new StringBuilder();
        String h = head == null ? "" : head.trim();
        if (!h.isEmpty()) sb.append(h).append('\n');
        sb.append(ScheduleModel.cnDate(row.date));
        String w = row.sheet.weekdayText(row);
        if (w != null && !w.trim().isEmpty()) sb.append('\n').append(w.trim());
        String hol = holiday == null ? "" : holiday.trim();
        if (!hol.isEmpty()) sb.append('（').append(hol).append('）');

        List<String> remarkLines = new ArrayList<>();
        for (ScheduleModel.GroupCol g : row.sheet.groups) {
            if (ScheduleModel.isSourceGuardColumn(g.label)) continue;   // 原表护学岗列不读取、不复制
            String raw = ScheduleModel.groupText(row, g.label);
            String text = (edited != null && edited.containsKey(g.label)) ? edited.get(g.label) : raw;
            text = cleanNames(ScheduleModel.stripRemarkPrefix(text));
            if (ScheduleModel.isRemarkEntry(raw)) {                 // "备注"行统一留到最后
                if (!text.isEmpty()) remarkLines.add(ScheduleModel.REMARK_LABEL + "：" + text);
                continue;
            }
            if (!text.isEmpty()) {
                // 值班组:复制时给"倒数第三人"缀上（值班室值守）;界面展示保持不变
                if (ScheduleModel.isDutyLabel(g.label)) text = ScheduleModel.markThirdFromLast(text);
                sb.append('\n').append(g.label).append('：').append(text);
            }
        }
        if (guard != null && guard.length >= 2) {
            if (guardAmOn && !guard[0].isEmpty()) {
                sb.append('\n').append(ScheduleModel.GUARD_AM).append('：').append(guard[0]);
            }
            if (guardPmOn && !guard[1].isEmpty()) {
                sb.append('\n').append(ScheduleModel.GUARD_PM).append('：').append(guard[1]);
            }
        }
        for (String line : remarkLines) sb.append('\n').append(line);
        String t = tail == null ? "" : tail.trim();
        if (!t.isEmpty()) sb.append('\n').append(t);
        return sb.toString();
    }

    /** 清理条目内容:去掉首尾顿号 */
    public static String cleanNames(String s) {
        if (s == null) return "";
        return s.replaceAll("^、+|、+$", "").trim();
    }
}
