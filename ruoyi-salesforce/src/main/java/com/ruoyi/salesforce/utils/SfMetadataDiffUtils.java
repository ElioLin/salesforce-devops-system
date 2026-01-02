package com.ruoyi.salesforce.utils;

import org.apache.commons.codec.digest.DigestUtils;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Salesforce 元数据差异比对工具类 (v2.0 增强版)
 * 包含：XML排序、去噪、语义哈希
 */
public class SfMetadataDiffUtils {

    // 【新增】黑名单：这些字段在比对时必须忽略，否则会产生 False Positive
    private static final Set<String> IGNORED_TAGS = new HashSet<>(Arrays.asList(
            "id", "createdDate", "createdById", "lastModifiedDate", "lastModifiedById",
            "systemModstamp", "majorNumber", "minorNumber", "namespacePrefix"
    ));

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
        // 激进的文本标准化：去除所有空白符，只比对有效字符
        // 注意：代码文件不能去空格，但比对差异时通常可以忽略行尾空格
        // 这里为了指纹的一致性，采用 trim + 统一换行
        String normalized = content.replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n").trim();
        return DigestUtils.md5Hex(normalized);
    }

    private static String calculateXmlHash(String xmlContent) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setIgnoringElementContentWhitespace(true);
        factory.setNamespaceAware(true); // 重要：处理 xml link
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xmlContent)));

        Element root = doc.getDocumentElement();

        // 1. 【新增】递归移除黑名单节点 (去噪)
        cleanNodes(root);

        // 2. 移除空白文本
        removeEmptyTextNodes(root);

        // 3. 属性排序
        sortAttributes(root);

        // 4. 子节点排序
        sortChildNodes(root);

        // 5. 输出为标准化字符串
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        // 设置不包含 XML 声明，避免版本号差异
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");

        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));

        // 再次进行文本标准化哈希
        return calculateTextHash(writer.toString());
    }

    /**
     * 【新增】清洗节点：移除黑名单标签
     */
    private static void cleanNodes(Node node) {
        NodeList childNodes = node.getChildNodes();
        // 倒序遍历以便删除
        for(int i = childNodes.getLength() - 1; i >= 0; i--) {
            Node child = childNodes.item(i);
            if(child.getNodeType() == Node.ELEMENT_NODE) {
                if(IGNORED_TAGS.contains(child.getNodeName())) {
                    node.removeChild(child);
                } else {
                    cleanNodes(child); // 递归
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

                String n1Name = getChildText(e1, "name");
                String n2Name = getChildText(e2, "name");
                if(n1Name != null && n2Name != null) return n1Name.compareTo(n2Name);

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