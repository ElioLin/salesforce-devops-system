package com.ruoyi.salesforce.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SfZipUtils {

    /**
     * 从 ZIP 字节流中读取指定文件的内容
     * @param zipBytes Salesforce 返回的 ZIP 字节数组
     * @param targetFileName 我们想找的文件名 (例如 "MyClass.cls")
     * @return 文件内容字符串
     */
    public static String extractFileContent(byte[] zipBytes, String targetFileName) {
        if (zipBytes == null || zipBytes.length == 0) {
            return "";
        }

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // Salesforce 返回的包结构通常是: unpackaged/classes/MyClass.cls
                // 我们只需要判断文件名是否结尾匹配即可
                if (!entry.isDirectory() && entry.getName().endsWith(targetFileName)) {
                    // 读取内容
                    ByteArrayOutputStream content = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        content.write(buffer, 0, len);
                    }
                    return content.toString(StandardCharsets.UTF_8.name());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "Error: File not found in retrieved package.";
    }
}
