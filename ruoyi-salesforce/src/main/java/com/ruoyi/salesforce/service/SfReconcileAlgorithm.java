package com.ruoyi.salesforce.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.text.csv.*;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.salesforce.domain.SfDataObjConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Salesforce 数据比对核心算法引擎 (流式优化最终修复版)
 * 适配 Hutool stream() 无参调用
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
    }

    public ReconcileStats execute(File sourceFile, File targetFile, File resultFile, SfDataObjConfig config, int totalRows, Consumer<Integer> progressCallback, java.util.function.BooleanSupplier checkRunning) {
        log.info("开始执行流式比对，对象: {}", config.getObjectName());
        ReconcileStats stats = new ReconcileStats();

        // 基础进度从 40% 开始 (前 40% 留给下载)
        final int BASE_PROGRESS = 40;
        final int MAX_ALGO_PROGRESS = 60; // 算法占 60% 的权重 (40-100)

        long processedCount = 0; // 已处理行数计数器
        int lastReportedProgress = BASE_PROGRESS;

        String srcKeyField = StringUtils.defaultIfEmpty(config.getSourceKeyField(), "Id");
        String tgtKeyField = StringUtils.defaultIfEmpty(config.getTargetKeyField(), "Id");

        // 1. 准备配置
        CsvReadConfig csvConfig = CsvReadConfig.defaultConfig();
        csvConfig.setFieldSeparator(',');
        csvConfig.setTextDelimiter('\"');

        // 使用 try-with-resources 确保所有流（写入流 + 读取流）被关闭
        try(
                // 结果写入流
                FileOutputStream fos = new FileOutputStream(resultFile);
                BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8));

                // 【核心修复】先创建 BufferedReader，确保文件句柄可被关闭
                BufferedReader srcBr = FileUtil.getReader(sourceFile, StandardCharsets.UTF_8);
                BufferedReader tgtBr = FileUtil.getReader(targetFile, StandardCharsets.UTF_8);
        ) {
            // 初始化写入器
            fos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // BOM
            CsvWriter writer = CsvUtil.getWriter(bw);
            writer.write(new String[]{"Source_Key", "Target_Key", "Diff_Type", "Field_Name", "Source_Value", "Target_Value"});

            // 【核心修复】绑定 Reader 到 CsvReader
            CsvReader srcReader = CsvUtil.getReader(srcBr, csvConfig);
            CsvReader tgtReader = CsvUtil.getReader(tgtBr, csvConfig);

            // 【核心修复】调用无参 stream()
            try(Stream<CsvRow> srcStream = srcReader.stream();
                Stream<CsvRow> tgtStream = tgtReader.stream()) {

                Iterator<CsvRow> srcIter = srcStream.iterator();
                Iterator<CsvRow> tgtIter = tgtStream.iterator();

                if(!srcIter.hasNext() || !tgtIter.hasNext()) {
                    log.warn("源文件或目标文件为空");
                    return stats;
                }

                CsvRow srcHeader = srcIter.next();
                CsvRow tgtHeader = tgtIter.next();

                int srcKeyIdx = findColIndex(srcHeader, srcKeyField);
                int tgtKeyIdx = findColIndex(tgtHeader, tgtKeyField);

                if(srcKeyIdx == -1) throw new RuntimeException("源文件未找到主键列: " + srcKeyField);
                if(tgtKeyIdx == -1) throw new RuntimeException("目标文件未找到主键列: " + tgtKeyField);

                CsvRow srcRow = srcIter.hasNext() ? srcIter.next() : null;
                CsvRow tgtRow = tgtIter.hasNext() ? tgtIter.next() : null;

                // 双指针比对循环
                while(srcRow != null || tgtRow != null) {
                    if(checkRunning != null && !checkRunning.getAsBoolean()) {
                        log.warn("算法在游标位置被强行中断...");
                        throw new RuntimeException("ABORTED_BY_USER");
                    }
                    // 1. 进度计算与防抖
                    processedCount++;
                    // 防止除以0
                    int safeTotal = totalRows > 0 ? totalRows : 1;

                    // 计算当前算法阶段的进度 (0-60)
                    int currentAlgoStep = (int) Math.min(MAX_ALGO_PROGRESS, (processedCount * MAX_ALGO_PROGRESS) / safeTotal);
                    int totalProgress = BASE_PROGRESS + currentAlgoStep;

                    // 只有进度前进至少 1% 或者是最后一条时，才回调
                    // 也可以加上时间判断，比如 System.currentTimeMillis()，每秒最多一次
                    if(totalProgress > lastReportedProgress && totalProgress < 100) {
                        progressCallback.accept(totalProgress);
                        lastReportedProgress = totalProgress;
                    }

                    String sKey = getNormalizedKey(srcRow, srcKeyIdx);
                    String tKey = getNormalizedKey(tgtRow, tgtKeyIdx);

                    int compare;
                    if(sKey == null && tKey == null) compare = 0;
                    else if(sKey == null) compare = 1;
                    else if(tKey == null) compare = -1;
                    else compare = sKey.compareTo(tKey);

                    if(compare == 0) {
                        stats.setTotalSource(stats.getTotalSource() + 1);
                        stats.setTotalTarget(stats.getTotalTarget() + 1);
                        boolean hasDiff = compareFields(srcRow, tgtRow, srcHeader, tgtHeader, writer, config, stats, sKey, tKey);
                        if(hasDiff) stats.setDiffCount(stats.getDiffCount() + 1);
                        srcRow = srcIter.hasNext() ? srcIter.next() : null;
                        tgtRow = tgtIter.hasNext() ? tgtIter.next() : null;
                    } else if(compare < 0) {
                        stats.setTotalSource(stats.getTotalSource() + 1);
                        stats.setMissingTarget(stats.getMissingTarget() + 1);
                        stats.setDiffCount(stats.getDiffCount() + 1);
                        writeDiff(writer, sKey, "", "MISSING_IN_TARGET", "-", "Row Exists", "Row Missing", stats);
                        srcRow = srcIter.hasNext() ? srcIter.next() : null;
                    } else {
                        stats.setTotalTarget(stats.getTotalTarget() + 1);
                        stats.setMissingSource(stats.getMissingSource() + 1);
                        stats.setDiffCount(stats.getDiffCount() + 1);
                        writeDiff(writer, "", tKey, "MISSING_IN_SOURCE", "-", "Row Missing", "Row Exists", stats);
                        tgtRow = tgtIter.hasNext() ? tgtIter.next() : null;
                    }
                }
                writer.flush();
                // 确保最后是 100% (或者由 Service 层设置 FINISHED 状态来覆盖)
                progressCallback.accept(99);
            }
        } catch(Exception e) {
            if ("ABORTED_BY_USER".equals(e.getMessage())) throw new RuntimeException(e);
            log.error("比对异常", e);
            throw new RuntimeException("比对失败: " + e.getMessage(), e);
        }
        return stats;
    }

    private int findColIndex(CsvRow header, String colName) {
        if(header == null || colName == null) return -1;
        String search = cleanHeader(colName);
        for(int i = 0; i < header.size(); i++) {
            if(header.get(i) != null && search.equalsIgnoreCase(cleanHeader(header.get(i)))) return i;
        }
        return -1;
    }

    private String cleanHeader(String header) {
        if(header == null) return "";
        return header.replace("\uFEFF", "").replace("\"", "").trim();
    }

    private String getNormalizedKey(CsvRow row, int index) {
        if(row == null || index == -1 || index >= row.size()) return null;
        String val = row.get(index);
        if(val == null) return null;
        val = StrUtil.trim(val);
        if(val.length() == 18 && val.startsWith("00")) return val.substring(0, 15);
        return val;
    }

    private boolean compareFields(CsvRow src, CsvRow tgt, CsvRow srcHeader, CsvRow tgtHeader,
                                  CsvWriter writer, SfDataObjConfig config, ReconcileStats stats,
                                  String srcKey, String tgtKey) {
        boolean hasDiff = false;
        String srcKeyName = StringUtils.defaultIfEmpty(config.getSourceKeyField(), "Id");

        for(int i = 0; i < srcHeader.size(); i++) {
            String fieldName = cleanHeader(srcHeader.get(i));
            if(fieldName.equalsIgnoreCase(srcKeyName)) continue;

            int tgtIdx = findSmartColIndex(tgtHeader, fieldName, config.getTargetKeyField());

            if(tgtIdx != -1) {
                String sVal = src.get(i);
                String tVal = tgt.size() > tgtIdx ? tgt.get(tgtIdx) : "";

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
        if(colName.endsWith("Id") || colName.endsWith("__c")) {
            String relName = null;
            if(colName.endsWith("__c")) relName = colName.substring(0, colName.length() - 3) + "__r";
            else if(colName.endsWith("Id")) relName = colName.substring(0, colName.length() - 2);

            if(relName != null) {
                String targetColName = relName + ".Source_Org_Id__c";
                idx = findColIndex(header, targetColName);
            }
        }
        return idx;
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
        String safeS = sVal == null ? "" : StrUtil.sub(sVal, 0, 3000);
        String safeT = tVal == null ? "" : StrUtil.sub(tVal, 0, 3000);
        String safeField = field == null ? "" : field;
        writer.write(new String[]{sKey, tKey, type, safeField, safeS, safeT});
    }
}
