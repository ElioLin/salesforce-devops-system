package com.ruoyi.salesforce.service.impl;

import cn.hutool.http.HttpRequest;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.salesforce.domain.SfOrg;
import com.ruoyi.salesforce.service.ISfDescribeApiService;
import com.ruoyi.salesforce.service.ISfMetadataService;
import com.ruoyi.salesforce.service.ISfOrgService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class SfDescribeApiServiceImpl implements ISfDescribeApiService {
    @Autowired
    private ISfMetadataService sfMetadataService;

    @Autowired
    private ISfOrgService sfOrgService;
    @Override
    public List<Map<String, String>> getSObjectList(Long orgId) throws Exception {
        // 使用 executeWithRetry 确保 Token 有效
        return sfMetadataService.executeWithRetry(orgId, () -> {
            SfOrg org = sfOrgService.selectSfOrgById(orgId);
            String url = org.getInstanceUrl() + "/services/data/v58.0/sobjects/";

            // 发送 GET 请求
            String result = HttpRequest.get(url)
                    .header("Authorization", "Bearer " + org.getAccessToken())
                    .timeout(30000)
                    .execute()
                    .body();

            JSONObject json = JSON.parseObject(result);
            JSONArray sobjects = json.getJSONArray("sobjects");

            List<Map<String, String>> list = new ArrayList<>();
            if (sobjects != null) {
                for (int i = 0; i < sobjects.size(); i++) {
                    JSONObject obj = sobjects.getJSONObject(i);
                    // 过滤掉一些不需要的系统对象，保留常用的
                    // isQueryable = true 代表可查询
                    if (obj.getBooleanValue("queryable")) {
                        Map<String, String> map = new HashMap<>();
                        map.put("name", obj.getString("name"));
                        map.put("label", obj.getString("label"));
                        list.add(map);
                    }
                }
            }
            // 按名称排序
            list.sort(Comparator.comparing(m -> m.get("name")));
            return list;
        });
    }

    @Override
    public List<Map<String, Object>> getSObjectFields(Long orgId, String objectName) throws Exception {
        return sfMetadataService.executeWithRetry(orgId, () -> {
            SfOrg org = sfOrgService.selectSfOrgById(orgId);
            // 使用 Describe API 获取字段详情
            String url = org.getInstanceUrl() + "/services/data/v58.0/sobjects/" + objectName + "/describe";

            String result = HttpRequest.get(url)
                    .header("Authorization", "Bearer " + org.getAccessToken())
                    .timeout(30000)
                    .execute()
                    .body();

            JSONObject json = JSON.parseObject(result);
            JSONArray fields = json.getJSONArray("fields");

            List<Map<String, Object>> fieldList = new ArrayList<>();
            if (fields != null) {
                for (int i = 0; i < fields.size(); i++) {
                    JSONObject f = fields.getJSONObject(i);
                    Map<String, Object> map = new HashMap<>();
                    map.put("name", f.getString("name"));
                    map.put("label", f.getString("label"));
                    map.put("type", f.getString("type"));
                    // 关键属性
                    map.put("createable", f.getBooleanValue("createable"));
                    map.put("updateable", f.getBooleanValue("updateable"));
                    map.put("queryable", f.getBooleanValue("queryable")); // 必须可查询
                    fieldList.add(map);
                }
            }
            return fieldList;
        });
    }
}
