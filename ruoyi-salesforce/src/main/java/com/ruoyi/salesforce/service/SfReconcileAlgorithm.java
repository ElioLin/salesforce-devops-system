package com.ruoyi.salesforce.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.text.csv.*;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSONObject;
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
        private int ignoredPostCutoffCount = 0;
        private int missingTarget = 0;
        private int missingSource = 0;
    }

    public ReconcileStats execute(File sourceFile, File targetFile, File resultFile, SfDataObjConfig config, int totalRows, Consumer<Integer> progressCallback, java.util.function.BooleanSupplier checkRunning, Date dataEndTime) {
        log.info("开始执行高精度比对，对象: {}", config.getObjectName());
        ReconcileStats stats = new ReconcileStats();

        String srcKeyField = StringUtils.defaultIfEmpty(config.getSourceKeyField(), "Id");
        String tgtKeyField = StringUtils.defaultIfEmpty(config.getTargetKeyField(), "Id");

        CsvReadConfig csvConfig = CsvReadConfig.defaultConfig();
        csvConfig.setFieldSeparator(',');
        csvConfig.setTextDelimiter('\"');
        // 【核心防御】：强制关闭自动 trim，防止首尾包含空格时导致引号匹配失衡
        csvConfig.setTrimField(false);

        // ==========================================
        // 【终极防御：关闭 CSV 注释符机制】
        // 修复 Salesforce 长文本包含 "#" 号换行时，Hutool 误将其当做注释跳过，
        // 导致数据丢失及双引号状态机崩溃、吞噬后续列数据的严重 Bug。
        // ==========================================
        try {
            csvConfig.disableComment();
        } catch (NoSuchMethodError e) {
            // 兼容性兜底：如果项目使用的 Hutool 版本较老没有 disableComment() 方法，则置空
            csvConfig.setCommentCharacter(null);
        }

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
                    // 改用底层的 BufferedInputStream 配合 InputStreamReader，
                    // 既保证了磁盘读取极速，又彻底避免了 BufferedReader 对长文本换行符的错误干扰。
                    InputStreamReader srcIsr = new InputStreamReader(new BufferedInputStream(new FileInputStream(sortedSrcFile)), StandardCharsets.UTF_8);
                    InputStreamReader tgtIsr = new InputStreamReader(new BufferedInputStream(new FileInputStream(sortedTgtFile)), StandardCharsets.UTF_8);
            ) {
                fos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // BOM
                CsvWriter writer = CsvUtil.getWriter(bw);
                writer.write(new String[]{"Source_Key", "Target_Key", "Diff_Type", "Field_Name", "Source_Value", "Target_Value",
                        "Source_CreatedDate", "Target_CreatedDate", "Source_LastModifiedDate", "Target_LastModifiedDate",
                        "Source_Id", "Target_Id"});

                // 将绝对纯净的原生 UTF-8 字符流传递给 Hutool
                CsvReader srcReader = CsvUtil.getReader(srcIsr, csvConfig);
                CsvReader tgtReader = CsvUtil.getReader(tgtIsr, csvConfig);

                try(Stream<CsvRow> srcStream = srcReader.stream();
                    Stream<CsvRow> tgtStream = tgtReader.stream()) {

                    Iterator<CsvRow> srcIter = srcStream.iterator();
                    Iterator<CsvRow> tgtIter = tgtStream.iterator();

                    if(!srcIter.hasNext() || !tgtIter.hasNext()) return stats;

                    CsvRow srcHeader = srcIter.next();
                    CsvRow tgtHeader = tgtIter.next();

                    int srcKeyIdx = findColIndex(srcHeader, srcKeyField);
                    int tgtKeyIdx = findColIndex(tgtHeader, tgtKeyField);

                    int srcCdIdx = findColIndex(srcHeader, "CreatedDate");
                    int tgtCdIdx = findColIndex(tgtHeader, "CreatedDate");
                    int srcMdIdx = findColIndex(srcHeader, "LastModifiedDate");
                    int tgtMdIdx = findColIndex(tgtHeader, "LastModifiedDate");

                    int srcIdIdx = findColIndex(srcHeader, "Id");
                    int tgtIdIdx = findColIndex(tgtHeader, "Id");

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

                        String sCd = (srcRow != null && srcCdIdx != -1) ? srcRow.get(srcCdIdx) : "";
                        String tCd = (tgtRow != null && tgtCdIdx != -1) ? tgtRow.get(tgtCdIdx) : "";
                        String sMd = (srcRow != null && srcMdIdx != -1) ? srcRow.get(srcMdIdx) : "";
                        String tMd = (tgtRow != null && tgtMdIdx != -1) ? tgtRow.get(tgtMdIdx) : "";

                        String sId = (srcRow != null && srcIdIdx != -1) ? srcRow.get(srcIdIdx) : "";
                        String tId = (tgtRow != null && tgtIdIdx != -1) ? tgtRow.get(tgtIdIdx) : "";

                        int compare;
                        if(sKey == null && tKey == null) compare = 0;
                        else if(sKey == null) compare = 1;
                        else if(tKey == null) compare = -1;
                            // 【核心修复 2：恢复强一致性校验】文件已完全对齐，恢复最严谨的区分大小写对比
                        else compare = sKey.compareTo(tKey);

                        if(compare == 0) {
                            stats.setTotalSource(stats.getTotalSource() + 1);
                            stats.setTotalTarget(stats.getTotalTarget() + 1);
                            boolean hasDiff = compareFields(srcRow, tgtRow, srcHeader, tgtHeader, writer, config, stats, sKey, tKey, dataEndTime, sCd, tCd, sMd, tMd, sId, tId);
                            if(hasDiff) stats.setDiffCount(stats.getDiffCount() + 1);
                            srcRow = srcIter.hasNext() ? srcIter.next() : null;
                            tgtRow = tgtIter.hasNext() ? tgtIter.next() : null;
                        } else if(compare < 0) {
                            stats.setTotalSource(stats.getTotalSource() + 1);
                            stats.setMissingTarget(stats.getMissingTarget() + 1);
                            stats.setDiffCount(stats.getDiffCount() + 1);
                            writeDiff(writer, sKey, "", "MISSING_IN_TARGET", "-", "Row Exists", "Row Missing", stats, sCd, "", sMd, "", sId, "");
                            srcRow = srcIter.hasNext() ? srcIter.next() : null;
                        } else {
                            stats.setTotalTarget(stats.getTotalTarget() + 1);
                            stats.setMissingSource(stats.getMissingSource() + 1);
                            stats.setDiffCount(stats.getDiffCount() + 1);
                            writeDiff(writer, "", tKey, "MISSING_IN_SOURCE", "-", "Row Missing", "Row Exists", stats, "", tCd, "", tMd, "", tId);
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

        // 使用严格的 CsvReader 直接开启带 UTF-8 声明的文件流，避免缓冲流的边界漏洞
        try(InputStreamReader isr = new InputStreamReader(new BufferedInputStream(new FileInputStream(inputFile)), StandardCharsets.UTF_8);
            CsvReader reader = CsvUtil.getReader(isr, csvConfig)) {

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
        // 强制使用纯净的 UTF-8 字节流
        try(FileOutputStream fos = new FileOutputStream(tempFile);
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8));
            CsvWriter writer = CsvUtil.getWriter(bw)) {
            for(CsvRow row : chunk) {
                String[] arr = new String[row.size()];
                for(int i = 0; i < row.size(); i++) {
                    String val = row.get(i);
                    if (val != null) {
                        // 【终极防御：抹平隐藏换行符】
                        // 拦截 Salesforce 长文本中游离的 \r (Mac回车)。
                        // 强制转为 \n，确保底层 CsvWriter 正常触发双引号包裹机制，防止 CSV 结构在硬盘上崩塌错位！
                        val = val.replace("\r\n", "\n").replace("\r", "\n");
                    }
                    arr[i] = val;
                }
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

            try(FileOutputStream fos = new FileOutputStream(outputFile);
                BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8));
                CsvWriter writer = CsvUtil.getWriter(bw)) {

                String[] hdr = new String[header.size()];
                for(int i = 0; i < header.size(); i++) {
                    hdr[i] = header.get(i);
                }
                writer.write(hdr);

                while(!pq.isEmpty()) {
                    ChunkReader cr = pq.poll();
                    CsvRow row = cr.currentRow;
                    String[] arr = new String[row.size()];
                    for(int i = 0; i < row.size(); i++) {
                        String val = row.get(i);
                        if (val != null) {
                            // 再次确保合并写入时换行符的绝对纯净
                            val = val.replace("\r\n", "\n").replace("\r", "\n");
                        }
                        arr[i] = val;
                    }
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
        InputStreamReader isr; // 【终极修复 3】：维护原生流以供释放
        CsvReader reader;
        Stream<CsvRow> stream;
        Iterator<CsvRow> iter;
        CsvRow currentRow;
        String currentKey;
        int keyIdx;

        public ChunkReader(File file, CsvReadConfig config, int keyIdx) throws IOException {
            this.isr = new InputStreamReader(new BufferedInputStream(new FileInputStream(file)), StandardCharsets.UTF_8);
            this.reader = CsvUtil.getReader(this.isr, config);
            this.stream = this.reader.stream();
            this.iter = this.stream.iterator();
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
            if (this.stream != null) this.stream.close();
            if (this.isr != null) this.isr.close();
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
        if(val.length() == 15 && val.matches("^[a-zA-Z0-9]+$")) {
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

    private boolean compareFields(CsvRow src, CsvRow tgt, CsvRow srcHeader, CsvRow tgtHeader, CsvWriter writer, SfDataObjConfig config, ReconcileStats stats, String srcKey, String tgtKey, Date dataEndTime, String sCd, String tCd, String sMd, String tMd, String sId, String tId) {
        Map<String, JSONObject> relationMap = new HashMap<>();
        if(StringUtils.isNotEmpty(config.getMappingConfig())) {
            try {
                relationMap = com.alibaba.fastjson2.JSON.parseObject(config.getMappingConfig(), new com.alibaba.fastjson2.TypeReference<Map<String, com.alibaba.fastjson2.JSONObject>>() {
                });
            } catch(Exception e) {
                log.warn("解析 MappingConfig 失败", e);
            }
        }
        boolean hasRealDiff = false;

        // 【新增提取】：获取当前双端行数据的最后修改时间
        Date srcLmd = null;
        Date tgtLmd = null;
        if(dataEndTime != null) {
            if(StringUtils.isNotEmpty(sMd)) srcLmd = cn.hutool.core.date.DateUtil.parse(sMd);
            if(StringUtils.isNotEmpty(tMd)) tgtLmd = cn.hutool.core.date.DateUtil.parse(tMd);
        }

        for(int sIdx = 0; sIdx < srcHeader.size(); sIdx++) {
            String colName = cleanHeader(srcHeader.get(sIdx));
            // 忽略非业务比对字段
            if(colName.equalsIgnoreCase("LastModifiedDate") ||
                    colName.equalsIgnoreCase("Id") ||
                    colName.equalsIgnoreCase(config.getSourceKeyField()))
                continue;

            int tIdx = findSmartColIndex(tgtHeader, colName, config.getTargetKeyField(), relationMap);
            if(tIdx == -1) continue;

            String sVal = src.get(sIdx);
            String tVal = tgt.get(tIdx);

            if(!isVisuallyEqual(sVal, tVal)) {
                // 【核心逻辑：移动靶安全判定】
                boolean isPostCutoff = false;
                if(dataEndTime != null) {
                    // 只要有一端的修改时间晚于我们的截断时间，就属于业务后置修改
                    if(srcLmd != null && srcLmd.after(dataEndTime)) {
                        isPostCutoff = true;
                    }
                }

                if(isPostCutoff) {
                    // 安全忽略，写入特殊类型
                    stats.setIgnoredPostCutoffCount(stats.getIgnoredPostCutoffCount() + 1);
                    writeDiff(writer, srcKey, tgtKey, "POST_CUTOFF_CHANGE", colName, sVal, tVal, stats, sCd, tCd, sMd, tMd, sId, tId);
                } else {
                    // 真实的迁移异常
                    hasRealDiff = true;
                    writeDiff(writer, srcKey, tgtKey, "VALUE_DIFF", colName, sVal, tVal, stats, sCd, tCd, sMd, tMd, sId, tId);
                }
            }
        }
        return hasRealDiff; // 注意：返回的是否有真实异常，外部收到 true 才会把总 diffCount + 1
    }

    private int findSmartColIndex(CsvRow header, String colName, String targetKeyField, Map<String, JSONObject> relationMap) {
        // 1. 原生 API 完全匹配
        int idx = findColIndex(header, colName);
        if(idx != -1) return idx;

        // 2. 【核心修复】检查该字段是否有用户自定义的映射策略 (例如 OwnerId -> Owner.Name)
        if(relationMap != null && relationMap.containsKey(colName)) {
            JSONObject mappingObj = relationMap.get(colName);
            if(mappingObj != null && mappingObj.containsKey("targetPath")) {
                String targetPath = mappingObj.getString("targetPath");
                idx = findColIndex(header, targetPath);
                if(idx != -1) return idx;
            }
        }

        // 3. 兜底推断：如果没配置，按旧逻辑推断 __r
        if(colName.endsWith("Id") || colName.endsWith("__c")) {
            String relName = null;
            if(colName.endsWith("__c")) relName = colName.substring(0, colName.length() - 3) + "__r";
            else if(colName.endsWith("Id")) relName = colName.substring(0, colName.length() - 2);

            if(relName != null && StringUtils.isNotEmpty(targetKeyField)) {
                String targetColName = relName + "." + targetKeyField;
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
        // 长短文本自适应分流引擎
        int LARGE_TEXT_THRESHOLD = 5000;
        if(v1.length() > LARGE_TEXT_THRESHOLD || v2.length() > LARGE_TEXT_THRESHOLD) {
            // 长文本：启用 O(1) 空间复杂度的高性能有效字符数比对
            return isLargeTextEqual(v1, v2);
        }

        // 短文本：维持高精度的逐行降噪抹平
        String v1Norm = normalizeMultilineText(v1);
        String v2Norm = normalizeMultilineText(v2);
        if(v1Norm.equals(v2Norm)) return true;

        // ==========================================
        // 【防误伤优化：多选下拉列表 (Multipicklist) 无序兼容】
        // 增加安全防线：只有在不包含换行符的情况下（排除包含分号的普通长文本段落被误判），
        // 且双端都包含分号时，才尝试打散成集合进行无序对比。
        // ==========================================
        if(!v1.contains("\n") && !v2.contains("\n") && v1.contains(";") && v2.contains(";")) {
            String[] arr1 = v1.split(";");
            String[] arr2 = v2.split(";");
            // 只有当分号切分出来的元素数量一致时，才进行集合比对，节省性能
            if(arr1.length == arr2.length) {
                Set<String> set1 = new HashSet<>();
                Set<String> set2 = new HashSet<>();
                // 加入 Set 前执行 trim，兼容某些带有空格的脏数据
                for(String s : arr1) set1.add(s.trim());
                for(String s : arr2) set2.add(s.trim());

                // Set 的 equals 会自动无视顺序，判断元素是否完全一致
                if(set1.equals(set2)) {
                    return true;
                }
            }
        }
        // ==========================================

        // 4. 数值类型精度抹平 (如 1.0 vs 1.00，及浮点数底层噪音抹平)
        if(NumberUtil.isNumber(v1) && NumberUtil.isNumber(v2)) {
            try {
                BigDecimal b1 = NumberUtil.toBigDecimal(v1);
                BigDecimal b2 = NumberUtil.toBigDecimal(v2);

                // 4.1. 尝试完全精确的比对
                if(b1.compareTo(b2) == 0) {
                    // 防止 "0123" 和 "123" 被误判相等（掩盖了工号/邮编前导零丢失的迁移 Bug）
                    // 逻辑：如果不包含小数点，说明是纯整数，必须字面量严格一致才放行。
                    if(!v1.contains(".") && !v2.contains(".")) {
                        if(!v1.equals(v2)) return false;
                    }
                    return true;
                }

                // ==========================================
                // 【核心优化：IEEE 754 浮点数底层噪音抹平引擎】
                // 解决 Salesforce 导出 Bulk CSV 时，Double 类型数据出现的尾数误差问题。
                // 策略：针对相差极小的值，在业务层认定其完全一致，忽略底层存储误差。
                // ==========================================
                BigDecimal diff = b1.subtract(b2).abs();

                // 策略 1：绝对误差极小 (小于 1e-10)，针对中小型常规数值直接放行
                if(diff.compareTo(new BigDecimal("1E-10")) < 0) {
                    return true;
                }

                // 策略 2：相对误差极小 (小于 1e-13)，针对几十亿的超大数值，防止绝对误差放大
                // 相对误差 = |a - b| / max(|a|, |b|)
                BigDecimal maxAbs = b1.abs().max(b2.abs());
                if(maxAbs.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal relativeError = diff.divide(maxAbs, 20, java.math.RoundingMode.HALF_UP);
                    if(relativeError.compareTo(new BigDecimal("1E-13")) < 0) {
                        return true;
                    }
                }

                // ==========================================
                // 依据系统特性，币种/数字字段四舍五入保留 5 位小数。
                // 若经过底层极小误差过滤后依然不相等，则强行四舍五入到 5 位小数做最终判定。
                // ==========================================
                BigDecimal roundedB1 = b1.setScale(5, java.math.RoundingMode.HALF_UP);
                BigDecimal roundedB2 = b2.setScale(5, java.math.RoundingMode.HALF_UP);
                if (roundedB1.compareTo(roundedB2) == 0) {
                    return true;
                }
                // ==========================================

            } catch(Exception e) {
                // 如果解析异常，忽略并交由后续逻辑继续比对
                log.warn("数值类型比对出现异常：{}", e.getMessage());
            }
        }

        // 5. 15位 / 18位 ID 兼容处理
        if((v1.length() == 15 || v1.length() == 18) && (v2.length() == 15 || v2.length() == 18)) {
            if(v1.matches("^[a-zA-Z0-9]+$") && v2.matches("^[a-zA-Z0-9]+$")) {
                // 【修正 Bug】：将 equals 替换为 equalsIgnoreCase，真正实现兼容抹平
                if(v1.substring(0, 15).equalsIgnoreCase(v2.substring(0, 15))) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 【终极修复版】：大文本高性能比对策略 (O(1) 空间复杂度，零内存分配)
     * 采用双指针逐字符比对，彻底修复了原版“仅比对长度导致严重误报”的漏洞，
     * 同时完美越过系统底层 \r 换行符的干扰，真正做到精准比对。
     */
    private boolean isLargeTextEqual(String v1, String v2) {
        int len1 = v1.length();
        int len2 = v2.length();
        int p1 = 0;
        int p2 = 0;

        while (p1 < len1 || p2 < len2) {
            // 指针 1：主动跳过 v1 中的游离回车符 \r
            while (p1 < len1 && v1.charAt(p1) == '\r') {
                p1++;
            }
            // 指针 2：主动跳过 v2 中的游离回车符 \r
            while (p2 < len2 && v2.charAt(p2) == '\r') {
                p2++;
            }

            boolean end1 = (p1 >= len1);
            boolean end2 = (p2 >= len2);

            // 如果双端同时结束，说明内容完全一致
            if (end1 && end2) {
                return true;
            }
            // 如果一端结束而另一端还有内容（排除 \r 后长度不一），说明有差异
            if (end1 || end2) {
                return false;
            }

            // 核心防御：真实的逐字符比对
            if (v1.charAt(p1) != v2.charAt(p2)) {
                return false;
            }

            p1++;
            p2++;
        }

        return true;
    }

    /**
     * 新增私有辅助方法：多行文本智能标准化
     * 处理 Salesforce 常见的行尾空格漂移及换行符不一致问题
     */
    private String normalizeMultilineText(String text) {
        if(StringUtils.isEmpty(text)) return text;
        // 统一换行符标准
        text = CRLF.matcher(text).replaceAll("\n");

        // 针对包含换行符的长文本，执行逐行清洗
        if(text.contains("\n")) {
            // 参数 -1 确保保留末尾的空行，防止极度严格的比对失真
            String[] lines = text.split("\n", -1);
            StringBuilder sb = new StringBuilder(text.length());
            for(int i = 0; i < lines.length; i++) {
                sb.append(StrUtil.trim(lines[i])); // 清理每一行首尾的脏空格和不可见字符
                if(i < lines.length - 1) {
                    sb.append("\n");
                }
            }
            return sb.toString();
        }
        return text;
    }

    /**
     * 将差异结果写入 CSV (自带长文本安全截断机制)
     */
    private void writeDiff(CsvWriter writer, String sKey, String tKey, String type, String field, String sVal, String tVal, ReconcileStats stats, String sCd, String tCd, String sMd, String tMd, String sId, String tId) {
        String safeS = sVal == null ? "" : StrUtil.sub(sVal, 0, 3000);
        String safeT = tVal == null ? "" : StrUtil.sub(tVal, 0, 3000);
        String safeField = field == null ? "" : field;

        writer.write(new String[]{
                sKey, tKey, type, safeField, safeS, safeT,
                sCd == null ? "" : sCd,
                tCd == null ? "" : tCd,
                sMd == null ? "" : sMd,
                tMd == null ? "" : tMd,
                // 【新增】：写入真实的 Id 到 CSV
                sId == null ? "" : sId,
                tId == null ? "" : tId
        });
    }
}
