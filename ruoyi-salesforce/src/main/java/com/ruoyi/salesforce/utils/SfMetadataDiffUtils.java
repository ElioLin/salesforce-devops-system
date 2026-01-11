package com.ruoyi.salesforce.utils;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.Patch;
import com.ruoyi.salesforce.domain.SfDeploymentItem;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Salesforce 元数据差异比对工具类 (v3.0 合并版)
 * 包含：XML排序、去噪、语义哈希、以及 Unified Diff 文本生成
 */
public class SfMetadataDiffUtils {

    private static final Logger log = LoggerFactory.getLogger(SfMetadataDiffUtils.class);

    // 黑名单：这些字段在比对时忽略
    private static final Set<String> IGNORED_TAGS = new HashSet<>(Arrays.asList(
            "id", "createdDate", "createdById", "lastModifiedDate", "lastModifiedById",
            "systemModstamp", "majorNumber", "minorNumber", "namespacePrefix"
    ));

    // =================================================================
    //  Part 1: Unified Diff 生成 (新增功能)
    // =================================================================

    /**
     * 生成双向 ZIP 包的差异 Map (Git Unified Diff 格式)
     * @param beforeZip 备份的 ZIP (旧/Before)
     * @param afterZip  本次部署的 ZIP (新/After)
     * @param items     部署条目
     * @return Map<Key, DiffText>
     */
    public static Map<String, String> generateDiffMap(byte[] beforeZip, byte[] afterZip, List<SfDeploymentItem> items) {
        Map<String, String> diffMap = new HashMap<>();
        if (items == null || items.isEmpty()) return diffMap;

        try {
            // 1. 解压两个 ZIP 到内存 Map (FileName -> Content)
            Map<String, String> beforeFiles = unzipToMap(beforeZip);
            Map<String, String> afterFiles = unzipToMap(afterZip);

            // 2. 遍历 Items 计算差异
            for (SfDeploymentItem item : items) {
                String key = item.getMetadataType() + "|" + item.getMemberName();
                String memberName = item.getMemberName();

                // 在 Map 中寻找对应的文件内容 (模糊匹配文件名)
                String oldContent = findContent(beforeFiles, memberName);
                String newContent = findContent(afterFiles, memberName);

                // 如果是二进制文件或过大文件，content 会被标记为特殊字符串，跳过文本比对
                if (isBinaryOrTooLarge(oldContent) || isBinaryOrTooLarge(newContent)) {
                    continue;
                }

                // 3. 生成 Unified Diff
                if (oldContent == null && newContent == null) continue;

                List<String> original = oldContent == null ? Collections.emptyList() : Arrays.asList(oldContent.split("\\n"));
                List<String> revised = newContent == null ? Collections.emptyList() : Arrays.asList(newContent.split("\\n"));

                Patch<String> patch = DiffUtils.diff(original, revised);

                // 如果没有差异，就不存
                if (patch.getDeltas().isEmpty()) continue;

                // 生成标准 Unified Diff 格式
                List<String> unifiedDiff = com.github.difflib.UnifiedDiffUtils.generateUnifiedDiff(
                        oldContent == null ? "dev/null" : memberName + " (Base)",
                        newContent == null ? "dev/null" : memberName + " (Deployed)",
                        original, patch, 3);

                diffMap.put(key, String.join("\n", unifiedDiff));
            }

        } catch (Exception e) {
            log.error("生成 Diff 失败", e);
        }
        return diffMap;
    }

    private static Map<String, String> unzipToMap(byte[] zipData) throws IOException {
        Map<String, String> map = new HashMap<>();
        if (zipData == null || zipData.length == 0) return map;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory() || entry.getName().endsWith("package.xml")) continue;

                // 简单的文件类型过滤，只处理文本
                if (isTextFile(entry.getName())) {
                    byte[] bytes = IOUtils.toByteArray(zis);
                    if (bytes.length > 2 * 1024 * 1024) {
                        map.put(entry.getName(), "LARGE_FILE");
                    } else {
                        map.put(entry.getName(), new String(bytes, StandardCharsets.UTF_8));
                    }
                }
            }
        }
        return map;
    }

    private static String findContent(Map<String, String> fileMap, String keyword) {
        for (Map.Entry<String, String> entry : fileMap.entrySet()) {
            if (entry.getKey().contains(keyword)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean isTextFile(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".xml") || n.endsWith(".cls") || n.endsWith(".trigger") ||
                n.endsWith(".page") || n.endsWith(".component") || n.endsWith(".object") ||
                n.endsWith(".field") || n.endsWith(".layout") || n.endsWith(".profile") ||
                n.endsWith(".permissionset") || n.endsWith(".labels") || n.endsWith(".workflow") ||
                n.endsWith(".json") || n.endsWith(".js") || n.endsWith(".css") || n.endsWith(".html") || n.endsWith(".txt");
    }

    private static boolean isBinaryOrTooLarge(String content) {
        return "LARGE_FILE".equals(content);
    }

    // =================================================================
    //  Part 2: 语义哈希 & XML 清洗 (原有功能，保持不变)
    // =================================================================

    public static String computeSemanticHash(String fileName, byte[] data) {
        if(data == null || data.length == 0) return null;
        String content = new String(data, StandardCharsets.UTF_8);
        String ext = getExtension(fileName);

        try {
            if(isXmlMetadata(ext)) {
                return calculateXmlHash(content);
            } else {
                return calculateTextHash(content);
            }
        } catch(Exception e) {
            return DigestUtils.md5Hex(data);
        }
    }

    private static String getExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex == -1) ? "" : fileName.substring(dotIndex + 1).toLowerCase();
    }

    private static boolean isXmlMetadata(String ext) {
        return Arrays.asList("xml", "object", "profile", "permissionset", "layout",
                "workflow", "labels", "flow", "component", "page", "app", "tab").contains(ext);
    }

    private static String calculateTextHash(String content) {
        String normalized = content.replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n").trim();
        return DigestUtils.md5Hex(normalized);
    }

    private static String calculateXmlHash(String xmlContent) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setIgnoringElementContentWhitespace(true);
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xmlContent)));

        Element root = doc.getDocumentElement();
        cleanNodes(root);
        removeEmptyTextNodes(root);
        sortAttributes(root);
        sortChildNodes(root);

        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");

        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));

        return calculateTextHash(writer.toString());
    }

    private static void cleanNodes(Node node) {
        NodeList childNodes = node.getChildNodes();
        for(int i = childNodes.getLength() - 1; i >= 0; i--) {
            Node child = childNodes.item(i);
            if(child.getNodeType() == Node.ELEMENT_NODE) {
                if(IGNORED_TAGS.contains(child.getNodeName())) {
                    node.removeChild(child);
                } else {
                    cleanNodes(child);
                }
            }
        }
    }

    private static void removeEmptyTextNodes(Node node) {
        NodeList childNodes = node.getChildNodes();
        for(int i = childNodes.getLength() - 1; i >= 0; i--) {
            Node child = childNodes.item(i);
            if(child.getNodeType() == Node.TEXT_NODE) {
                if(child.getTextContent().trim().isEmpty()) {
                    node.removeChild(child);
                }
            } else if(child.getNodeType() == Node.ELEMENT_NODE) {
                removeEmptyTextNodes(child);
            }
        }
    }

    private static void sortAttributes(Element element) {
        if(!element.hasAttributes()) return;
        NamedNodeMap attributes = element.getAttributes();
        if(attributes.getLength() <= 1) return;

        List<Attr> attrList = new ArrayList<>();
        for(int i = 0; i < attributes.getLength(); i++) {
            attrList.add((Attr) attributes.item(i));
        }
        attrList.sort(Comparator.comparing(Attr::getName));

        for(Attr attr : attrList) element.removeAttributeNode(attr);
        for(Attr attr : attrList) element.setAttributeNode(attr);
    }

    private static void sortChildNodes(Node node) {
        List<Node> children = new ArrayList<>();
        NodeList childNodes = node.getChildNodes();
        for(int i = 0; i < childNodes.getLength(); i++) {
            children.add(childNodes.item(i));
        }

        Collections.sort(children, (n1, n2) -> {
            if(n1.getNodeType() != n2.getNodeType()) return Short.compare(n1.getNodeType(), n2.getNodeType());
            if(n1.getNodeType() == Node.ELEMENT_NODE) {
                Element e1 = (Element) n1;
                Element e2 = (Element) n2;
                int tagCompare = e1.getTagName().compareTo(e2.getTagName());
                if(tagCompare != 0) return tagCompare;

                // 尝试取 name 或 fullName 排序
                String name1 = getChildText(e1, "fullName");
                String name2 = getChildText(e2, "fullName");
                if(name1 != null && name2 != null) return name1.compareTo(name2);

                return e1.getTextContent().trim().compareTo(e2.getTextContent().trim());
            }
            return 0;
        });

        for(Node child : children) {
            node.removeChild(child);
            node.appendChild(child);
            if(child.getNodeType() == Node.ELEMENT_NODE) {
                sortAttributes((Element) child);
                sortChildNodes(child);
            }
        }
    }

    private static String getChildText(Element parent, String childTagName) {
        NodeList list = parent.getElementsByTagName(childTagName);
        return list.getLength() > 0 ? list.item(0).getTextContent() : null;
    }
}