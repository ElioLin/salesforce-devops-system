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
 * Salesforce 元数据差异比对工具类 (v5.0 终极增强版)
 * 修复：
 * 1. 解决 LWC/Aura 因 "unpackaged/" 根目录导致的路径匹配失败问题。
 * 2. 解决 VF Component/Apex 优先匹配到 -meta.xml 而非代码本体的问题。
 * 3. 补全 Translation, GlobalValueSet, StaticResource 等遗漏的文本类型。
 */
public class SfMetadataDiffUtils {

    private static final Logger log = LoggerFactory.getLogger(SfMetadataDiffUtils.class);

    // XML 语义比对黑名单
    private static final Set<String> IGNORED_TAGS = new HashSet<>(Arrays.asList(
            "id", "createdDate", "createdById", "lastModifiedDate", "lastModifiedById",
            "systemModstamp", "majorNumber", "minorNumber", "namespacePrefix"
    ));

    // Object 子元素 -> 父文件映射
    private static final Set<String> OBJECT_CHILDREN = new HashSet<>(Arrays.asList(
            "CustomField", "WebLink", "ValidationRule", "RecordType", "ListView", "FieldSet",
            "CompactLayout", "BusinessProcess", "Index", "SharingReason"
    ));

    // Workflow 子元素 -> 父文件映射
    private static final Set<String> WORKFLOW_CHILDREN = new HashSet<>(Arrays.asList(
            "WorkflowRule", "WorkflowAlert", "WorkflowFieldUpdate", "WorkflowOutboundMessage", "WorkflowTask"
    ));

    // Bundle 类型 (LWC/Aura)
    private static final Set<String> BUNDLE_TYPES = new HashSet<>(Arrays.asList(
            "LightningComponentBundle", "AuraDefinitionBundle"
    ));

    // =================================================================
    //  Part 1: Unified Diff 生成
    // =================================================================

    public static Map<String, String> generateDiffMap(byte[] beforeZip, byte[] afterZip, List<SfDeploymentItem> items) {
        Map<String, String> diffMap = new HashMap<>();
        if(items == null || items.isEmpty()) return diffMap;

        try {
            Map<String, String> beforeFiles = unzipToMap(beforeZip);
            Map<String, String> afterFiles = unzipToMap(afterZip);

            for(SfDeploymentItem item : items) {
                String key = item.getMetadataType() + "|" + item.getMemberName();
                String type = item.getMetadataType();
                String name = item.getMemberName();

                String diffText = null;

                if(BUNDLE_TYPES.contains(type)) {
                    // 【修复点1】Bundle 类型使用包含匹配，忽略根目录差异
                    diffText = generateBundleDiff(beforeFiles, afterFiles, name, type);
                } else {
                    // 【修复点2】单文件类型使用优先级匹配 (Code > Meta)
                    String targetFileName = resolveTargetFilename(type, name, beforeFiles.keySet());
                    if(targetFileName == null) {
                        targetFileName = resolveTargetFilename(type, name, afterFiles.keySet());
                    }

                    if(targetFileName != null) {
                        String oldContent = beforeFiles.get(targetFileName);
                        String newContent = afterFiles.get(targetFileName);
                        String displayTitle = (isChildType(type) ? name + " (in " + targetFileName + ")" : targetFileName);
                        diffText = computeSingleFileDiff(oldContent, newContent, displayTitle);
                    }
                }

                if(diffText != null && !diffText.isEmpty()) {
                    diffMap.put(key, diffText);
                }
            }

        } catch(Exception e) {
            log.error("生成 Diff 失败", e);
        }
        return diffMap;
    }

    /**
     * 【核心修复】智能解析目标文件名 (引入优先级机制)
     * 解决问题：有 MyComp.component 和 MyComp.component-meta.xml 时，确保选中 MyComp.component
     */
    private static String resolveTargetFilename(String type, String memberName, Set<String> fileList) {
        // 1. 特殊映射逻辑 (Object/Workflow/Label)
        if(OBJECT_CHILDREN.contains(type)) {
            String parentObj = memberName.contains(".") ? memberName.split("\\.")[0] : memberName;
            return findInSet(fileList, "objects/" + parentObj + ".object");
        }
        if(WORKFLOW_CHILDREN.contains(type)) {
            String parentObj = memberName.contains(".") ? memberName.split("\\.")[0] : memberName;
            return findInSet(fileList, "workflows/" + parentObj + ".workflow");
        }
        if("CustomLabel".equals(type)) {
            return findInSet(fileList, "labels/CustomLabels.labels");
        }

        // 2. 常规匹配 (Apex, VF, Trigger, etc.)
        List<String> candidates = new ArrayList<>();
        for(String file : fileList) {
            int lastSlash = file.lastIndexOf("/");
            String simpleName = lastSlash == -1 ? file : file.substring(lastSlash + 1);

            // 匹配规则：文件名以 "MemberName." 开头
            if(simpleName.startsWith(memberName + ".")) {
                candidates.add(file);
            }
        }

        if(candidates.isEmpty()) return null;
        if(candidates.size() == 1) return candidates.get(0);

        // 【优先级仲裁】如果存在多个候选 (例如 .cls 和 .cls-meta.xml)，优先选本体
        // 规则：不以 -meta.xml 结尾的文件优先级更高
        candidates.sort((f1, f2) -> {
            boolean f1Meta = f1.endsWith("-meta.xml");
            boolean f2Meta = f2.endsWith("-meta.xml");
            if(f1Meta && !f2Meta) return 1;  // f1 是 meta, f2 不是 -> f2 排前面
            if(!f1Meta && f2Meta) return -1; // f1 不是, f2 是 -> f1 排前面
            return f1.compareTo(f2);
        });

        return candidates.get(0);
    }

    /**
     * 【核心修复】Bundle 聚合比对 (支持 unpackaged/ 前缀)
     */
    private static String generateBundleDiff(Map<String, String> beforeFiles, Map<String, String> afterFiles, String bundleName, String type) {
        StringBuilder bundleDiff = new StringBuilder();
        Set<String> allFiles = new HashSet<>();

        // 构建搜索特征串，例如 "/lwc/MyComp/"
        String typeDir = "LightningComponentBundle".equals(type) ? "lwc" : "aura";
        String searchPattern = "/" + typeDir + "/" + bundleName + "/";
        String startPattern = typeDir + "/" + bundleName + "/"; // 针对没有根目录的情况

        collectBundleFiles(beforeFiles.keySet(), searchPattern, startPattern, allFiles);
        collectBundleFiles(afterFiles.keySet(), searchPattern, startPattern, allFiles);

        List<String> sortedFiles = new ArrayList<>(allFiles);
        Collections.sort(sortedFiles);

        for(String fileName : sortedFiles) {
            String oldContent = beforeFiles.get(fileName);
            String newContent = afterFiles.get(fileName);
            String diff = computeSingleFileDiff(oldContent, newContent, fileName);

            if(diff != null) {
                bundleDiff.append(diff).append("\n\n");
            }
        }

        return bundleDiff.length() > 0 ? bundleDiff.toString() : null;
    }

    private static void collectBundleFiles(Set<String> sourceKeys, String pattern, String startPattern, Set<String> targetSet) {
        for(String key : sourceKeys) {
            // 兼容 "unpackaged/lwc/..." (包含) 和 "lwc/..." (开头)
            if(key.contains(pattern) || key.startsWith(startPattern)) {
                targetSet.add(key);
            }
        }
    }

    private static String findInSet(Set<String> fileList, String suffix) {
        for(String file : fileList) {
            if(file.endsWith(suffix)) return file;
        }
        return null;
    }

    private static boolean isChildType(String type) {
        return OBJECT_CHILDREN.contains(type) || WORKFLOW_CHILDREN.contains(type) || "CustomLabel".equals(type);
    }

    private static String computeSingleFileDiff(String oldContent, String newContent, String title) {
        if(isBinaryOrTooLarge(oldContent) || isBinaryOrTooLarge(newContent)) {
            return "Binary file or too large to diff (Skipped).";
        }
        if(oldContent == null && newContent == null) return null;

        List<String> original = oldContent == null ? Collections.emptyList() : Arrays.asList(oldContent.split("\\n"));
        List<String> revised = newContent == null ? Collections.emptyList() : Arrays.asList(newContent.split("\\n"));

        Patch<String> patch = DiffUtils.diff(original, revised);
        if(patch.getDeltas().isEmpty()) return null;

        List<String> unifiedDiff = com.github.difflib.UnifiedDiffUtils.generateUnifiedDiff(
                oldContent == null ? "dev/null" : title + " (Base)",
                newContent == null ? "dev/null" : title + " (Current)",
                original, patch, 3);

        return String.join("\n", unifiedDiff);
    }

    private static Map<String, String> unzipToMap(byte[] zipData) throws IOException {
        Map<String, String> map = new HashMap<>();
        if(zipData == null || zipData.length == 0) return map;

        try(ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if(entry.isDirectory() || name.endsWith("package.xml")) continue;

                if(isTextFile(name)) {
                    byte[] bytes = IOUtils.toByteArray(zis);
                    if(bytes.length > 2 * 1024 * 1024) {
                        map.put(name, "LARGE_FILE");
                    } else {
                        map.put(name, new String(bytes, StandardCharsets.UTF_8));
                    }
                }
            }
        }
        return map;
    }

    /**
     * 【核心修复】全量文本类型白名单
     */
    private static boolean isTextFile(String name) {
        String n = name.toLowerCase();
        // 1. 代码类
        if(n.endsWith(".xml") || n.endsWith(".cls") || n.endsWith(".trigger") || n.endsWith(".json") ||
                n.endsWith(".js") || n.endsWith(".css") || n.endsWith(".html") || n.endsWith(".txt") ||
                n.endsWith(".yaml") || n.endsWith(".properties")) return true;

        // 2. 页面与组件 (修复 VF Component 识别)
        if(n.endsWith(".page") || n.endsWith(".component") || n.endsWith(".svg") || n.endsWith(".auradoc")) return true;

        // 3. 配置类
        if(n.endsWith(".object") || n.endsWith(".field") || n.endsWith(".layout") ||
                n.endsWith(".profile") || n.endsWith(".permissionset") || n.endsWith(".labels") ||
                n.endsWith(".workflow") || n.endsWith(".flow") || n.endsWith(".settings")) return true;

        // 4. 其他常见文本元数据 (补充遗漏)
        if(n.endsWith(".flexipage") || n.endsWith(".tab") || n.endsWith(".app") || n.endsWith(".email") ||
                n.endsWith(".remotesite") || n.endsWith(".group") || n.endsWith(".queue") || n.endsWith(".role") ||
                n.endsWith(".sharingrules") || n.endsWith(".network") || n.endsWith(".weblink") ||
                n.endsWith(".quickaction") || n.endsWith(".reporttype") ||
                n.endsWith(".globalvalueset") || n.endsWith(".standardvalueset") || // 值集
                n.endsWith(".translation") || n.endsWith(".objecttranslation") || // 翻译
                n.endsWith(".md") || n.endsWith(".custommetadata") || // 自定义元数据
                n.endsWith(".resource") || // 静态资源 (文本型)
                n.endsWith(".namedcredential") || n.endsWith(".corswhitelistorigin")) return true;

        return false;
    }

    private static boolean isBinaryOrTooLarge(String content) {
        return "LARGE_FILE".equals(content);
    }

    // =================================================================
    //  Part 2: 语义哈希 (保持不变，用于 MD5 计算)
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
                "workflow", "labels", "flow", "component", "page", "app", "tab", "flexipage").contains(ext);
    }

    private static String calculateTextHash(String content) {
        String normalized = content.replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n").trim();
        return DigestUtils.md5Hex(normalized);
    }

    private static String calculateXmlHash(String xmlContent) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setIgnoringElementContentWhitespace(true);
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

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