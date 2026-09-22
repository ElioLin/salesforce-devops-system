package com.ruoyi.salesforce.utils;

import com.ruoyi.salesforce.domain.SfDeploymentItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 元数据清洗工具 (终极遍历版)
 * 采用全文档节点遍历策略，彻底解决因命名空间导致的节点查找失败问题。
 * 确保 compactLayoutAssignment 等顽固配置被精准移除。
 */
public class MetadataCleaner {

    private static final Logger log = LoggerFactory.getLogger(MetadataCleaner.class);

    // 【Profile黑名单】
    private static final Set<String> NODES_TO_REMOVE_IN_PROFILE = new HashSet<>();
    // 【Object黑名单】增量部署时必须移除的全局配置
    private static final Set<String> NODES_TO_REMOVE_IN_OBJECT_PARTIAL = new HashSet<>();

    static {
        // Profile
        NODES_TO_REMOVE_IN_PROFILE.add("userPermissions");
        NODES_TO_REMOVE_IN_PROFILE.add("loginHours");
        NODES_TO_REMOVE_IN_PROFILE.add("loginIpRanges");
        NODES_TO_REMOVE_IN_PROFILE.add("passwordPolicies");
        NODES_TO_REMOVE_IN_PROFILE.add("sessionSettings");

        // Object (这些是导致 invalid ... assigned 错误的元凶)
        NODES_TO_REMOVE_IN_OBJECT_PARTIAL.add("compactLayoutAssignment");
        NODES_TO_REMOVE_IN_OBJECT_PARTIAL.add("searchLayouts");
        NODES_TO_REMOVE_IN_OBJECT_PARTIAL.add("profileSearchLayouts");
        NODES_TO_REMOVE_IN_OBJECT_PARTIAL.add("actionOverrides");
        NODES_TO_REMOVE_IN_OBJECT_PARTIAL.add("customHelpPage");
//        NODES_TO_REMOVE_IN_OBJECT_PARTIAL.add("listViews");
    }

    public static byte[] clean(byte[] zipBytes, List<SfDeploymentItem> items) {
        if(zipBytes == null || zipBytes.length == 0) return zipBytes;

        // 1. 构建白名单
        Set<String> validFields = new HashSet<>();
        Set<String> validClasses = new HashSet<>();
        Set<String> validPages = new HashSet<>();
        Set<String> validTabs = new HashSet<>();
        Set<String> validObjects = new HashSet<>();
        Set<String> validRecordTypes = new HashSet<>();
        Set<String> validApps = new HashSet<>();

        Set<String> validListViews = new HashSet<>();
        Set<String> validValidationRules = new HashSet<>();
        Set<String> validWebLinks = new HashSet<>();
        Set<String> validCompactLayouts = new HashSet<>();
        Set<String> validFieldSets = new HashSet<>();

        if(items != null) {
            for(SfDeploymentItem item : items) {
                String type = item.getMetadataType();
                String name = item.getMemberName();

                if("CustomField".equals(type)) validFields.add(name);
                else if("ApexClass".equals(type)) validClasses.add(name);
                else if("ApexPage".equals(type)) validPages.add(name);
                else if("CustomTab".equals(type)) validTabs.add(name);
                    // 只有明确选择了 CustomObject 类型的元数据，才视为全量部署
                else if("CustomObject".equals(type)) validObjects.add(name);
                else if("RecordType".equals(type)) validRecordTypes.add(name);
                else if("CustomApplication".equals(type)) validApps.add(name);
                else if("ListView".equals(type)) validListViews.add(name);
                else if("ValidationRule".equals(type)) validValidationRules.add(name);
                else if("WebLink".equals(type)) validWebLinks.add(name);
                else if("CompactLayout".equals(type)) validCompactLayouts.add(name);
                else if("FieldSet".equals(type)) validFieldSets.add(name);
            }
        }

        try(ByteArrayInputStream bais = new ByteArrayInputStream(zipBytes);
            ZipInputStream zis = new ZipInputStream(bais);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ZipOutputStream zos = new ZipOutputStream(baos)) {

            ZipEntry entry;
            while((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] content = readStream(zis);

                // 默认写入清洗后的内容
                byte[] finalContent = content;

                // A. Profile / PermissionSet
                if(!entry.isDirectory() && (name.endsWith(".profile") || name.endsWith(".permissionset"))) {
                    try {
                        finalContent = cleanPermissionFile(content, validFields, validClasses, validPages, validTabs, validObjects, validRecordTypes, validApps);
//                        log.info("已清洗权限文件: {}", name);
                    } catch(Exception e) {
                        log.error("清洗权限文件失败: " + name, e);
                    }
                }
                // B. Object 文件
                else if(!entry.isDirectory() && name.endsWith(".object")) {
                    try {
                        String objectName = getObjectNameFromPath(name);
                        boolean isFullDeploy = validObjects.contains(objectName);

                        log.info("正在处理对象文件: {}, 部署模式: {}", name, isFullDeploy ? "全量(保留配置)" : "增量(执行清洗)");

                        finalContent = cleanObjectFile(content, objectName, validFields, validListViews,
                                validRecordTypes, validValidationRules, validWebLinks,
                                validCompactLayouts, validFieldSets, isFullDeploy);
                    } catch(Exception e) {
                        log.error("清洗对象文件失败: " + name, e);
                    }
                }

                ZipEntry newEntry = new ZipEntry(name);
                zos.putNextEntry(newEntry);
                zos.write(finalContent);
                zos.closeEntry();
            }
            zos.finish();
            return baos.toByteArray();

        } catch(Exception e) {
            log.error("元数据清洗异常", e);
            return zipBytes;
        }
    }

    private static byte[] cleanPermissionFile(byte[] content, Set<String> validFields, Set<String> validClasses, Set<String> validPages, Set<String> validTabs, Set<String> validObjects, Set<String> validRecordTypes, Set<String> validApps) throws Exception {
        Document doc = parseXml(content);
        Element root = doc.getDocumentElement();

        // 移除黑名单 (Profile)
        removeBlacklistNodesUniversal(doc, NODES_TO_REMOVE_IN_PROFILE);

        filterNodes(root, "fieldPermissions", "field", validFields);
        filterNodes(root, "classAccesses", "apexClass", validClasses);
        filterNodes(root, "pageAccesses", "apexPage", validPages);
        filterNodes(root, "objectPermissions", "object", validObjects);
        filterNodes(root, "tabVisibilities", "tab", validTabs);
        filterNodes(root, "tabSettings", "tab", validTabs);
        filterNodes(root, "recordTypeVisibilities", "recordType", validRecordTypes);
        filterNodes(root, "applicationVisibilities", "application", validApps);
        return docToBytes(doc);
    }

    private static byte[] cleanObjectFile(byte[] content, String objectName,
                                          Set<String> validFields, Set<String> validListViews,
                                          Set<String> validRecordTypes, Set<String> validValidationRules,
                                          Set<String> validWebLinks, Set<String> validCompactLayouts,
                                          Set<String> validFieldSets, boolean isFullDeploy) throws Exception {
        Document doc = parseXml(content);
        Element root = doc.getDocumentElement();

        // 1. 增量部署时，移除全局配置
        if(!isFullDeploy) {
            // 【核心变更】使用 Universal 移除方法
            removeBlacklistNodesUniversal(doc, NODES_TO_REMOVE_IN_OBJECT_PARTIAL);

            filterUnrequestedObjectChildren(root, "listViews", objectName, validListViews);
            filterUnrequestedObjectChildren(root, "fields", objectName, validFields);
            filterUnrequestedObjectChildren(root, "recordTypes", objectName, validRecordTypes);
            filterUnrequestedObjectChildren(root, "validationRules", objectName, validValidationRules);
            filterUnrequestedObjectChildren(root, "webLinks", objectName, validWebLinks);
            filterUnrequestedObjectChildren(root, "compactLayouts", objectName, validCompactLayouts);
            filterUnrequestedObjectChildren(root, "fieldSets", objectName, validFieldSets);
        }

        // 2. 清洗 RecordType Picklist
        NodeList recordTypes = root.getElementsByTagName("recordTypes");
        if(recordTypes.getLength() > 0) {
            for(int i = 0; i < recordTypes.getLength(); i++) {
                Element rt = (Element) recordTypes.item(i);
                NodeList picklistValues = rt.getElementsByTagName("picklistValues");
                List<Node> pvsToRemove = new ArrayList<>();
                for(int j = 0; j < picklistValues.getLength(); j++) {
                    Element pv = (Element) picklistValues.item(j);
                    String picklistName = getTagValue(pv, "picklist");
                    String fullFieldName = objectName + "." + picklistName;
                    boolean keep = validFields.contains(fullFieldName) || validFields.contains(picklistName);
                    if(!keep) pvsToRemove.add(pv);
                }
                for(Node n : pvsToRemove) rt.removeChild(n);
            }
        }
        return docToBytes(doc);
    }

    /**
     * 通用对象子元素精准白名单过滤
     * 作用：遍历指定的子元素(如 fields, validationRules)，如果它不在前端勾选的白名单中，则从 XML 中安全移除。
     * 这彻底阻断了 Salesforce API "搭便车"返回冗余数据的问题。
     */
    private static void filterUnrequestedObjectChildren(Element root, String tagName, String objectName, Set<String> validNames) {
        NodeList nodes = root.getElementsByTagName(tagName);
        List<Node> toRemove = new ArrayList<>();

        for(int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String fullName = getTagValue(el, "fullName"); // Salesforce XML内部是短名称，如 PAD_Import

            if (fullName != null) {
                // 拼接成长名称以匹配数据库中存储的 MemberName (如 Import_Field_Mapping__mdt.PAD_Import)
                String fullMemberName = objectName + "." + fullName;

                // 如果用户本次部署没有明确勾选这个子元素，就将其判定为冗余数据
                if (!validNames.contains(fullMemberName)) {
                    toRemove.add(el);
                }
            }
        }

        // 安全移除所有未选中的冗余节点
        for(Node n : toRemove) {
            if(n.getParentNode() != null) {
                n.getParentNode().removeChild(n);
            }
        }
    }

    /**
     * 【终极修复】通用全文档节点移除
     * 不再依赖 getElementsByTagName("具体名字")，而是获取所有节点手动比对。
     * 解决命名空间、前缀、解析器差异导致找不到节点的问题。
     */
    private static void removeBlacklistNodesUniversal(Document doc, Set<String> blacklist) {
        // 获取文档中所有标签 ("*")，这是最底层的获取方式
        NodeList allNodes = doc.getElementsByTagName("*");
        List<Node> nodesToRemove = new ArrayList<>();

        // 遍历所有节点
        for(int i = 0; i < allNodes.getLength(); i++) {
            Node node = allNodes.item(i);

            // 获取各种可能的名称
            String nodeName = node.getNodeName();   // 可能带前缀，如 sf:compactLayoutAssignment
            String localName = node.getLocalName(); // 不带前缀，如 compactLayoutAssignment

            // 只要其中一个命中了黑名单，就标记删除
            if(blacklist.contains(nodeName) || (localName != null && blacklist.contains(localName))) {
//                log.info("【强制移除】成功锁定节点: [Name={}, Local={}]，准备删除。", nodeName, localName);
                nodesToRemove.add(node);
            }
        }

        // 执行删除 (倒序删除是个好习惯，但这里我们是先收集再删除，也很安全)
        for(Node n : nodesToRemove) {
            // 双重检查：防止父节点已经被删除导致空指针
            if(n.getParentNode() != null) {
                n.getParentNode().removeChild(n);
            }
        }
    }

    private static void filterNodes(Element root, String parentTagName, String keyTagName, Set<String> whiteList) {
        NodeList nodes = root.getElementsByTagName(parentTagName);
        List<Node> toRemove = new ArrayList<>();
        for(int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String keyValue = getTagValue(el, keyTagName);
            if(keyValue != null && !whiteList.contains(keyValue)) {
                toRemove.add(el);
            }
        }
        for(Node n : toRemove) n.getParentNode().removeChild(n);
    }

    private static String getObjectNameFromPath(String path) {
        int lastSlash = path.lastIndexOf('/');
        String fileName = (lastSlash >= 0) ? path.substring(lastSlash + 1) : path;
        if(fileName.endsWith(".object")) {
            return fileName.substring(0, fileName.length() - 7);
        }
        return fileName;
    }

    private static String getTagValue(Element element, String tagName) {
        NodeList list = element.getElementsByTagName(tagName);
        if(list.getLength() > 0) return list.item(0).getTextContent();
        return null;
    }

    private static Document parseXml(byte[] content) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false); // 保持关闭命名空间感知，这对简单匹配最有效
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(content));
    }

    private static byte[] docToBytes(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        // doc.setXmlStandalone(true); // 部分环境可能不兼容此方法，如果报错请注释掉
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(doc), new StreamResult(out));
        return out.toByteArray();
    }

    private static byte[] readStream(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while((len = in.read(buffer)) > -1) out.write(buffer, 0, len);
        return out.toByteArray();
    }
}
