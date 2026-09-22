package com.ruoyi.salesforce.service.impl;

import cn.hutool.http.HttpRequest;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.redis.RedisCache; // 确保引入 RuoYi 的 Redis 工具类
import com.ruoyi.salesforce.domain.SfOrg;
import com.ruoyi.salesforce.service.ISfAuthService;
import com.ruoyi.salesforce.service.ISfDescribeApiService;
import com.ruoyi.salesforce.service.ISfMetadataService;
import com.ruoyi.salesforce.service.ISfOrgService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class SfDescribeApiServiceImpl implements ISfDescribeApiService {

    @Autowired
    private ISfAuthService sfAuthService;

    @Autowired
    private ISfOrgService sfOrgService;

    @Autowired
    private RedisCache redisCache; // 注入 Redis 缓存工具

    private static final long CACHE_TIME = 2;
    private static final TimeUnit CACHE_UNIT = TimeUnit.HOURS;

    @Override
    public List<Map<String, String>> getSObjectList(Long orgId) throws Exception {
        // 1. 先查 Redis
        String cacheKey = CacheConstants.CACHE_KEY_OBJS + "orgId_" + orgId;
        List<Map<String, String>> cachedList = redisCache.getCacheList(cacheKey);

        if(cachedList != null && !cachedList.isEmpty()) {
            return cachedList;
        }

        // 2. Redis 没有，查 Salesforce API
        return sfAuthService.executeWithRetry(orgId, () -> {
            SfOrg org = sfOrgService.selectSfOrgById(orgId);
            String url = org.getInstanceUrl() + "/services/data/v58.0/sobjects/";

            String result = HttpRequest.get(url)
                    .header("Authorization", "Bearer " + org.getAccessToken())
                    .timeout(30000)
                    .execute()
                    .body();

            JSONObject json = JSON.parseObject(result);
            JSONArray sobjects = json.getJSONArray("sobjects");

            List<Map<String, String>> list = new ArrayList<>();
            if(sobjects != null) {
                for(int i = 0; i < sobjects.size(); i++) {
                    JSONObject obj = sobjects.getJSONObject(i);
                    Map<String, String> map = new HashMap<>();
                    map.put("name", obj.getString("name"));
                    map.put("label", obj.getString("label"));

                    // 过滤掉不可见/废弃的对象，减少缓存体积
                    if(!obj.getBooleanValue("deprecatedAndHidden")) {
                        list.add(map);
                    }
                }
            }

            // 3. 写入 Redis (设置过期时间)
            if(!list.isEmpty()) {
                redisCache.setCacheList(cacheKey, list);
                redisCache.expire(cacheKey, CACHE_TIME, CACHE_UNIT);
            }

            return list;
        });
    }

    @Override
    public List<Map<String, Object>> getSObjectFields(Long orgId, String objectName) throws Exception {
        // 1. 先查 Redis
        String cacheKey = CacheConstants.CACHE_KEY_FIELDS + "orgId_" + orgId + ":" + objectName;
        List<Map<String, Object>> cachedFields = redisCache.getCacheList(cacheKey);

        if(cachedFields != null && !cachedFields.isEmpty()) {
            return cachedFields;
        }

        // 2. Redis 没有，查 Salesforce API
        return sfAuthService.executeWithRetry(orgId, () -> {
            SfOrg org = sfOrgService.selectSfOrgById(orgId);
            String url = org.getInstanceUrl() + "/services/data/v58.0/sobjects/" + objectName + "/describe";

            String result = HttpRequest.get(url)
                    .header("Authorization", "Bearer " + org.getAccessToken())
                    .timeout(30000)
                    .execute()
                    .body();

            JSONObject json = JSON.parseObject(result);
            JSONArray fields = json.getJSONArray("fields");

            List<Map<String, Object>> fieldList = new ArrayList<>();
            if(fields != null) {
                for(int i = 0; i < fields.size(); i++) {
                    JSONObject f = fields.getJSONObject(i);
                    Map<String, Object> map = new HashMap<>();
                    map.put("name", f.getString("name"));
                    map.put("label", f.getString("label"));
                    map.put("type", f.getString("type"));

                    // 【提取自定义标识与公式属性】
                    map.put("custom", f.getBooleanValue("custom")); // 是否是自定义字段
                    map.put("calculated", f.getBooleanValue("calculated")); // 是否是公式字段
                    map.put("calculatedFormula", f.getString("calculatedFormula")); // 具体的公式逻辑内容

                    // 关联关系
                    map.put("relationshipName", f.getString("relationshipName"));
                    JSONArray referenceTo = f.getJSONArray("referenceTo");
                    if(referenceTo != null && !referenceTo.isEmpty()) {
                        map.put("referenceTo", referenceTo.toList(String.class));
                    }

                    // 权限
                    map.put("createable", f.getBooleanValue("createable"));
                    map.put("updateable", f.getBooleanValue("updateable"));
                    map.put("queryable", f.getBooleanValue("queryable"));

                    fieldList.add(map);
                }
            }

            // 3. 写入 Redis
            if(!fieldList.isEmpty()) {
                redisCache.setCacheList(cacheKey, fieldList);
                redisCache.expire(cacheKey, CACHE_TIME, CACHE_UNIT);
            }

            return fieldList;
        });
    }
}
