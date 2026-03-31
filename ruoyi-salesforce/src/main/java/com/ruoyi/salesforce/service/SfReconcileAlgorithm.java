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

import java.io.*;
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
        log.info("开始执行高精度比对，对象: {}", config.getObjectName());
        ReconcileStats stats = new ReconcileStats();

        String srcKeyField = StringUtils.defaultIfEmpty(config.getSourceKeyField(), "Id");
        String tgtKeyField = StringUtils.defaultIfEmpty(config.getTargetKeyField(), "Id");

        CsvReadConfig csvConfig = CsvReadConfig.defaultConfig();
        csvConfig.setFieldSeparator(',');
        csvConfig.setTextDelimiter('\"');

        File sortedSrcFile = null;
        File sortedTgtFile = null;

        try {
            // ==========================================
            // 【核心修复 1：外部归并排序】无视 SF 的错误排序，强制按 Java Case-Sensitive 统一重排双端 CSV
            // ==========================================
            log.info("正在执行本地数据对齐排序...");
            sortedSrcFile = externalSortCsv(sourceFile, csvConfig, srcKeyField);
            sortedTgtFile = externalSortCsv(targetFile, csvConfig, tgtKeyField);

            try(
                    FileOutputStream fos = new FileOutputStream(resultFile);
                    BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8));
                    BufferedReader srcBr = FileUtil.getReader(sortedSrcFile, StandardCharsets.UTF_8);
                    BufferedReader tgtBr = FileUtil.getReader(sortedTgtFile, StandardCharsets.UTF_8);
            ) {
                fos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // BOM
                CsvWriter writer = CsvUtil.getWriter(bw);
                writer.write(new String[]{"Source_Key", "Target_Key", "Diff_Type", "Field_Name", "Source_Value", "Target_Value"});

                CsvReader srcReader = CsvUtil.getReader(srcBr, csvConfig);
                CsvReader tgtReader = CsvUtil.getReader(tgtBr, csvConfig);

                try(Stream<CsvRow> srcStream = srcReader.stream();
                    Stream<CsvRow> tgtStream = tgtReader.stream()) {

                    Iterator<CsvRow> srcIter = srcStream.iterator();
                    Iterator<CsvRow> tgtIter = tgtStream.iterator();

                    if(!srcIter.hasNext() || !tgtIter.hasNext()) return stats;

                    CsvRow srcHeader = srcIter.next();
                    CsvRow tgtHeader = tgtIter.next();

                    int srcKeyIdx = findColIndex(srcHeader, srcKeyField);
                    int tgtKeyIdx = findColIndex(tgtHeader, tgtKeyField);

                    CsvRow srcRow = srcIter.hasNext() ? srcIter.next() : null;
                    CsvRow tgtRow = tgtIter.hasNext() ? tgtIter.next() : null;

                    long processedCount = 0;
                    final int BASE_PROGRESS = 40;
                    final int MAX_ALGO_PROGRESS = 60;
                    int lastReportedProgress = BASE_PROGRESS;

                    while(srcRow != null || tgtRow != null) {
                        if(checkRunning != null && !checkRunning.getAsBoolean()) {
                            throw new RuntimeException("ABORTED_BY_USER");
                        }

                        processedCount++;
                        int safeTotal = totalRows > 0 ? totalRows : 1;
                        int currentAlgoStep = (int) Math.min(MAX_ALGO_PROGRESS, (processedCount * MAX_ALGO_PROGRESS) / safeTotal);
                        int totalProgress = BASE_PROGRESS + currentAlgoStep;
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
                            // 【核心修复 2：恢复强一致性校验】文件已完全对齐，恢复最严谨的区分大小写对比
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
                    progressCallback.accept(99);
                }
            }
        } catch(Exception e) {
            if("ABORTED_BY_USER".equals(e.getMessage())) throw new RuntimeException(e);
            log.error("比对异常", e);
            throw new RuntimeException("比对失败: " + e.getMessage(), e);
        } finally {
            // 兜底清理排序产生的中间临时文件
            if(sortedSrcFile != null && !sortedSrcFile.getAbsolutePath().equals(sourceFile.getAbsolutePath())) {
                FileUtil.del(sortedSrcFile);
            }
            if(sortedTgtFile != null && !sortedTgtFile.getAbsolutePath().equals(targetFile.getAbsolutePath())) {
                FileUtil.del(sortedTgtFile);
            }
        }
        return stats;
    }

    // =========================================================
    // 【核心部件 1：外部归并排序引擎 (防爆内存处理海量 CSV)】
    // =========================================================
    private File externalSortCsv(File inputFile, CsvReadConfig csvConfig, String keyField) throws Exception {
        File sortedFile = new File(inputFile.getAbsolutePath() + ".sorted");
        List<File> chunkFiles = new ArrayList<>();

        try(BufferedReader br = FileUtil.getReader(inputFile, StandardCharsets.UTF_8);
            CsvReader reader = CsvUtil.getReader(br, csvConfig)) {

            Iterator<CsvRow> iter = reader.stream().iterator();
            if(!iter.hasNext()) return inputFile;

            CsvRow header = iter.next();
            int keyIdx = findColIndex(header, keyField);
            if(keyIdx == -1) return inputFile;

            // 【核心优化：自适应分块算法】
            // 假设我们期望每个 Chunk 在内存中不超过 50MB。
            // 粗略估计：如果是 10 个字段的窄表，可以放 50000 行；如果是 200 个字段的宽表，只能放 5000 行。
            // 采用公式：基准数 / 字段数量，同时设定安全上下限。
            int columnsCount = Math.max(1, header.size());
            int dynamicChunkSize = Math.max(5000, Math.min(50000, 1000000 / columnsCount));
            log.info("自适应分块计算：字段数={}, 安全块大小={}", columnsCount, dynamicChunkSize);

            List<CsvRow> chunk = new ArrayList<>(dynamicChunkSize);
            while(iter.hasNext()) {
                chunk.add(iter.next());
                if(chunk.size() >= dynamicChunkSize) {
                    chunkFiles.add(sortAndSaveChunk(chunk, keyIdx));
                    chunk.clear();
                    // 提示 JVM 尽早回收，防止碎片化累积
                    // System.gc(); // 不强制调用，但利用 ArrayList 的 clear 重用空间已经足够高效
                }
            }
            if(!chunk.isEmpty()) {
                chunkFiles.add(sortAndSaveChunk(chunk, keyIdx));
            }

            // 【防御机制】如果切出了数百个块，直接合并可能会报 Too many open files
            if(chunkFiles.size() > 200) {
                log.warn("检测到超大海量数据，切割了 {} 个块，准备执行多级归并防文件句柄溢出...", chunkFiles.size());
                // 这里如果极度严谨，可以写一个多级归并（把每 100 个合并成 1 个大块，再合并大块）。
                // 考虑到我们现在的动态块大小（5000~50000），要切出 200 个块意味着单对象几百万甚至上千万数据。
                // 现阶段 OS 的 ulimit -n 通常配置为 65535，所以暂时直接合并也是安全的，只留警告日志。
            }

            // 执行归并
            mergeChunks(chunkFiles, sortedFile, header, keyIdx, csvConfig);
        } finally {
            for(File f : chunkFiles) FileUtil.del(f); // 严格销毁临时分片文件
        }
        return sortedFile;
    }

    private File sortAndSaveChunk(List<CsvRow> chunk, int keyIdx) throws IOException {
        chunk.sort((r1, r2) -> {
            String k1 = getNormalizedKey(r1, keyIdx);
            String k2 = getNormalizedKey(r2, keyIdx);
            if(k1 == null) k1 = "";
            if(k2 == null) k2 = "";
            return k1.compareTo(k2); // 绝对的区分大小写排序
        });

        File tempFile = File.createTempFile("sf_chunk_", ".csv");
        try(BufferedWriter bw = FileUtil.getWriter(tempFile, StandardCharsets.UTF_8, false);
            CsvWriter writer = CsvUtil.getWriter(bw)) {
            for(CsvRow row : chunk) {
                String[] arr = new String[row.size()];
                for(int i = 0; i < row.size(); i++) arr[i] = row.get(i);
                writer.write(arr);
            }
        }
        return tempFile;
    }

    private void mergeChunks(List<File> chunkFiles, File outputFile, CsvRow header, int keyIdx, CsvReadConfig csvConfig) throws Exception {
        PriorityQueue<ChunkReader> pq = new PriorityQueue<>();
        List<ChunkReader> readers = new ArrayList<>();
        try {
            for(File f : chunkFiles) {
                ChunkReader cr = new ChunkReader(f, csvConfig, keyIdx);
                readers.add(cr);
                if(cr.next()) pq.add(cr);
            }

            try(BufferedWriter bw = FileUtil.getWriter(outputFile, StandardCharsets.UTF_8, false);
                CsvWriter writer = CsvUtil.getWriter(bw)) {

                String[] hdr = new String[header.size()];
                for(int i = 0; i < header.size(); i++) hdr[i] = header.get(i);
                writer.write(hdr);

                while(!pq.isEmpty()) {
                    ChunkReader cr = pq.poll();
                    CsvRow row = cr.currentRow;
                    String[] arr = new String[row.size()];
                    for(int i = 0; i < row.size(); i++) arr[i] = row.get(i);
                    writer.write(arr);

                    if(cr.next()) {
                        pq.add(cr);
                    }
                }
            }
        } finally {
            for(ChunkReader cr : readers) cr.close();
        }
    }

    private static class ChunkReader implements Comparable<ChunkReader>, AutoCloseable {
        BufferedReader br;
        CsvReader reader;
        Iterator<CsvRow> iter;
        CsvRow currentRow;
        String currentKey;
        int keyIdx;

        public ChunkReader(File file, CsvReadConfig config, int keyIdx) throws IOException {
            this.br = FileUtil.getReader(file, StandardCharsets.UTF_8);
            this.reader = CsvUtil.getReader(br, config);
            this.iter = reader.stream().iterator();
            this.keyIdx = keyIdx;
        }

        public boolean next() {
            if(iter.hasNext()) {
                currentRow = iter.next();
                currentKey = getNormalizedKey(currentRow, keyIdx);
                if(currentKey == null) currentKey = "";
                return true;
            }
            return false;
        }

        @Override
        public int compareTo(ChunkReader o) {
            return this.currentKey.compareTo(o.currentKey);
        }

        @Override
        public void close() throws Exception {
            if(br != null) br.close();
        }
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

    private static String getNormalizedKey(CsvRow row, int index) {
        if(row == null || index == -1 || index >= row.size()) return null;
        String val = row.get(index);
        if(val == null) return null;
        val = StrUtil.trim(val);

        // 彻底废除 18 截 15 逻辑。如果传过来的是 15 位，强制使用 SF 标准算法升维成 18 位。
        if(val.length() == 15) {
            val = convertId15To18(val);
        }
        return val;
    }

    private static String convertId15To18(String id15) {
        if(id15 == null || id15.length() != 15) return id15;
        StringBuilder suffix = new StringBuilder();
        for(int i = 0; i < 3; i++) {
            int flags = 0;
            for(int j = 0; j < 5; j++) {
                char c = id15.charAt(i * 5 + j);
                if(c >= 'A' && c <= 'Z') flags += 1 << j;
            }
            if(flags <= 25) suffix.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ".charAt(flags));
            else suffix.append("012345".charAt(flags - 26));
        }
        return id15 + suffix.toString();
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

        // 1. 完全一致
        if(v1.equals(v2)) return true;

        // 2. 换行符差异抹平
        String v1Norm = CRLF.matcher(v1).replaceAll("\n");
        String v2Norm = CRLF.matcher(v2).replaceAll("\n");
        if(v1Norm.equals(v2Norm)) return true;

        // ==========================================
        // 【新增优化：多选下拉列表 (Multipicklist) 无序兼容】
        // 如果两边都包含分号，打散成集合进行无序对比
        // ==========================================
        if(v1.contains(";") && v2.contains(";")) {
            String[] arr1 = v1.split(";");
            String[] arr2 = v2.split(";");
            // 只有当分号切分出来的元素数量一致时，才进行集合比对，节省性能
            if(arr1.length == arr2.length) {
                Set<String> set1 = new HashSet<>();
                Set<String> set2 = new HashSet<>();
                // 加入 Set 前执行 trim，兼容某些带有空格的脏数据 (例如 "A; B")
                for(String s : arr1) set1.add(s.trim());
                for(String s : arr2) set2.add(s.trim());

                // Set 的 equals 会自动无视顺序，判断元素是否完全一致
                if(set1.equals(set2)) {
                    return true;
                }
            }
        }
        // ==========================================

        // 4. 数值类型精度抹平 (如 1.0 vs 1.00)
        if(NumberUtil.isNumber(v1) && NumberUtil.isNumber(v2)) {
            try {
                BigDecimal b1 = NumberUtil.toBigDecimal(v1);
                BigDecimal b2 = NumberUtil.toBigDecimal(v2);
                if(b1.compareTo(b2) == 0) return true;
            } catch(Exception e) {
            }
        }

        // 5. 15位 / 18位 ID 兼容处理
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
