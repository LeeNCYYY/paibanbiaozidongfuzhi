package com.example.dailysheetcopier;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.example.dailysheetcopier.databinding.ActivityMainBinding;
import com.example.dailysheetcopier.databinding.ItemRowBinding;
import com.google.android.material.button.MaterialButton;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private List<String> effectiveHeaders = new ArrayList<>();
    private List<List<String>> allRowsData = new ArrayList<>(); 
    private List<List<String>> todayRowsData = new ArrayList<>();
    private Set<Integer> globalDisabledIndices = new HashSet<>(); 
    private DataAdapter adapter;
    private SharedPreferences prefs;
    private int currentMode = 1;
    private List<String> templateSlots = new ArrayList<>();
    private int todayRowIndexInAll = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefs = getSharedPreferences("DailyCopier", MODE_PRIVATE);
        binding.etFullNote.setText(prefs.getString("user_note", ""));

        initTemplateSystem();
        adapter = new DataAdapter();
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setAdapter(adapter);
        
        if (binding.recyclerView.getItemAnimator() instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) binding.recyclerView.getItemAnimator()).setSupportsChangeAnimations(false);
        }

        ActivityResultLauncher<String[]> filePicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) parseExcel(uri); }
        );

        binding.btnImport.setOnClickListener(v -> { saveNote(); filePicker.launch(new String[]{
                "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        }); });
        binding.btnNote.setOnClickListener(v -> switchView(2));
        binding.btnAll.setOnClickListener(v -> switchView(0));
        binding.btnToday.setOnClickListener(v -> switchView(1));
        binding.btnCloseEditor.setOnClickListener(v -> binding.attributeEditorLayer.setVisibility(View.GONE));

        switchView(1);
    }

    private void initTemplateSystem() {
        refreshTemplateSlots();
        binding.autoCompleteTemplates.setOnItemClickListener((p, v, pos, id) -> {
            String numStr = templateSlots.get(pos).split("\\.")[0];
            binding.etFullNote.setText(prefs.getString("template_slot_" + numStr, ""));
            // 确保选择后显示文本的最左侧（前部分）
            binding.autoCompleteTemplates.setSelection(0);
        });
        binding.btnSaveTemplate.setOnClickListener(v -> {
            String txt = binding.autoCompleteTemplates.getText().toString();
            if (txt.isEmpty()) return;
            String numPart = txt.split("\\.")[0];
            try {
                int idx = Integer.parseInt(numPart.trim());
                prefs.edit().putString("template_slot_" + idx, binding.etFullNote.getText().toString()).apply();
                Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
                refreshTemplateSlots();
            } catch (Exception e) {}
        });
    }

    private void refreshTemplateSlots() {
        templateSlots.clear();
        for (int i = 1; i <= 10; i++) {
            String saved = prefs.getString("template_slot_" + i, "").trim();
            String firstLine = saved.split("\n")[0];
            // 限制长度以免过长，显示前部分内容
            String preview = firstLine.length() > 15 ? firstLine.substring(0, 15) + "..." : firstLine;
            templateSlots.add(i + ". " + (preview.isEmpty() ? "" : preview));
        }
        ArrayAdapter<String> tAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, templateSlots);
        binding.autoCompleteTemplates.setAdapter(tAdapter);
    }

    private void saveNote() { prefs.edit().putString("user_note", binding.etFullNote.getText().toString()).apply(); }

    private void switchView(int viewType) {
        saveNote();
        currentMode = viewType;
        binding.attributeEditorLayer.setVisibility(View.GONE);
        binding.recyclerView.setVisibility(viewType == 2 ? View.GONE : View.VISIBLE);
        binding.noteContainer.setVisibility(viewType == 2 ? View.VISIBLE : View.GONE);
        if (viewType != 2) {
            updateDisplay(viewType);
            if (viewType == 0 && todayRowIndexInAll != -1) {
                binding.recyclerView.post(() -> ((LinearLayoutManager)binding.recyclerView.getLayoutManager()).scrollToPositionWithOffset(todayRowIndexInAll, 0));
            }
        }
        updateButtonColors(viewType);
    }

    private void updateButtonColors(int type) {
        int cA = ContextCompat.getColor(this, R.color.purple_active);
        int cI = ContextCompat.getColor(this, R.color.purple_light);
        int tA = ContextCompat.getColor(this, R.color.purple_text_active);
        int tI = ContextCompat.getColor(this, R.color.purple_text_inactive);
        setButtonStyle(binding.btnAll, type == 0, cA, cI, tA, tI);
        setButtonStyle(binding.btnToday, type == 1, cA, cI, tA, tI);
        setButtonStyle(binding.btnNote, type == 2, cA, cI, tA, tI);
        setButtonStyle(binding.btnImport, false, cA, cI, tA, tI);
    }

    private void setButtonStyle(MaterialButton b, boolean act, int ca, int ci, int ta, int ti) {
        b.setBackgroundTintList(ColorStateList.valueOf(act ? ca : ci));
        b.setTextColor(act ? ta : ti);
    }

    private void parseExcel(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            Workbook wb = WorkbookFactory.create(is);
            Sheet sheet = wb.getSheetAt(0);
            effectiveHeaders.clear();
            allRowsData.clear();
            todayRowsData.clear();
            todayRowIndexInAll = -1;

            int maxCol = 0;
            int scanLimit = Math.min(sheet.getLastRowNum() + 1, 100);
            for (int i = 0; i < scanLimit; i++) {
                Row r = sheet.getRow(i);
                if (r != null) maxCol = Math.max(maxCol, (int)r.getLastCellNum());
            }

            // 1. 合并单元格标题：每一列都返回相同的完整标题名
            for (int c = 0; c < maxCol; c++) {
                effectiveHeaders.add(getMergedHeaderName(sheet, 0, c));
            }

            // 2. 超强今日日期比对器
            Calendar cal = Calendar.getInstance();
            int curM = cal.get(Calendar.MONTH) + 1;
            int curD = cal.get(Calendar.DAY_OF_MONTH);
            long todayTs = getNormalizedToday();

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                List<String> rowList = new ArrayList<>();
                boolean isToday = false;
                for (int j = 0; j < effectiveHeaders.size(); j++) {
                    Cell cell = row.getCell(j);
                    String val = getCellValue(cell);
                    rowList.add(val);
                    if (!val.isEmpty()) {
                        // 逻辑：寻找数字指纹，如 "4-19" 或 "4.19" 或 "4/19"
                        if (matchDateFingerprint(val, curM, curD)) isToday = true;
                        if (cell != null && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                            if (getNormalizedTime(cell.getDateCellValue()) == todayTs) isToday = true;
                        }
                    }
                }
                allRowsData.add(rowList);
                if (isToday) {
                    todayRowsData.add(rowList);
                    if (todayRowIndexInAll == -1) todayRowIndexInAll = allRowsData.size() - 1;
                }
            }
            updateDisplay(currentMode == 2 ? 1 : currentMode);
        } catch (Exception e) { e.printStackTrace(); }
    }

    private boolean matchDateFingerprint(String text, int month, int day) {
        // 匹配任意包含 M...D 顺序数字的字符串
        String regex = ".*" + month + "[\\.\\-/月\\s]{1,2}" + day + ".*";
        Pattern p = Pattern.compile(regex);
        Matcher m = p.matcher(text.replace(" ", ""));
        return m.matches();
    }

    private String getMergedHeaderName(Sheet s, int r, int c) {
        String name = "";
        for (int i = 0; i < s.getNumMergedRegions(); i++) {
            CellRangeAddress range = s.getMergedRegion(i);
            if (range.isInRange(r, c)) {
                Row firstRow = s.getRow(range.getFirstRow());
                name = (firstRow != null) ? getCellValue(firstRow.getCell(range.getFirstColumn())) : "";
                break;
            }
        }
        if (name.isEmpty()) {
            Row row = s.getRow(r);
            name = (row != null && row.getCell(c) != null) ? getCellValue(row.getCell(c)) : "";
        }
        // 去除末尾的序号（数字及可能的小数点/空格）
        return name.replaceAll("\\d+\\.?\\s*$", "").trim();
    }

    private String getCellValue(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue().trim();
        if (cell.getCellType() == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cell.getDateCellValue());
            double v = cell.getNumericCellValue();
            return (v == (long) v) ? String.valueOf((long) v) : String.valueOf(v);
        }
        return "";
    }

    private long getNormalizedToday() {
        Calendar c = Calendar.getInstance(); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
    private long getNormalizedTime(Date date) {
        Calendar c = Calendar.getInstance(); c.setTime(date);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private String formatToCh(String in) {
        if (in == null || in.isEmpty()) return "";
        try {
            String c = in.replace("/", "-").replace(".", "-");
            if (c.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) return new SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault()).format(new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(c));
        } catch (Exception e) {}
        return in;
    }

    private void updateDisplay(int mode) {
        List<List<String>> data = (mode == 1) ? todayRowsData : allRowsData;
        adapter.setMode(mode == 1);
        adapter.setData(new ArrayList<>(data));
        binding.emptyView.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void openAttributeEditor(List<String> row) {
        Set<Integer> localDisabled = new HashSet<>();
        binding.attributeEditorLayer.setVisibility(View.VISIBLE);
        binding.rvEditor.setLayoutManager(new LinearLayoutManager(this));
        binding.rvEditor.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
                return new RecyclerView.ViewHolder(LayoutInflater.from(p.getContext()).inflate(R.layout.item_data_pair, p, false)) {};
            }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
                String header = effectiveHeaders.get(pos);
                String val = row.get(pos);
                if (val.isEmpty()) { h.itemView.setVisibility(View.GONE); h.itemView.setLayoutParams(new RecyclerView.LayoutParams(0,0)); return; }
                TextView tvL = h.itemView.findViewById(R.id.tvLabel);
                EditText etV = h.itemView.findViewById(R.id.etValue);
                // 加大字号一半 (15sp -> 22.5sp)
                tvL.setTextSize(22.5f);
                etV.setTextSize(22.5f);
                tvL.setText(header + "：");
                etV.setText(val);
                etV.setFocusable(true); etV.setFocusableInTouchMode(true);
                etV.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                    @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                    @Override public void afterTextChanged(Editable s) { row.set(pos, s.toString()); }
                });
                boolean off = localDisabled.contains(pos);
                ((ImageView)h.itemView.findViewById(R.id.ivToggleIcon)).setImageResource(off ? R.drawable.ic_pure_cross : R.drawable.ic_pure_hook);
                h.itemView.findViewById(R.id.btnToggle).setOnClickListener(v -> { if (off) localDisabled.remove(pos); else localDisabled.add(pos); notifyItemChanged(pos); });
            }
            @Override public int getItemCount() { return effectiveHeaders.size(); }
        });
        binding.btnCopyInEditor.setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder();
            for (int j=0; j<effectiveHeaders.size(); j++) {
                if (!localDisabled.contains(j) && !row.get(j).isEmpty()) {
                    sb.append(effectiveHeaders.get(j)).append("：").append(formatToCh(row.get(j))).append("\n");
                }
            }
            ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("Excel", (sb.toString() + prefs.getString("user_note", "")).trim()));
            binding.attributeEditorLayer.setVisibility(View.GONE);
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });
    }

    class DataAdapter extends RecyclerView.Adapter<DataAdapter.ViewHolder> {
        private List<List<String>> data = new ArrayList<>();
        private boolean isTodayMode = false;
        public void setData(List<List<String>> d) { this.data = d; notifyDataSetChanged(); }
        public void setMode(boolean b) { this.isTodayMode = b; }
        @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new ViewHolder(ItemRowBinding.inflate(LayoutInflater.from(p.getContext()), p, false));
        }
        @Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            List<String> rowValues = data.get(position);
            holder.binding.contentContainer.removeAllViews();
            StringBuilder copySb = new StringBuilder();
            holder.binding.btnEditAttr.setVisibility(isTodayMode ? View.GONE : View.VISIBLE);
            holder.binding.btnEditAttr.setOnClickListener(v -> openAttributeEditor(rowValues));
            for (int j = 0; j < effectiveHeaders.size(); j++) {
                String val = rowValues.get(j);
                if (val == null || val.isEmpty()) continue;
                View v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_data_pair, holder.binding.contentContainer, false);
                TextView tvL = v.findViewById(R.id.tvLabel);
                EditText etV = v.findViewById(R.id.etValue);
                if (isTodayMode) {
                    tvL.setTextSize(22.5f);
                    etV.setTextSize(22.5f);
                }
                tvL.setText(effectiveHeaders.get(j) + "：");
                etV.setText(val);
                etV.setFocusable(isTodayMode); etV.setFocusableInTouchMode(isTodayMode);
                if (isTodayMode) {
                    final int idx = j;
                    etV.addTextChangedListener(new TextWatcher() {
                        @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                        @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                        @Override public void afterTextChanged(Editable s) { rowValues.set(idx, s.toString()); }
                    });
                }
                boolean off = isTodayMode && globalDisabledIndices.contains(j);
                v.findViewById(R.id.btnToggle).setVisibility(isTodayMode ? View.VISIBLE : View.GONE);
                ((ImageView)v.findViewById(R.id.ivToggleIcon)).setImageResource(off ? R.drawable.ic_pure_cross : R.drawable.ic_pure_hook);
                final int idx = j;
                v.findViewById(R.id.btnToggle).setOnClickListener(view -> { if (globalDisabledIndices.contains(idx)) globalDisabledIndices.remove(idx); else globalDisabledIndices.add(idx); notifyItemChanged(position); });
                if (!off) copySb.append(effectiveHeaders.get(j)).append("：").append(formatToCh(val)).append("\n");
                holder.binding.contentContainer.addView(v);
            }
            holder.binding.btnCopy.setOnClickListener(v -> {
                ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cb.setPrimaryClip(ClipData.newPlainText("Excel", (copySb.toString() + prefs.getString("user_note", "")).trim()));
                Toast.makeText(MainActivity.this, "已复制", Toast.LENGTH_SHORT).show();
            });
        }
        @Override public int getItemCount() { return data.size(); }
        class ViewHolder extends RecyclerView.ViewHolder {
            ItemRowBinding binding;
            ViewHolder(ItemRowBinding b) { super(b.getRoot()); this.binding = b; }
        }
    }
}
