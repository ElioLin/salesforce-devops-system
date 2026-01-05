package com.ruoyi.salesforce.service;

import java.util.List;
import java.util.Map;

public interface ISfDescribeApiService {

    /**
     * 获取 Salesforce 所有对象列表 (SObject Global Describe)
     * @param orgId 环境ID
     * @return List of Maps (name, label)
     */
    List<Map<String, String>> getSObjectList(Long orgId) throws Exception;

    /**
     * 获取SF对应对象的所有字段
     * @param orgId 环境Id
     * @param objectName 对象名
     * @return
     * @throws Exception
     */
    List<Map<String, Object>> getSObjectFields(Long orgId, String objectName) throws Exception;
}
