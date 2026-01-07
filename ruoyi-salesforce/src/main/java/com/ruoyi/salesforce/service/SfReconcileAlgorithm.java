package com.ruoyi.salesforce.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.text.csv.*;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.ruoyi.salesforce.domain.SfDataObjConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Salesforce 数据比对核心算法引擎 (终极分隔符修复版)
 */
@Slf4j
@Component
public class SfReconcileAlgorithm {

    private static final Pattern CRLF = Pattern.compile("\r\n|\r|\n");

    @Data
    public static class ReconcileStats {
        private int totalSource = 0;
        private int totalTarget = 0;
        private int diffCount = 0;
        private int missingTarget = 0;
        private int missingSource = 0;
        private List<Map<String, String>> previewList = new ArrayList<>();
    }

    public ReconcileStats execute(File sourceFile, File targetFile, File resultFile, SfDataObjConfig config) {
        log.info("开始执行算法比对，对象: {}", config.getObjectName());

        File sortedSource = externalSort(sourceFile, config.getSourceKeyField());
        File sortedTarget = externalSort(targetFile, config.getTargetKeyField());

        ReconcileStats stats = new ReconcileStats();
        try {
            doCompare(sortedSource, sortedTarget, resultFile, config, stats);
        } catch(Exception e) {
            log.error("比对过程发生异常", e);
            throw new RuntimeException(e);
        } finally {
//            FileUtil.del(sortedSource);
//            FileUtil.del(sortedTarget);
        }
        return stats;
    }

    private File externalSort(File inputFile, String keyField) {
        // 1. 读取所有行
        List<CsvRow> rows = readCsvRobust(inputFile);

        if(rows.isEmpty()) {
            log.warn("文件为空: {}", inputFile.getName());
            return inputFile;
        }

        // 2. 验证表头
        CsvRow header = rows.get(0);

        // --- 诊断日志区 ---
        if(header.size() <= 1) {
            log.error("【严重异常】文件 [{}] 解析后只有 1 列！", inputFile.getName());
            log.error(">>> 原始内容预览: {}", header.getRawList());
            // 尝试强制修复：如果是单列，尝试用逗号暴力拆分
            if(header.get(0).contains(",")) {
                log.info(">>> 检测到逗号，尝试暴力拆分修复...");
                // 这里无法直接修改 CsvRow，建议用户检查 SfBulkApiService 下载逻辑
            }
        }
        // ------------------

        int keyIndex = findColIndex(header, keyField);

        if(keyIndex == -1) {
            log.error("排序失败：文件 [{}] 未找到主键 [{}]。当前列数: {}", inputFile.getName(), keyField, header.size());
            log.error(">>> 实际表头: {}", header.getRawList());
            return inputFile;
        }

        // 3. 排序
        List<CsvRow> dataRows = new ArrayList<>(rows.subList(1, rows.size()));
        dataRows.sort((r1, r2) -> {
            String k1 = getNormalizedKey(r1, keyIndex);
            String k2 = getNormalizedKey(r2, keyIndex);
            if(k1 == null) return 1;
            if(k2 == null) return -1;
            return k1.compareTo(k2);
        });

        // 4. 写出排序后的文件
        File sortedFile = new File(inputFile.getParent(), "sorted_" + inputFile.getName());
        writeCsvRobust(sortedFile, header, dataRows);
        return sortedFile;
    }

    private void doCompare(File srcFile, File tgtFile, File resultFile, SfDataObjConfig config, ReconcileStats stats) throws Exception {
        // 使用增强读取方法
        List<CsvRow> srcRows = readCsvRobust(srcFile);
        List<CsvRow> tgtRows = readCsvRobust(tgtFile);

        Iterator<CsvRow> srcIter = srcRows.iterator();
        Iterator<CsvRow> tgtIter = tgtRows.iterator();

        CsvRow srcHeader = srcIter.hasNext() ? srcIter.next() : null;
        CsvRow tgtHeader = tgtIter.hasNext() ? tgtIter.next() : null;

        if(srcHeader == null || tgtHeader == null) {
            log.error("源文件或目标文件为空，无法比对");
            return;
        }

        try(FileOutputStream fos = new FileOutputStream(resultFile);
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {

            fos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // BOM
            CsvWriter writer = CsvUtil.getWriter(bw);
            writer.write(new String[]{"Source_Key", "Target_Key", "Diff_Type", "Field_Name", "Source_Value", "Target_Value"});

            int srcKeyIdx = findColIndex(srcHeader, config.getSourceKeyField());
            int tgtKeyIdx = findColIndex(tgtHeader, config.getTargetKeyField());

            if(srcKeyIdx == -1) {
                log.error("比对中止：源文件缺失主键 [{}]", config.getSourceKeyField());
                return;
            }

            CsvRow srcRow = srcIter.hasNext() ? srcIter.next() : null;
            CsvRow tgtRow = tgtIter.hasNext() ? tgtIter.next() : null;

            while(srcRow != null || tgtRow != null) {
                String srcKey = getNormalizedKey(srcRow, srcKeyIdx);
                String tgtKey = getNormalizedKey(tgtRow, tgtKeyIdx);

                int compare;
                if(srcKey == null && tgtKey == null) compare = 0;
                else if(srcKey == null) compare = 1;
                else if(tgtKey == null) compare = -1;
                else compare = srcKey.compareTo(tgtKey);

                if(compare == 0) {
                    stats.setTotalSource(stats.getTotalSource() + 1);
                    stats.setTotalTarget(stats.getTotalTarget() + 1);
                    boolean hasDiff = compareFields(srcRow, tgtRow, srcHeader, tgtHeader, writer, config, stats, srcKey, tgtKey);
                    if(hasDiff) stats.setDiffCount(stats.getDiffCount() + 1);
                    srcRow = srcIter.hasNext() ? srcIter.next() : null;
                    tgtRow = tgtIter.hasNext() ? tgtIter.next() : null;
                } else if(compare < 0) {
                    stats.setTotalSource(stats.getTotalSource() + 1);
                    stats.setMissingTarget(stats.getMissingTarget() + 1);
                    stats.setDiffCount(stats.getDiffCount() + 1);
                    writeDiff(writer, srcKey, "", "MISSING_IN_TARGET", "-", "Row Exists", "Row Missing", stats);
                    srcRow = srcIter.hasNext() ? srcIter.next() : null;
                } else {
                    stats.setTotalTarget(stats.getTotalTarget() + 1);
                    stats.setMissingSource(stats.getMissingSource() + 1);
                    stats.setDiffCount(stats.getDiffCount() + 1);
                    writeDiff(writer, "", tgtKey, "MISSING_IN_SOURCE", "-", "Row Missing", "Row Exists", stats);
                    tgtRow = tgtIter.hasNext() ? tgtIter.next() : null;
                }
            }
            writer.flush();
        }
    }

    /**
     * 【核心工具】健壮的 CSV 读取器
     * 强制使用 UTF-8，处理引号，忽略空行
     */
    private List<CsvRow> readCsvRobust(File file) {
        try {
            CsvReadConfig config = CsvReadConfig.defaultConfig();
            config.setFieldSeparator(','); // 显式逗号
            config.setTextDelimiter('\"'); // 显式双引号
            config.setTrimField(true);     // 去除字段空格
            config.setSkipEmptyRows(true);// 忽略空行

            // 强制指定 UTF-8 Reader，防止系统编码干扰
            return CsvUtil.getReader(config).read(FileUtil.getReader(file, StandardCharsets.UTF_8)).getRows();
        } catch(Exception e) {
            log.error("读取CSV异常: {}", file.getName(), e);
            return new ArrayList<>();
        }
    }

    /**
     * 【核心修复】写入 CSV 时，必须将 List 转换为 Array
     * 否则 Hutool 会误以为你要写多行，导致文件变成纵向！
     */
    private void writeCsvRobust(File file, CsvRow header, List<CsvRow> rows) {
        try (FileOutputStream fos = new FileOutputStream(file);
             BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {

            fos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // 写入 BOM
            CsvWriter writer = CsvUtil.getWriter(bw);

            // 【修复点 1】必须转为 String 数组！代表写入"一行"
            writer.write(header.getRawList().toArray(new String[0]));

            for (CsvRow row : rows) {
                // 【修复点 2】同理，数据行也必须转为数组
                writer.write(row.getRawList().toArray(new String[0]));
            }
            writer.flush();
        } catch (Exception e) {
            log.error("写入CSV异常", e);
        }
    }

    /**
     * 【核心工具】清洗表头：去除 BOM 和 引号
     */
    private String cleanHeader(String header) {
        if(header == null) return "";
        return header.replace("\uFEFF", "").replace("\"", "").trim();
    }

    private int findColIndex(CsvRow header, String colName) {
        if(header == null || colName == null) return -1;
        String search = cleanHeader(colName);
        for(int i = 0; i < header.size(); i++) {
            if(header.get(i) != null && search.equalsIgnoreCase(cleanHeader(header.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    // ... compareFields, findSmartColIndex, getNormalizedKey, isVisuallyEqual, writeDiff
    // (为了节省篇幅，请保留您之前版本中这几个逻辑正确的方法，它们不需要修改)

    private boolean compareFields(CsvRow src, CsvRow tgt, CsvRow srcHeader, CsvRow tgtHeader,
                                  CsvWriter writer, SfDataObjConfig config, ReconcileStats stats,
                                  String srcKey, String tgtKey) {
        boolean hasDiff = false;
        if(srcHeader == null || tgtHeader == null) return false;

        for(int i = 0; i < srcHeader.size(); i++) {
            if(srcHeader.get(i) == null) continue;
            String fieldName = cleanHeader(srcHeader.get(i));
            if(fieldName.equalsIgnoreCase(config.getSourceKeyField())) continue;

            int tgtIdx = findSmartColIndex(tgtHeader, fieldName, config.getTargetKeyField());
            if(tgtIdx != -1) {
                String sVal = src.get(i);
                String tVal = tgt.get(tgtIdx);
                if(!isVisuallyEqual(sVal, tVal)) {
                    writeDiff(writer, srcKey, tgtKey, "VALUE_DIFF", fieldName, sVal, tVal, stats);
                    hasDiff = true;
                }
            }
        }
        return hasDiff;
    }

    private int findSmartColIndex(CsvRow header, String colName, String targetKeyField) {
        int idx = findColIndex(header, colName);
        if(idx != -1) return idx;
        if(targetKeyField == null) targetKeyField = "Source_Org_Id__c";
        if(colName.endsWith("Id") || colName.endsWith("__c")) {
            String relName = null;
            if(colName.endsWith("__c")) relName = colName.substring(0, colName.length() - 3) + "__r";
            else if(colName.endsWith("Id")) relName = colName.substring(0, colName.length() - 2);
            if(relName != null) {
                String targetColName = relName + "." + targetKeyField;
                idx = findColIndex(header, targetColName);
            }
        }
        return idx;
    }

    private String getNormalizedKey(CsvRow row, int index) {
        if(index == -1 || row == null) return null;
        String val = row.get(index);
        if(val == null) return null;
        val = StrUtil.trim(val);
        if(val.length() == 18 && val.startsWith("00")) return val.substring(0, 15);
        return val;
    }

    private boolean isVisuallyEqual(String v1, String v2) {
        v1 = StrUtil.trimToEmpty(v1);
        v2 = StrUtil.trimToEmpty(v2);
        if(v1.equals(v2)) return true;
        String v1Norm = CRLF.matcher(v1).replaceAll("\n");
        String v2Norm = CRLF.matcher(v2).replaceAll("\n");
        if(v1Norm.equals(v2Norm)) return true;
        if(NumberUtil.isNumber(v1) && NumberUtil.isNumber(v2)) {
            try {
                BigDecimal b1 = NumberUtil.toBigDecimal(v1);
                BigDecimal b2 = NumberUtil.toBigDecimal(v2);
                if(b1.compareTo(b2) == 0) return true;
            } catch(Exception e) {
            }
        }
        if(v1.length() >= 15 && v2.length() >= 15) {
            if(v1.substring(0, 15).equals(v2.substring(0, 15))) return true;
        }
        return false;
    }

    private void writeDiff(CsvWriter writer, String sKey, String tKey, String type, String field, String sVal, String tVal, ReconcileStats stats) {
        String safeS = sVal == null ? "" : StrUtil.sub(sVal, 0, 2000);
        String safeT = tVal == null ? "" : StrUtil.sub(tVal, 0, 2000);
        String safeField = field == null ? "" : field;
        writer.write(new String[]{sKey, tKey, type, safeField, safeS, safeT});
        if(stats.getPreviewList().size() < 50) {
            Map<String, String> item = new HashMap<>();
            item.put("key", "MISSING".equals(type) || "MISSING_IN_TARGET".equals(type) ? sKey : tKey);
            item.put("type", type);
            item.put("field", safeField);
            item.put("srcVal", safeS);
            item.put("tgtVal", safeT);
            stats.getPreviewList().add(item);
        }
    }
}