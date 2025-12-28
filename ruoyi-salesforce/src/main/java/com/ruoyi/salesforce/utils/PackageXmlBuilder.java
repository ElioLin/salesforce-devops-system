package com.ruoyi.salesforce.utils;

import com.ruoyi.salesforce.domain.SfDeploymentItem;
import com.sforce.soap.metadata.Package;
import com.sforce.soap.metadata.PackageTypeMembers;

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
        for (SfDeploymentItem item : items) {
            typesMap.computeIfAbsent(item.getMetadataType(), k -> new ArrayList<>())
                    .add(item.getMemberName());
        }

        // 2. 转换为 Salesforce SDK 对象
        List<PackageTypeMembers> typeMembersList = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : typesMap.entrySet()) {
            PackageTypeMembers typeMembers = new PackageTypeMembers();
            typeMembers.setName(entry.getKey()); // 类型名
            typeMembers.setMembers(entry.getValue().toArray(new String[0])); // 成员名列表
            typeMembersList.add(typeMembers);
        }

        manifest.setTypes(typeMembersList.toArray(new PackageTypeMembers[0]));
        return manifest;
    }
}