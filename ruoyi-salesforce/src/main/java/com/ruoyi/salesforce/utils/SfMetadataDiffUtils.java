package com.ruoyi.salesforce.utils;

import org.apache.commons.codec.digest.DigestUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Salesforce 元数据差异比对工具类
 * 用于解决 XML 乱序、换行符差异导致的虚假差异问题
 */
public class SfMetadataDiffUtils {

    /**
     * 计算文件的语义哈希值
     * @param fileName 文件名
     * @param data 文件二进制内容
     * @return 优化后的 MD5
     */
    public static String computeSemanticHash(String fileName, byte[] data) {
        if (data == null || data.length == 0) return null;

        String content = new String(data, StandardCharsets.UTF_8);
        String ext = getExtension(fileName);

        try {
            // 1. 如果是 XML 结构的元数据，进行 XML 规范化排序
            if (isXmlMetadata(ext)) {
                return calculateXmlHash(content);
            }
            // 2. 如果是代码文件，进行文本标准化（去除回车换行差异）
            else {
                return calculateTextHash(content);
            }
        } catch (Exception e) {
            // 如果解析失败（比如文件损坏），降级为原始 MD5
            return DigestUtils.md5Hex(data);
        }
    }

    private static String getExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex == -1) ? "" : fileName.substring(dotIndex + 1).toLowerCase();
    }

    private static boolean isXmlMetadata(String ext) {
        return ext.equals("xml") || ext.equals("object") || ext.equals("profile") ||
                ext.equals("permissionset") || ext.equals("layout") || ext.equals("workflow") ||
                ext.equals("labels") || ext.equals("flow") || ext.equals("component") || ext.equals("page");
    }

    /**
     * 文本标准化哈希：统一换行符，去除首尾空白
     */
    private static String calculateTextHash(String content) {
        // 统一换行符为 \n
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        // 去除首尾空白
        normalized = normalized.trim();
        return DigestUtils.md5Hex(normalized);
    }

    /**
     * XML 规范化哈希：解析 XML，对子节点进行递归排序，忽略空白节点
     */
    private static String calculateXmlHash(String xmlContent) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setIgnoringElementContentWhitespace(true); // 忽略空白
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();

        Document doc = builder.parse(new InputSource(new StringReader(xmlContent)));

        // 1. 递归移除空白文本节点
        removeEmptyTextNodes(doc.getDocumentElement());

        // 2. 递归排序所有子节点
        sortChildNodes(doc.getDocumentElement());

        // 3. 转换回字符串
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        // 设置输出格式，确保转换的一致性
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");

        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));

        // 4. 对规范化后的 XML 字符串计算哈希
        return calculateTextHash(writer.toString());
    }

    /**
     * 递归移除纯空白的 Text Node
     */
    private static void removeEmptyTextNodes(Node node) {
        NodeList childNodes = node.getChildNodes();
        for (int i = childNodes.getLength() - 1; i >= 0; i--) {
            Node child = childNodes.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                if (child.getTextContent().trim().isEmpty()) {
                    node.removeChild(child);
                }
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                removeEmptyTextNodes(child);
            }
        }
    }

    /**
     * 核心算法：递归对子元素进行排序
     * 排序规则：优先按 fullName 文本内容排序，其次按 TagName 排序，最后按 TextContent 排序
     */
    private static void sortChildNodes(Node node) {
        List<Node> children = new ArrayList<>();
        NodeList childNodes = node.getChildNodes();

        for (int i = 0; i < childNodes.getLength(); i++) {
            children.add(childNodes.item(i));
        }

        // 仅对 Element 类型的节点进行排序，属性保持不变
        Collections.sort(children, new Comparator<Node>() {
            @Override
            public int compare(Node n1, Node n2) {
                if (n1.getNodeType() != n2.getNodeType()) {
                    return Short.compare(n1.getNodeType(), n2.getNodeType());
                }
                if (n1.getNodeType() == Node.ELEMENT_NODE) {
                    Element e1 = (Element) n1;
                    Element e2 = (Element) n2;

                    // 1. 优先比对 Tag Name
                    int tagCompare = e1.getTagName().compareTo(e2.getTagName());
                    if (tagCompare != 0) return tagCompare;

                    // 2. 如果 Tag Name 相同（例如都是 <field>），则尝试获取其 <fullName> 子元素的值进行比对
                    String name1 = getChildText(e1, "fullName");
                    String name2 = getChildText(e2, "fullName");
                    if (name1 != null && name2 != null) {
                        return name1.compareTo(name2);
                    }
                    // 如果是 <application> 等，可能用 <name> 作为 Key
                    String n1Name = getChildText(e1, "name");
                    String n2Name = getChildText(e2, "name");
                    if (n1Name != null && n2Name != null) {
                        return n1Name.compareTo(n2Name);
                    }

                    // 3. 最后比对整个节点的文本内容
                    return e1.getTextContent().trim().compareTo(e2.getTextContent().trim());
                }
                return 0;
            }
        });

        // 重新挂载节点
        for (Node child : children) {
            node.removeChild(child);
            node.appendChild(child);
            // 递归深度排序
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                sortChildNodes(child);
            }
        }
    }

    private static String getChildText(Element parent, String childTagName) {
        NodeList list = parent.getElementsByTagName(childTagName);
        if (list.getLength() > 0) {
            return list.item(0).getTextContent();
        }
        return null;
    }
}