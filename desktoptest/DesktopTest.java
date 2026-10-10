import com.paiban.app.ScheduleFile;
import com.paiban.app.ScheduleModel;
import com.paiban.app.CopyTextBuilder;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * 桌面验证工具(不参与 APK 构建)。
 *   DesktopTest -selftest                       纯函数自检:人名拆分/后三人
 *   DesktopTest <表格> [yyyy-MM-dd] [-note=..] [-out=报告文件]
 */
public class DesktopTest {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "-selftest".equals(args[0])) { selftest(); return; }
        String file = args[0];
        LocalDate target = args.length > 1 ? LocalDate.parse(args[1]) : LocalDate.now().plusDays(1);
        String note = "";
        String outFile = null;
        for (String a : args) if (a.startsWith("-note=")) note = a.substring(6);
        for (String a : args) if (a.startsWith("-out=")) outFile = a.substring(5);

        ScheduleModel.ParseResult pr;
        try (FileInputStream fis = new FileInputStream(file)) {
            pr = ScheduleFile.parse(fis);
        }
        StringBuilder log = new StringBuilder();
        log.append("== sheets (").append(pr.sheets.size()).append(") ==\n");
        for (ScheduleModel.SheetData sd : pr.sheets) {
            log.append("  ").append(sd.name).append(" | 表头r").append(sd.headerRow)
               .append(" | 组数").append(sd.groups.size()).append(" | 天数").append(sd.days.size())
               .append(" | ").append(sd.firstDate == null ? "-" : sd.firstDate)
               .append(" ~ ").append(sd.lastDate == null ? "-" : sd.lastDate).append("\n");
            for (ScheduleModel.GroupCol g : sd.groups)
                log.append("     组: ").append(g.label).append(" 列=").append(g.cols).append("\n");
        }
        log.append("== 目标: ").append(target).append(" ==\n");
        ScheduleModel.DayRow row = ScheduleModel.findDay(pr, target);
        String[] guard = ScheduleModel.guardDuty(pr, target);
        String body;
        if (row == null) {
            body = "NOT FOUND. 全表: " + pr.overallFirst() + " ~ " + pr.overallLast();
        } else {
            log.append("命中: 表[").append(row.sheet.name).append("] r").append(row.rowIndex).append("\n");
            // 与 App 的"一键复制"完全同一套拼装(护学岗两段都打开,便于观察)
            body = CopyTextBuilder.buildDayText(row, new HashMap<String, String>(), guard, true, true,
                    "", note, "");
        }
        // ---- 护学岗派生(值班组后三人) ----
        log.append("值班组[目标日]: ").append(ScheduleModel.dutyTextOfDay(pr, target)).append("\n");
        log.append("值班组[前一天]: ").append(ScheduleModel.dutyTextOfDay(pr, target.minusDays(1))).append("\n");
        log.append("早上护学岗 = 前一天值班组后三人: ").append(guard[0]).append("\n");
        log.append("下午护学岗 = 当天值班组后三人: ").append(guard[1]).append("\n");

        // ---- 逐列:原标题 / 是否"备注"行 / 最终标题与内容 ----
        if (row != null) {
            log.append("== 当日各列(标题按内容自动修正) ==\n");
            for (ScheduleModel.GroupCol g : row.sheet.groups) {
                String raw = ScheduleModel.groupText(row, g.label);
                if (ScheduleModel.isSourceGuardColumn(g.label)) {
                    log.append("  源表标题[").append(g.label).append("] 原文=[").append(raw)
                       .append("] → 当天排班页/复制:跳过(改用值班组派生的早上/下午两段);当月排班:按原表显示\n");
                    continue;
                }
                boolean remark = ScheduleModel.isRemarkEntry(raw);
                log.append("  源表标题[").append(g.label).append("]")
                   .append(" 原文=[").append(raw).append("]")
                   .append(" → 标题=[").append(remark ? ScheduleModel.REMARK_LABEL : g.label).append("]")
                   .append(" 内容=[").append(ScheduleModel.stripRemarkPrefix(raw)).append("]")
                   .append(remark ? " (固定排最后)" : "").append("\n");
            }
        }

        if (outFile != null) {
            Files.write(Paths.get(outFile), (log + "--- 复制文本 ---\n" + body + "\n--- 结束 ---\n").getBytes(StandardCharsets.UTF_8));
            System.out.println("written: " + outFile);
        } else {
            System.out.println(log);
            System.out.println("--- 复制文本 ---");
            System.out.println(body);
            System.out.println("--- 结束 ---");
        }
    }

    // ---------------- 纯函数自检 ----------------

    private static int pass = 0, fail = 0;

    private static void check(String what, String expect, String actual) {
        boolean ok = expect == null ? actual == null : expect.equals(actual);
        if (ok) pass++; else fail++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + "  expect=[" + expect + "] actual=[" + actual + "]");
    }

    private static void check(String what, int expect, int actual) {
        boolean ok = expect == actual;
        if (ok) pass++; else fail++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + "  expect=" + expect + " actual=" + actual);
    }

    private static void check(String what, boolean expect, boolean actual) {
        boolean ok = expect == actual;
        if (ok) pass++; else fail++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + "  expect=" + expect + " actual=" + actual);
    }

    private static void selftest() {
        System.out.println("== lastThree(值班组后三人) ==");
        check("5人取后三", "丙、丁、戊", ScheduleModel.lastThree("甲、乙、丙、丁、戊"));
        check("6人取后三", "丁、戊、己", ScheduleModel.lastThree("甲、乙、丙、丁、戊、己"));
        check("正好3人", "甲、乙、丙", ScheduleModel.lastThree("甲、乙、丙"));
        check("2人全取", "甲、乙", ScheduleModel.lastThree("甲、乙"));
        check("1人全取", "甲", ScheduleModel.lastThree("甲"));
        check("空文本", "", ScheduleModel.lastThree(""));
        check("null", "", ScheduleModel.lastThree(null));
        check("混合分隔符", "丙、丁、戊", ScheduleModel.lastThree("甲，乙;丙 丁、戊"));
        check("全角空格分隔", "乙、丙、丁", ScheduleModel.lastThree("甲　乙　丙　丁"));
        check("换行分隔", "乙、丙、丁", ScheduleModel.lastThree("甲\n乙\n丙\n丁"));
        check("首尾多余顿号", "甲、乙、丙", ScheduleModel.lastThree("、甲、乙、丙、"));
        check("连续顿号", "乙、丙、丁", ScheduleModel.lastThree("甲、、乙、丙、丁"));

        System.out.println("== splitNames ==");
        List<String> n = ScheduleModel.splitNames("甲、乙、丙");
        check("拆分个数", 3, n.size());
        check("拆分首项", "甲", n.get(0));
        check("空文本拆分", 0, ScheduleModel.splitNames("   ").size());

        System.out.println("== isDutyLabel / isGuardLabel ==");
        check("值班组", true, ScheduleModel.isDutyLabel("值班组"));
        check("值班组带空格", true, ScheduleModel.isDutyLabel(" 值班 组 "));
        check("A组不是值班组", false, ScheduleModel.isDutyLabel("A组"));
        check("早上护学岗是派生的护学岗行", true, ScheduleModel.isGuardLabel("早上护学岗"));
        check("下午护学岗是派生的护学岗行", true, ScheduleModel.isGuardLabel("下午护学岗"));
        check("值班组不是护学岗", false, ScheduleModel.isGuardLabel("值班组"));
        check("源表列名'护学岗'按普通列处理", false, ScheduleModel.isGuardLabel("护学岗"));
        check("'护学岗'是源表护学岗列", true, ScheduleModel.isSourceGuardColumn("护学岗"));
        check("'护校岗'是源表护学岗列", true, ScheduleModel.isSourceGuardColumn("护校岗"));
        check("'值班组'不是护学岗列", false, ScheduleModel.isSourceGuardColumn("值班组"));
        check("派生的'早上护学岗'也算护学岗字样", true, ScheduleModel.isSourceGuardColumn("早上护学岗"));

        System.out.println("== 备注行识别与标题替换 ==");
        check("以备注开头", true, ScheduleModel.isRemarkEntry("备注：明天休息"));
        check("只有备注二字", true, ScheduleModel.isRemarkEntry("备注"));
        check("备注中间有空格", true, ScheduleModel.isRemarkEntry("备 注 明天休息"));
        check("不以备注开头", false, ScheduleModel.isRemarkEntry("值班要求"));
        check("null 不是备注", false, ScheduleModel.isRemarkEntry(null));
        check("去前缀(全角冒号)", "明天休息", ScheduleModel.stripRemarkPrefix("备注：明天休息"));
        check("去前缀(半角冒号+空格)", "明天休息", ScheduleModel.stripRemarkPrefix("备注: 明天休息"));
        check("去前缀(顿号)", "明天休息", ScheduleModel.stripRemarkPrefix("备注、明天休息"));
        check("去前缀(无分隔符)", "明天休息", ScheduleModel.stripRemarkPrefix("备注明天休息"));
        check("去前缀(中间有空格)", "明天休息", ScheduleModel.stripRemarkPrefix("备 注 明天休息"));
        check("只有备注二字时内容为空", "", ScheduleModel.stripRemarkPrefix("备注"));
        check("非备注文本原样返回", "值班要求", ScheduleModel.stripRemarkPrefix("值班要求"));
        check("null 安全", "", ScheduleModel.stripRemarkPrefix(null));

        System.out.println("PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }
}
