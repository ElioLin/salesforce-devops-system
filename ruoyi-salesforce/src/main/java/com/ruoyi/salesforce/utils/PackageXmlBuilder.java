package com.ruoyi.salesforce.utils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.ruoyi.salesforce.domain.SfDeploymentItem;
import com.sforce.soap.metadata.Package;
import com.sforce.soap.metadata.PackageTypeMembers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Salesforce Package.xml 构建工具
 * 用于混合部署和检索
 */
public class PackageXmlBuilder {

    public static Package build(List<SfDeploymentItem> items) {
        Package manifest = new Package();
        manifest.setVersion("58.0"); // API 版本

        // 1. 按 MetadataType 分组
        Map<String, List<String>> typesMap = new HashMap<>();
        for(SfDeploymentItem item : items) {
            typesMap.computeIfAbsent(item.getMetadataType(), k -> new ArrayList<>())
                    .add(item.getMemberName());
        }

        // 2. 转换为 Salesforce SDK 对象
        List<PackageTypeMembers> typeMembersList = new ArrayList<>();
        for(Map.Entry<String, List<String>> entry : typesMap.entrySet()) {
            PackageTypeMembers typeMembers = new PackageTypeMembers();
            typeMembers.setName(entry.getKey()); // 类型名
            typeMembers.setMembers(entry.getValue().toArray(new String[0])); // 成员名列表
            typeMembersList.add(typeMembers);
        }

        manifest.setTypes(typeMembersList.toArray(new PackageTypeMembers[0]));
        return manifest;
    }

    /**
     * 构建混合回滚包 (Hybrid Rollback Package)
     * <p>
     * 逻辑：
     * 1. 读取 originalZip (备份的旧代码，用于还原 UPDATE 的部分)
     * 2. 复制 originalZip 中的所有文件到新的 ZIP 流
     * 3. 生成 destructiveChanges.xml (用于删除 CREATE 的部分) 并写入新的 ZIP 流
     * 4. 确保 package.xml 存在 (通常复用备份包里的 package.xml 即可)
     *
     * @param originalZip      原始备份 ZIP 文件的字节数组
     * @param destructiveItems 需要删除的元数据列表 (Key: type, name)
     * @return 包含 destructiveChanges.xml 的新 ZIP 包字节数组
     */
    public static byte[] addDestructiveChanges(byte[] originalZip, List<Map<String, String>> destructiveItems) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // 使用 try-with-resources 确保流关闭
        try(ZipOutputStream zos = new ZipOutputStream(baos)) {

            // Set 集合用于记录已经写入 ZIP 的文件名，防止重复写入
            Set<String> existingEntries = new HashSet<>();

            // =======================================================
            // 步骤 1: 复制原 ZIP 包中的所有内容 (还原旧代码)
            // =======================================================
            if(originalZip != null && originalZip.length > 0) {
                try(ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(originalZip))) {
                    ZipEntry entry;
                    byte[] buffer = new byte[1024];
                    while((entry = zis.getNextEntry()) != null) {
                        String name = entry.getName();

                        // 记录文件名
                        existingEntries.add(name);

                        // 复制 Entry
                        zos.putNextEntry(new ZipEntry(name));
                        int len;
                        while((len = zis.read(buffer)) > 0) {
                            zos.write(buffer, 0, len);
                        }
                        zos.closeEntry();
                    }
                }
            }

            // =======================================================
            // 步骤 2: 注入 destructiveChanges.xml (删除新增代码)
            // =======================================================
            if(destructiveItems != null && !destructiveItems.isEmpty()) {
                String destructiveXmlContent = buildDestructiveXml(destructiveItems);

                String destEntryName = "destructiveChanges.xml";
                // 只有当原包里没有这个文件时才写入（通常备份包里肯定没有）
                if(!existingEntries.contains(destEntryName)) {
                    ZipEntry destEntry = new ZipEntry(destEntryName);
                    zos.putNextEntry(destEntry);
                    zos.write(destructiveXmlContent.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                    existingEntries.add(destEntryName);
                }
            }

            // =======================================================
            // 步骤 3: 检查并兜底 package.xml
            // =======================================================
            // 如果是纯删除操作（originalZip 为空），或者原包里居然没有 package.xml
            // Salesforce 部署必须包含 package.xml，即使它是空的
            if(!existingEntries.contains("package.xml")) {
                String emptyPackageXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<Package xmlns=\"http://soap.sforce.com/2006/04/metadata\">\n" +
                        "    <version>58.0</version>\n" +
                        "</Package>";
                ZipEntry pkgEntry = new ZipEntry("package.xml");
                zos.putNextEntry(pkgEntry);
                zos.write(emptyPackageXml.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }

        return baos.toByteArray();
    }

    /**
     * 生成 destructiveChanges.xml 的内容
     */
    private static String buildDestructiveXml(List<Map<String, String>> items) {
        // 按 MetadataType 分组
        Map<String, List<String>> typeMap = new HashMap<>();
        for(Map<String, String> item : items) {
            String type = item.get("type");
            String name = item.get("name");
            typeMap.computeIfAbsent(type, k -> new ArrayList<>()).add(name);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<Package xmlns=\"http://soap.sforce.com/2006/04/metadata\">\n");

        // 遍历分组生成 XML
        for(Map.Entry<String, List<String>> entry : typeMap.entrySet()) {
            sb.append("    <types>\n");
            for(String member : entry.getValue()) {
                sb.append("        <members>").append(member).append("</members>\n");
            }
            sb.append("        <name>").append(entry.getKey()).append("</name>\n");
            sb.append("    </types>\n");
        }

        sb.append("    <version>58.0</version>\n");
        sb.append("</Package>");

        return sb.toString();
    }

    /**
     * 【新增】解析 ZIP 包用于前端预览 (提取文件列表和文本内容)
     * 逻辑源自 SfDeploymentServiceImpl.previewPackage
     */
    public static Map<String, Object> parseZipForPreview(byte[] zipBytes) throws IOException {
        List<String> fileList = new ArrayList<>();
        Map<String, String> fileContents = new HashMap<>();
        String packageXmlContent = "";

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory()) {
                    fileList.add(name);

                    // 判断是否为文本文件
                    if (isPreviewableTextFile(name)) {
                        // 读取流 (限制大小 1MB，防止浏览器崩溃)
                        byte[] contentBytes = readStream(zis);
                        if (contentBytes.length < 1024 * 1024) {
                            String content = new String(contentBytes, StandardCharsets.UTF_8);
                            fileContents.put(name, content);
                            if (name.endsWith("package.xml")) {
                                packageXmlContent = content;
                            }
                        } else {
                            fileContents.put(name, "(文件过大 >1MB，请下载查看)");
                        }
                    } else {
                        fileContents.put(name, "(二进制文件或不支持的格式，不支持在线预览)");
                    }
                }
            }
        }

        // 排序
        Collections.sort(fileList);

        Map<String, Object> result = new HashMap<>();
        result.put("files", fileList);
        result.put("fileContents", fileContents);
        result.put("packageXml", packageXmlContent);
        result.put("size", zipBytes.length);
        return result;
    }

    /**
     * 辅助方法：判断文件后缀
     */
    private static boolean isPreviewableTextFile(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".xml") || n.endsWith(".cls") || n.endsWith(".trigger") ||
                n.endsWith(".page") || n.endsWith(".component") || n.endsWith(".object") ||
                n.endsWith(".field") || n.endsWith(".layout") || n.endsWith(".profile") ||
                n.endsWith(".permissionset") || n.endsWith(".js") || n.endsWith(".css") ||
                n.endsWith(".html") || n.endsWith(".txt") || n.endsWith(".json") ||
                n.endsWith(".labels") || n.endsWith(".workflow") || n.endsWith(".flow");
    }

    /**
     * 辅助方法：读取流防止关闭
     */
    private static byte[] readStream(java.io.InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
        return out.toByteArray();
    }
}