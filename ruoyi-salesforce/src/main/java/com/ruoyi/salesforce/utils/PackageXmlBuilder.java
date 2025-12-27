package com.ruoyi.salesforce.utils;

import com.ruoyi.salesforce.domain.SfDeploymentItem;
import com.sforce.soap.metadata.Package;
import com.sforce.soap.metadata.PackageTypeMembers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PackageXmlBuilder {

    /**
     * 将明细列表转换为 Salesforce Package 对象
     */
    public static Package build(List<SfDeploymentItem> items) {
        Package manifest = new Package();
        manifest.setVersion("58.0"); // API 版本

        // 1. 按 MetadataType 分组
        Map<String, List<SfDeploymentItem>> grouped = items.stream()
                .collect(Collectors.groupingBy(SfDeploymentItem::getMetadataType));

        List<PackageTypeMembers> typeMembersList = new ArrayList<>();

        // 2. 构建 PackageTypeMembers
        for (Map.Entry<String, List<SfDeploymentItem>> entry : grouped.entrySet()) {
            PackageTypeMembers typeMembers = new PackageTypeMembers();
            typeMembers.setName(entry.getKey()); // 类型名 (e.g., ApexClass)

            // 提取成员名数组
            String[] members = entry.getValue().stream()
                    .map(SfDeploymentItem::getMemberName)
                    .toArray(String[]::new);

            typeMembers.setMembers(members);
            typeMembersList.add(typeMembers);
        }

        manifest.setTypes(typeMembersList.toArray(new PackageTypeMembers[0]));
        return manifest;
    }
}