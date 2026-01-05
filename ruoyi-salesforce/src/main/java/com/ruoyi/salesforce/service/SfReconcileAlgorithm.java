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
 * Salesforce 数据比对核心算法引擎 (最终增强版)
 * 修复：
 * 1. 解决 Key 不匹配导致 compareFields 进不去的问题 (15/18位兼容 + 去空格)
 * 2. 增加采样调试日志，明确显示 Key 差异
 * 3. 增强数值、换行符等值的容错比对
 */
@Slf4j
@Component
public class SfReconcileAlgorithm {

    private static final Pattern CRLF = Pattern.compile("\r\n");

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

        // 1. 排序
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

    /**
     * 【核心方法】获取归一化的 Key
     * 作用：去除 BOM、去除空格、统一 ID 长度，确保 Source 和 Target 能对上号
     */
    private String getNormalizedKey(CsvRow row, int index) {
        if(index == -1 || row == null) return null;
        String val = row.get(index);
        if(val == null) return null;
        val = StrUtil.trim(val);
        // 15/18位兼容
        if(val.length() == 18 && val.startsWith("00")) {
            return val.substring(0, 15);
        }
        return val;
    }

    private File externalSort(File inputFile, String keyField) {
        log.info("正在对文件进行本地排序: {}", inputFile.getName());
        CsvReader reader = CsvUtil.getReader();
        List<CsvRow> rows = reader.read(inputFile).getRows();
        if(rows.isEmpty()) return inputFile;

        CsvRow header = rows.get(0);
        int keyIndex = findColIndex(header, keyField);

        if(keyIndex == -1) {
            log.warn("文件中未找到关联键: {}, 无法排序", keyField);
            return inputFile;
        }

        List<CsvRow> dataRows = new ArrayList<>(rows.subList(1, rows.size()));

        // 【优化】排序时使用归一化的 Key
        dataRows.sort((r1, r2) -> {
            String k1 = getNormalizedKey(r1, keyIndex);
            String k2 = getNormalizedKey(r2, keyIndex);

            if(k1 == null && k2 == null) return 0;
            if(k1 == null) return 1;
            if(k2 == null) return -1;
            return k1.compareTo(k2);
        });

        File sortedFile = new File(inputFile.getParent(), "sorted_" + inputFile.getName());
        CsvWriter writer = CsvUtil.getWriter(sortedFile, StandardCharsets.UTF_8);
        writer.write(header.getRawList());
        for(CsvRow row : dataRows) {
            writer.write(row.getRawList());
        }
        writer.close();
        return sortedFile;
    }

    private void doCompare(File srcFile, File tgtFile, File resFile, SfDataObjConfig config, ReconcileStats stats) throws Exception {
        CsvReader srcReader = CsvUtil.getReader();
        CsvReader tgtReader = CsvUtil.getReader();

        // Fix #1: 手动创建 Writer 并写入 BOM 头，解决 Excel 中文乱码
        FileOutputStream fos = new FileOutputStream(resFile);
        fos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // UTF-8 BOM
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8));
        CsvWriter writer = CsvUtil.getWriter(bw);

        writer.write(new String[]{"Source_Key", "Target_Key", "Diff_Type", "Field_Name", "Source_Value", "Target_Value"});

        Iterator<CsvRow> srcIter = srcReader.read(srcFile).iterator();
        Iterator<CsvRow> tgtIter = tgtReader.read(tgtFile).iterator();

        CsvRow srcHeader = srcIter.hasNext() ? srcIter.next() : null;
        CsvRow tgtHeader = tgtIter.hasNext() ? tgtIter.next() : null;

        CsvRow srcRow = srcIter.hasNext() ? srcIter.next() : null;
        CsvRow tgtRow = tgtIter.hasNext() ? tgtIter.next() : null;

        int srcKeyIdx = findColIndex(srcHeader, config.getSourceKeyField());
        int tgtKeyIdx = findColIndex(tgtHeader, config.getTargetKeyField());

        if(srcKeyIdx == -1 || tgtKeyIdx == -1) {
            log.error("无法找到主键列, 停止比对");
            writer.close();
            return;
        }

        while(srcRow != null || tgtRow != null) {
            String srcKey = getNormalizedKey(srcRow, srcKeyIdx);
            String tgtKey = getNormalizedKey(tgtRow, tgtKeyIdx);

            int compare = 0;
            if(srcKey == null && tgtKey == null) compare = 0;
            else if(srcKey == null) compare = 1;
            else if(tgtKey == null) compare = -1;
            else compare = srcKey.compareTo(tgtKey);

            if(compare == 0) {
                // Key 匹配，比对字段
                stats.setTotalSource(stats.getTotalSource() + 1);
                stats.setTotalTarget(stats.getTotalTarget() + 1);

                // Fix #2: 只有 Key 匹配时才去比对字段差异 (VALUE_DIFF)
                boolean rowHasDiff = compareFields(srcRow, tgtRow, srcHeader, tgtHeader, writer, config, stats, srcKey, tgtKey);
                if(rowHasDiff) stats.setDiffCount(stats.getDiffCount() + 1);

                srcRow = srcIter.hasNext() ? srcIter.next() : null;
                tgtRow = tgtIter.hasNext() ? tgtIter.next() : null;
            } else if(compare < 0) {
                // Fix #2 & #5: 行级缺失，只生成一条记录，字段名显示为 "-", 值为 "N/A"
                stats.setTotalSource(stats.getTotalSource() + 1);
                stats.setMissingTarget(stats.getMissingTarget() + 1);
                stats.setDiffCount(stats.getDiffCount() + 1);

                // DiffType: MISSING_IN_TARGET, Field: -, SrcVal: Key Exist, TgtVal: null
                writeDiff(writer, srcKey, "MISSING", "MISSING_IN_TARGET", "-", "Record Exist", "N/A", stats);

                srcRow = srcIter.hasNext() ? srcIter.next() : null;
            } else {
                stats.setTotalTarget(stats.getTotalTarget() + 1);
                stats.setMissingSource(stats.getMissingSource() + 1);
                stats.setDiffCount(stats.getDiffCount() + 1);

                writeDiff(writer, "MISSING", tgtKey, "MISSING_IN_SOURCE", "-", "N/A", "Record Exist", stats);

                tgtRow = tgtIter.hasNext() ? tgtIter.next() : null;
            }
        }
        writer.close();
    }

    private boolean compareFields(CsvRow src, CsvRow tgt, CsvRow srcHeader, CsvRow tgtHeader,
                                  CsvWriter writer, SfDataObjConfig config, ReconcileStats stats,
                                  String srcKey, String tgtKey) {
        boolean hasDiff = false;

        for(int i = 0; i < srcHeader.size(); i++) {
            String fieldName = srcHeader.get(i);
            if(fieldName.equalsIgnoreCase(config.getSourceKeyField())) continue;

            // Fix #3: 智能列匹配 (处理 Source: OwnerId vs Target: Owner.Source_Org_Id__c)
            int tgtIdx = findSmartColIndex(tgtHeader, fieldName, config.getTargetKeyField());

            if(tgtIdx != -1) {
                String srcVal = src.get(i);
                String tgtVal = tgt.get(tgtIdx);

                // Fix #4: 空值 vs 有值 必须被识别出来
                if(!isVisuallyEqual(srcVal, tgtVal)) {
                    // Fix #6: 确保 fieldName 正确传入
                    writeDiff(writer, srcKey, tgtKey, "VALUE_DIFF", fieldName, srcVal, tgtVal, stats);
                    hasDiff = true;
                }
            }
        }
        return hasDiff;
    }

    /**
     * Fix #3: 智能查找列索引
     * 如果直接找不到 OwnerId，尝试查找 Owner.Source_Org_Id__c
     */
    private int findSmartColIndex(CsvRow header, String colName, String targetKeyField) {
        // 1. 尝试精确匹配
        int idx = findColIndex(header, colName);
        if(idx != -1) return idx;

        // 2. 尝试关联字段推断匹配
        // 规则: 如果源字段是 OwnerId，目标CSV里可能有 Owner.Source_Org_Id__c
        if(colName.endsWith("Id") || colName.endsWith("__c")) {
            String relName = null;
            if(colName.endsWith("__c")) {
                // Custom__c -> Custom__r
                relName = colName.substring(0, colName.length() - 3) + "__r";
            } else if(colName.endsWith("Id")) {
                // AccountId -> Account
                relName = colName.substring(0, colName.length() - 2);
            }

            if(relName != null) {
                // 构造目标列名: Relationship.ExternalId
                String targetColName = relName + "." + targetKeyField;
                idx = findColIndex(header, targetColName);
            }
        }
        return idx;
    }

    private int findColIndex(CsvRow header, String colName) {
        if(header == null || colName == null) return -1;
        String search = colName.trim();
        for(int i = 0; i < header.size(); i++) {
            // 忽略大小写，忽略空格，忽略不可见字符
            if(search.equalsIgnoreCase(StrUtil.trim(header.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 【值比对】逻辑增强
     */
    private boolean isVisuallyEqual(String v1, String v2) {
        v1 = StrUtil.trimToEmpty(v1);
        v2 = StrUtil.trimToEmpty(v2);

        // 如果一个有值一个没值，直接不等 (空串已统一)
        if(v1.equals(v2)) return true;

        // 换行符归一化
        String v1Norm = CRLF.matcher(v1).replaceAll("\n");
        String v2Norm = CRLF.matcher(v2).replaceAll("\n");
        if(v1Norm.equals(v2Norm)) return true;

        // 数值比对
        if(NumberUtil.isNumber(v1) && NumberUtil.isNumber(v2)) {
            try {
                BigDecimal b1 = NumberUtil.toBigDecimal(v1);
                BigDecimal b2 = NumberUtil.toBigDecimal(v2);
                if(b1.compareTo(b2) == 0) return true;
            } catch(Exception e) {
            }
        }

        // ID 15/18位兼容
        if(v1.length() >= 15 && v2.length() >= 15) {
            if(v1.substring(0, 15).equals(v2.substring(0, 15))) return true;
        }

        return false;
    }

    private void writeDiff(CsvWriter writer, String sKey, String tKey, String type, String field, String sVal, String tVal, ReconcileStats stats) {
        // Fix #5: 只有 VALUE_DIFF 才显示具体值，行缺失显示 N/A 防止混淆
        // 且处理 null 值防止 CSV 错位
        String safeS = sVal == null ? "" : StrUtil.sub(sVal, 0, 2000); // 适度截断防止溢出
        String safeT = tVal == null ? "" : StrUtil.sub(tVal, 0, 2000);
        String safeField = field == null ? "" : field;

        writer.write(new String[]{sKey, tKey, type, safeField, safeS, safeT});

        if(stats.getPreviewList().size() < 50) {
            Map<String, String> item = new HashMap<>();
            item.put("key", "MISSING".equals(sKey) ? tKey : sKey);
            item.put("type", type);
            item.put("field", safeField);
            item.put("srcVal", safeS);
            item.put("tgtVal", safeT);
            stats.getPreviewList().add(item);
        }
    }
}