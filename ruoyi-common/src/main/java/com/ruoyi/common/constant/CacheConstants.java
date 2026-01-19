package com.ruoyi.common.constant;

/**
 * 缓存的key 常量
 *
 * @author ruoyi
 */
public class CacheConstants
{
    /**
     * 登录用户 redis key
     */
    public static final String LOGIN_TOKEN_KEY = "login_tokens:";

    /**
     * 验证码 redis key
     */
    public static final String CAPTCHA_CODE_KEY = "captcha_codes:";

    /**
     * 参数管理 cache key
     */
    public static final String SYS_CONFIG_KEY = "sys_config:";

    /**
     * 字典管理 cache key
     */
    public static final String SYS_DICT_KEY = "sys_dict:";

    /**
     * 防重提交 redis key
     */
    public static final String REPEAT_SUBMIT_KEY = "repeat_submit:";

    /**
     * 限流 redis key
     */
    public static final String RATE_LIMIT_KEY = "rate_limit:";

    /**
     * 登录账户密码错误次数 redis key
     */
    public static final String PWD_ERR_CNT_KEY = "pwd_err_cnt:";

    /**
     * 缓存Salesforce的对象
     */
    public static final String CACHE_KEY_OBJS = "sf_meta_objs:";

    /**
     * 缓存Salesforce的字段
     */
    public static final String CACHE_KEY_FIELDS = "sf_meta_fields:";

    /**
     * 缓存Salesforce元数据内容
     */
    public static final String REDIS_CONTENT_KEY_PREFIX = "sf_content:";

    /**
     * 缓存Salesforce元数据
     */
    public static final String REDIS_META_KEY_PREFIX = "sf_meta_v1";
}
