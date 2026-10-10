package com.paiban.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PICK_FILE = 1;
    private static final String PREFS = "paiban_prefs";
    private static final String KEY_URI = "file_uri";
    private static final String KEY_NOTE_HEAD = "note_head";
    private static final String KEY_NOTE_TAIL = "note_tail";
    private static final String KEY_EDITS = "edits_map";

    private BottomNavigationView bottomNav;
    private TextView tvTitle;
    private ScrollView panelImport, panelMonth, panelDay;

    private TextView tvFile;
    private Button btnChoose;
    private EditText etNoteHead, etNoteTail;
    private Spinner spMonth;
    private LinearLayout llMonthDays;
    private TextView btnToday, btnTomorrow, tvDayDate;
    private LinearLayout llGroups;
    private Button btnCopy, btnReset;

    private Uri fileUri;
    private ScheduleModel.ParseResult parsed;
    private boolean useTomorrow = true;
    private boolean suppress = false;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    /** 当天排班页每行的内容控件:可编辑项是 EditText,派生的护学岗两行是只读 TextView */
    private final List<TextView> groupViews = new ArrayList<>();
    private final List<String> groupLabels = new ArrayList<>();
    /** 派生出的护学岗两行,编辑值班组时要立刻刷新它们 */
    private TextView tvGuardAm, tvGuardPm;

    /** 护学岗两段的手动开关(键=日期|标签);没手动点过的行跟随"当天是否国家法定工作日" */
    private final Map<String, Boolean> guardOverride = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
            prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            bindViews();
            setupListeners();
            purgeLegacyGuardEdits();
            switchTo("文件导入", panelImport);
            restoreFile();
            refreshDay();
        } catch (Throwable t) {
            try { Toast.makeText(this, "初始化失败", Toast.LENGTH_LONG).show(); } catch (Throwable ignored) { }
        }
    }

    private void bindViews() {
        bottomNav = findViewById(R.id.bottomNav);
        tvTitle = findViewById(R.id.tvTitle);
        panelImport = findViewById(R.id.panel_import);
        panelMonth = findViewById(R.id.panel_month);
        panelDay = findViewById(R.id.panel_day);
        tvFile = findViewById(R.id.tvFile);
        btnChoose = findViewById(R.id.btnChoose);
        etNoteHead = findViewById(R.id.etNoteHead);
        etNoteTail = findViewById(R.id.etNoteTail);
        spMonth = findViewById(R.id.spMonth);
        llMonthDays = findViewById(R.id.llMonthDays);
        btnToday = findViewById(R.id.btnToday);
        btnTomorrow = findViewById(R.id.btnTomorrow);
        tvDayDate = findViewById(R.id.tvDayDate);
        llGroups = findViewById(R.id.llGroups);
        btnCopy = findViewById(R.id.btnCopy);
        btnReset = findViewById(R.id.btnReset);
    }

    private void setupListeners() {
        etNoteHead.setText(prefs.getString(KEY_NOTE_HEAD, ""));
        etNoteHead.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable s) {
                prefs.edit().putString(KEY_NOTE_HEAD, s.toString()).apply();
            }
        });
        etNoteTail.setText(prefs.getString(KEY_NOTE_TAIL, ""));
        etNoteTail.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable s) {
                prefs.edit().putString(KEY_NOTE_TAIL, s.toString()).apply();
            }
        });

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_import) switchTo("文件导入", panelImport);
            else if (id == R.id.nav_month) switchTo("当月排班", panelMonth);
            else switchTo("当天排班", panelDay);
            return true;
        });

        btnChoose.setOnClickListener(v -> pickFile());
        btnToday.setOnClickListener(v -> { useTomorrow = false; refreshDay(); });
        btnTomorrow.setOnClickListener(v -> { useTomorrow = true; refreshDay(); });
        btnCopy.setOnClickListener(v -> copyDay());
        btnReset.setOnClickListener(v -> resetEdits());
        bindMonthSpinner();
    }

    private void switchTo(String title, ScrollView panel) {
        tvTitle.setText(title);
        panelImport.setVisibility(panel == panelImport ? View.VISIBLE : View.GONE);
        panelMonth.setVisibility(panel == panelMonth ? View.VISIBLE : View.GONE);
        panelDay.setVisibility(panel == panelDay ? View.VISIBLE : View.GONE);
        if (panel == panelMonth) refreshMonth();
        if (panel == panelDay) refreshDay();
    }

    private void restoreFile() {
        String uriStr = prefs.getString(KEY_URI, null);
        if (uriStr != null && !uriStr.isEmpty()) {
            try {
                fileUri = Uri.parse(uriStr);
                loadFileAsync(fileUri);
            } catch (Throwable ignored) { }
        }
    }

    // ---------------- 文件 ----------------

    private void pickFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_PICK_FILE);
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            fileUri = uri;
            prefs.edit().putString(KEY_URI, uri.toString()).apply();
            loadFileAsync(uri);
        }
    }

    private void loadFileAsync(Uri uri) {
        exec.execute(() -> {
            try {
                ScheduleModel.ParseResult pr;
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                    pr = ScheduleFile.parse(in);
                }
                ui.post(() -> {
                    parsed = pr;
                    tvFile.setTextColor(0xFF1C1C1E);
                    tvFile.setText(displayName(uri));
                    try { refreshMonth(); refreshDay(); } catch (Throwable ignored) { }
                });
            } catch (Throwable e) {
                ui.post(() -> {
                    parsed = null;
                    tvFile.setTextColor(0xFFFF3B30);
                    tvFile.setText("无法读取");
                    Toast.makeText(this, "仅支持 .xls / .xlsx", Toast.LENGTH_LONG).show();
                    try { refreshMonth(); refreshDay(); } catch (Throwable ignored) { }
                });
            }
        });
    }

    private String displayName(Uri uri) {
        try {
            Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                String name = null;
                if (c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) name = c.getString(idx);
                }
                c.close();
                if (name != null) return name;
            }
        } catch (Throwable ignored) { }
        String seg = uri.getLastPathSegment();
        return seg == null ? "排班表" : seg;
    }

    // ---------------- 当天 ----------------

    private LocalDate targetDate() {
        return useTomorrow ? LocalDate.now().plusDays(1) : LocalDate.now();
    }

    private void refreshDay() {
        try {
            btnToday.setBackground(useTomorrow ? null : getDrawable(R.drawable.seg_on));
            btnTomorrow.setBackground(useTomorrow ? getDrawable(R.drawable.seg_on) : null);
            btnToday.setTextColor(useTomorrow ? 0xFF8E8E93 : 0xFF1C1C1E);
            btnTomorrow.setTextColor(useTomorrow ? 0xFF1C1C1E : 0xFF8E8E93);
            populateDay();
        } catch (Throwable ignored) { }
    }

    /** 兼容旧数据:存储里可能是整行(带“标签：”),也可能是纯人名 */
    private String storedNames(String label, String stored) {
        if (stored == null) return "";
        String s = stored.trim();
        String prefix = label + "：";
        if (s.startsWith(prefix)) return s.substring(prefix.length()).trim();
        return s;
    }

    private void populateDay() {
        groupViews.clear();
        groupLabels.clear();
        tvGuardAm = null;
        tvGuardPm = null;
        llGroups.removeAllViews();
        LocalDate target = targetDate();
        tvDayDate.setText(ScheduleModel.cnDate(target) + "  " + ScheduleModel.cnWeekday(target));

        if (parsed == null || parsed.sheets.isEmpty()) return;
        ScheduleModel.DayRow row = ScheduleModel.findDay(parsed, target);
        if (row == null) return;

        // 行 = 源表里的各条目(可编辑,原样显示) + 末尾由值班组派生的护学岗两段(只读)
        // 早上护学岗 = 前一天值班组的后三人;下午护学岗 = 当天值班组的后三人
        String[] guard = guardFinal(target);
        JSONObject dateEdits = editsForDate(target);
        List<String> itemLabels = new ArrayList<>();   // 展示/复制用的标题
        List<String> itemKeys = new ArrayList<>();     // 编辑存储用的键(始终是源表列名,保证标题变化不影响已存内容)
        List<String> itemTexts = new ArrayList<>();
        List<Boolean> itemReadOnly = new ArrayList<>();
        // 以"备注"开头的条目先单独收着,统一排到最后一栏
        List<String> remarkLabels = new ArrayList<>();
        List<String> remarkKeys = new ArrayList<>();
        List<String> remarkTexts = new ArrayList<>();
        boolean hasDutyCol = false;
        for (ScheduleModel.GroupCol g : row.sheet.groups) {
            if (ScheduleModel.isSourceGuardColumn(g.label)) continue;   // 原表护学岗列不读取、不展示(已拆成早上/下午两段)
            String raw = ScheduleModel.groupText(row, g.label);
            boolean remark = ScheduleModel.isRemarkEntry(raw);   // 该格文字以"备注"开头 → 表头列名作废
            String shown = dateEdits.has(g.label)
                    ? storedNames(g.label, dateEdits.optString(g.label, ""))
                    : raw;
            if (remark) {
                remarkLabels.add(ScheduleModel.REMARK_LABEL);
                remarkKeys.add(g.label);
                remarkTexts.add(ScheduleModel.stripRemarkPrefix(shown));   // 去掉内容里重复的"备注"字样
            } else {
                itemLabels.add(g.label);
                itemKeys.add(g.label);
                itemTexts.add(shown);
                itemReadOnly.add(false);
            }
            if (ScheduleModel.isDutyLabel(g.label)) hasDutyCol = true;
        }
        if (hasDutyCol) {
            // 派生的护学岗两段排在普通条目之后
            itemLabels.add(ScheduleModel.GUARD_AM);
            itemKeys.add(ScheduleModel.GUARD_AM);
            itemTexts.add(guard[0]);
            itemReadOnly.add(true);
            itemLabels.add(ScheduleModel.GUARD_PM);
            itemKeys.add(ScheduleModel.GUARD_PM);
            itemTexts.add(guard[1]);
            itemReadOnly.add(true);
        }
        // "备注"行固定排在最后一栏(不管它在源表哪一列)
        for (int r = 0; r < remarkLabels.size(); r++) {
            itemLabels.add(remarkLabels.get(r));
            itemKeys.add(remarkKeys.get(r));
            itemTexts.add(remarkTexts.get(r));
            itemReadOnly.add(false);
        }

        suppress = true;
        int i = 0;
        try {
            for (int k = 0; k < itemLabels.size(); k++) {
                final String label = itemLabels.get(k);
                final String key = itemKeys.get(k);
                final boolean readOnly = itemReadOnly.get(k);
                String names = itemTexts.get(k);

                if (i > 0) {
                    View dv = new View(this);
                    dv.setBackgroundColor(0xFFE5E5EA);
                    llGroups.addView(dv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
                }
                i++;

                // 行:固定标签(不可改) + 内容;两者同字号同字体避免视觉差
                LinearLayout rowBox = new LinearLayout(this);
                rowBox.setOrientation(LinearLayout.HORIZONTAL);
                rowBox.setGravity(Gravity.CENTER_VERTICAL);
                rowBox.setPadding(0, 4, 0, 4);

                TextView lab = new TextView(this);
                lab.setText(label + "：");
                lab.setTextColor(0xFF1C1C1E);
                lab.setTextSize(18);
                lab.setTypeface(null, Typeface.NORMAL);
                lab.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                lab.setPadding(0, 0, dp(4), 0);
                LinearLayout.LayoutParams lpLab = new LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT);
                rowBox.addView(lab, lpLab);

                final TextView content;
                if (readOnly) {
                    TextView tv = new TextView(this);          // 派生的护学岗:只读
                    styleDayContent(tv);
                    content = tv;
                    if (ScheduleModel.GUARD_AM.equals(label)) tvGuardAm = tv;
                    else if (ScheduleModel.GUARD_PM.equals(label)) tvGuardPm = tv;
                } else {
                    EditText et = new EditText(this);
                    styleDayContent(et);
                    et.setBackground(null);
                    content = et;
                }
                content.setText(names == null ? "" : names);
                LinearLayout.LayoutParams lpEt = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                rowBox.addView(content, lpEt);

                // 护学岗两段:默认跟随法定工作日,点标题可单独重新激活/取消(只控制"要不要复制",不改人员)
                if (ScheduleModel.isGuardLabel(label)) {
                    applyGuardStyle(guardActive(target, label), lab, content);
                    lab.setOnClickListener(v -> {
                        boolean now = !guardActive(target, label);
                        guardOverride.put(dateKey(target) + "|" + label, now);
                        applyGuardStyle(now, lab, content);
                    });
                }

                if (!readOnly) {
                    ((EditText) content).addTextChangedListener(new SimpleWatcher() {
                        @Override public void afterTextChanged(Editable s) {
                            onLineEdited(target, key, s.toString());
                            // 改值班组 → 当天下午的护学岗立刻联动;早上的那条来自前一天,切到明天时自然反映
                            if (ScheduleModel.isDutyLabel(key)) refreshGuardRows(target);
                        }
                    });
                }

                llGroups.addView(rowBox);
                groupViews.add(content);
                groupLabels.add(label);
            }
        } catch (Throwable ignored) {
        } finally {
            suppress = false;   // 无论中途是否出错都必须恢复,否则后续所有编辑会被静默丢弃
        }
    }

    private void onLineEdited(LocalDate date, String label, String text) {
        if (suppress) return;
        try {
            JSONObject all = loadEdits();
            JSONObject d = all.has(dateKey(date)) ? all.getJSONObject(dateKey(date)) : new JSONObject();
            d.put(label, text);
            all.put(dateKey(date), d);
            prefs.edit().putString(KEY_EDITS, all.toString()).apply();
        } catch (JSONException ignored) { }
    }

    /** 某天"值班组"的最终文本:该天的编辑优先,否则取表格原值 */
    private String dutyFinal(LocalDate date) {
        try {
            JSONObject ed = editsForDate(date);
            if (ed.has(ScheduleModel.DUTY_LABEL)) {
                return storedNames(ScheduleModel.DUTY_LABEL, ed.optString(ScheduleModel.DUTY_LABEL, ""));
            }
        } catch (Throwable ignored) { }
        return ScheduleModel.dutyTextOfDay(parsed, date);
    }

    /**
     * 目标日两段护学岗的最终人员:{早上, 下午}。
     * 早上 = 前一天值班组的后三人;下午 = 当天值班组的后三人。
     */
    private String[] guardFinal(LocalDate target) {
        return new String[]{
                ScheduleModel.lastThree(dutyFinal(target.minusDays(1))),
                ScheduleModel.lastThree(dutyFinal(target))
        };
    }

    /** 值班组被编辑后,立刻刷新护学岗两行的内容(人员) */
    private void refreshGuardRows(LocalDate target) {
        try {
            String[] guard = guardFinal(target);
            if (tvGuardAm != null) tvGuardAm.setText(guard[0]);
            if (tvGuardPm != null) tvGuardPm.setText(guard[1]);
        } catch (Throwable ignored) { }
    }

    private void resetEdits() {
        JSONObject all = loadEdits();
        all.remove(dateKey(targetDate()));
        prefs.edit().putString(KEY_EDITS, all.toString()).apply();
        populateDay();
    }

    private JSONObject editsForDate(LocalDate date) {
        JSONObject all = loadEdits();
        try {
            if (all.has(dateKey(date))) return all.getJSONObject(dateKey(date));
        } catch (JSONException ignored) { }
        return new JSONObject();
    }

    private JSONObject loadEdits() {
        try {
            return new JSONObject(prefs.getString(KEY_EDITS, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private String dateKey(LocalDate d) {
        return d.getYear() + "-" + d.getMonthValue() + "-" + d.getDayOfMonth();
    }

    private void copyDay() {
        LocalDate target = targetDate();
        if (parsed == null || parsed.sheets.isEmpty()) {
            Toast.makeText(this, "未导入排班表", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ScheduleModel.findDay(parsed, target) == null) {
            Toast.makeText(this, "该日无排班", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = buildCopyText(target);
        Toast.makeText(this, copyToClipboard(text) ? "已复制" : "复制失败", Toast.LENGTH_SHORT).show();
    }

    /** 复制文本到剪贴板;成功返回 true */
    private boolean copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("排班", text));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 生成某天的"一键复制/长按复制"文本。
     * 拼装逻辑放在 CopyTextBuilder.buildDayText(纯 Java),桌面测试与 App 用的是同一套,保证格式一致。
     */
    private String buildCopyText(LocalDate date) {
        ScheduleModel.DayRow row = ScheduleModel.findDay(parsed, date);
        if (row == null) return "";
        return CopyTextBuilder.buildDayText(row, editsMap(date), guardFinal(date),
                guardActive(date, ScheduleModel.GUARD_AM), guardActive(date, ScheduleModel.GUARD_PM),
                holidayName(date), noteText(etNoteHead), noteText(etNoteTail));
    }

    /** 某天的编辑覆盖(键=源表列名);兼容旧数据里"标签：内容"的整行存法 */
    private Map<String, String> editsMap(LocalDate date) {
        Map<String, String> m = new HashMap<>();
        JSONObject o = editsForDate(date);
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            m.put(k, storedNames(k, o.optString(k, "")));
        }
        return m;
    }

    private static String noteText(EditText et) {
        return et == null || et.getText() == null ? "" : et.getText().toString();
    }

    // ---------------- 护校岗复制:仅国家法定工作日 ----------------

    /**
     * 2026 年法定节假日放假区间(国务院办公厅2025年11月4日《关于2026年部分节假日安排的通知》)。
     * 格式: {起始月, 起始日, 结束月, 结束日}。仅内置 2026 年;其他年份请按当年通知补充。
     */
    private static final int[][] LEGAL_HOLIDAYS_2026 = {
        {1, 1, 1, 3},    // 元旦:1/1(四)~1/3(六),1/4(日)上班
        {2, 15, 2, 23},  // 春节:2/15(日)~2/23(一),2/14(六)、2/28(六)上班
        {4, 4, 4, 6},    // 清明节:4/4(六)~4/6(一)
        {5, 1, 5, 5},    // 劳动节:5/1(五)~5/5(二),5/9(六)上班
        {6, 19, 6, 21},  // 端午节:6/19(五)~6/21(日)
        {9, 25, 9, 27},  // 中秋节:9/25(五)~9/27(日)
        {10, 1, 10, 7},  // 国庆节:10/1(四)~10/7(三),9/20(日)、10/10(六)上班
    };

    /** 与 LEGAL_HOLIDAYS_2026 逐条对应的节日名(顺序必须一致) */
    private static final String[] LEGAL_HOLIDAY_NAMES_2026 = {
        "元旦", "春节", "清明节", "劳动节", "端午节", "中秋节", "国庆节"
    };

    /** 2026 年调休上班日:虽是周六/周日,但属国家法定工作日,护校岗照常复制 */
    private static final int[][] MAKEUP_WORKDAYS_2026 = {
        {1, 4}, {2, 14}, {2, 28}, {5, 9}, {9, 20}, {10, 10}
    };

    /** 当天所属的法定节日名(如"中秋节");不在法定假期内、或不是 2026 年时返回空串 */
    private static String holidayName(LocalDate date) {
        if (date == null || date.getYear() != 2026) return "";
        int m = date.getMonthValue(), d = date.getDayOfMonth();
        for (int i = 0; i < LEGAL_HOLIDAYS_2026.length; i++) {
            if (inHolidayRange(m, d, LEGAL_HOLIDAYS_2026[i])) return LEGAL_HOLIDAY_NAMES_2026[i];
        }
        return "";
    }

    private static boolean inHolidayRange(int m, int d, int[] r) {
        return (m > r[0] || (m == r[0] && d >= r[1])) && (m < r[2] || (m == r[2] && d <= r[3]));
    }

    /** 当天是否国家法定工作日:周一~周五(非法定假日) + 调休上班的周六/周日 */
    private static boolean isLegalWorkday(LocalDate date) {
        int m = date.getMonthValue(), d = date.getDayOfMonth();
        int dow = date.getDayOfWeek().getValue(); // 1=周一 … 7=周日
        if (dow >= 6) { // 周六/周日
            if (date.getYear() == 2026) {
                for (int[] w : MAKEUP_WORKDAYS_2026) {
                    if (w[0] == m && w[1] == d) return true; // 调休上班日
                }
            }
            return false;
        }
        if (date.getYear() == 2026) {
            for (int[] h : LEGAL_HOLIDAYS_2026) {
                if (inHolidayRange(m, d, h)) return false; // 法定节假日
            }
        }
        return true;
    }

    /**
     * 护学岗某一段当前是否要复制:手动点过标题就用手动开关,
     * 没点过则跟随"当天是否国家法定工作日"。
     */
    private boolean guardActive(LocalDate date, String label) {
        Boolean manual = guardOverride.get(dateKey(date) + "|" + label);
        return manual != null ? manual : isLegalWorkday(date);
    }

    /** 护学岗某一段的样式:要复制=正常色,不复制=调暗 */
    private static void applyGuardStyle(boolean active, TextView lab, TextView content) {
        int c = active ? 0xFF1C1C1E : 0xFF8E8E93;
        lab.setTextColor(c);
        content.setTextColor(c);
    }

    // ---------------- 当月 ----------------

    private void refreshMonth() {
        try {
            if (parsed == null || parsed.sheets.isEmpty()) {
                llMonthDays.removeAllViews();
                spMonth.setAdapter(null);
                return;
            }
            List<String> names = new ArrayList<>();
            for (ScheduleModel.SheetData sd : parsed.sheets) {
                names.add(sd.name + "  " + ScheduleModel.cnDate(sd.firstDate) + "~" + ScheduleModel.cnDate(sd.lastDate));
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spMonth.setAdapter(adapter);

            LocalDate target = targetDate();
            int idx = 0;
            for (int i = 0; i < parsed.sheets.size(); i++) {
                ScheduleModel.SheetData sd = parsed.sheets.get(i);
                for (ScheduleModel.DayRow dr : sd.days) {
                    if (dr.date != null && dr.date.equals(target)) { idx = i; break; }
                }
            }
            spMonth.setSelection(idx);
        } catch (Throwable ignored) { }
    }

    private void bindMonthSpinner() {
        spMonth.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (parsed != null && pos >= 0 && pos < parsed.sheets.size()) {
                    renderMonthList(parsed.sheets.get(pos));
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
    }

    private void renderMonthList(ScheduleModel.SheetData sd) {
        llMonthDays.removeAllViews();
        if (sd == null) return;
        try {
            LayoutInflater inflater = LayoutInflater.from(this);
            for (ScheduleModel.DayRow row : sd.days) {
                if (row.date == null) continue;
                final LocalDate date = row.date;
                View card = inflater.inflate(R.layout.item_month_day, llMonthDays, false);
                TextView tvCardDate = card.findViewById(R.id.tvCardDate);
                TextView tvCardBody = card.findViewById(R.id.tvCardBody);
                tvCardDate.setText(ScheduleModel.cnDate(date) + "  " + sd.weekdayText(row));
                tvCardBody.setText(monthBody(sd, row));
                // 长按卡片 → 复制当天排班内容(与"一键复制"同一套文本)
                card.setOnLongClickListener(v -> {
                    String text = buildCopyText(date);
                    if (text.isEmpty()) {
                        Toast.makeText(this, "该日无排班", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    String tip = copyToClipboard(text)
                            ? "已复制 " + date.getMonthValue() + "月" + date.getDayOfMonth() + "日"
                            : "复制失败";
                    Toast.makeText(this, tip, Toast.LENGTH_SHORT).show();
                    return true;
                });
                llMonthDays.addView(card);
            }
        } catch (Throwable ignored) { }
    }

    /** 月视图卡片正文:按源表原有列原样显示,编辑记录优先 */
    private String monthBody(ScheduleModel.SheetData sd, ScheduleModel.DayRow row) {
        JSONObject dateEdits = editsForDate(row.date);
        StringBuilder sb = new StringBuilder();
        for (ScheduleModel.GroupCol g : sd.groups) {
            String raw = sd.groupContent(row, g);
            String label = ScheduleModel.isRemarkEntry(raw) ? ScheduleModel.REMARK_LABEL : g.label;
            String names = dateEdits.has(g.label)
                    ? storedNames(g.label, dateEdits.optString(g.label, ""))
                    : raw;
            names = CopyTextBuilder.cleanNames(ScheduleModel.stripRemarkPrefix(names));
            if (names.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(label).append("：").append(names);
        }
        return sb.length() == 0 ? "（无排班内容）" : sb.toString();
    }

    /** 老版本把护学岗两段当成普通可编辑条目存过;现在它们是派生的,清掉遗留键以免旧值干扰联动 */
    private void purgeLegacyGuardEdits() {
        try {
            JSONObject all = loadEdits();
            List<String> dates = new ArrayList<>();
            for (Iterator<String> it = all.keys(); it.hasNext(); ) dates.add(it.next());
            boolean changed = false;
            for (String d : dates) {
                JSONObject day = all.optJSONObject(d);
                if (day == null) continue;
                if (day.has(ScheduleModel.GUARD_AM)) { day.remove(ScheduleModel.GUARD_AM); changed = true; }
                if (day.has(ScheduleModel.GUARD_PM)) { day.remove(ScheduleModel.GUARD_PM); changed = true; }
            }
            if (changed) prefs.edit().putString(KEY_EDITS, all.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** 当天排班页每一行内容的统一样式:编辑框与只读的护学岗行高度、字号、内边距完全一致 */
    private void styleDayContent(TextView tv) {
        tv.setTextSize(18);
        tv.setTypeface(Typeface.DEFAULT);
        tv.setTextColor(0xFF1C1C1E);
        tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        tv.setLines(2);
        tv.setMaxLines(2);
        tv.setMinLines(2);
        tv.setVerticalScrollBarEnabled(false);
        tv.setPadding(0, dp(2), 0, dp(2));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------------- 生命周期 ----------------

    @Override
    protected void onResume() {
        super.onResume();
        try { refreshDay(); } catch (Throwable ignored) { }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            prefs.edit()
                .putString(KEY_NOTE_HEAD, etNoteHead.getText() == null ? "" : etNoteHead.getText().toString())
                .putString(KEY_NOTE_TAIL, etNoteTail.getText() == null ? "" : etNoteTail.getText().toString())
                .apply();
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdown();
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
    }
}