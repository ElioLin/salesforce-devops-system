package com.ruoyi.salesforce.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sf_deployment_history_detail")
public class SfDeploymentHistoryDetail {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long historyId;         // 关联的历史记录ID
    private String metadataType;    // 元数据类型 (ApexClass)
    private String memberName;      // 元数据名称 (MyController)

    // 关键字段：记录本次操作对目标环境的影响
    // CREATE: 目标环境原本没有，本次新增 (回滚时需删除)
    // UPDATE: 目标环境已有，本次覆盖 (回滚时需还原)
    // UNCHANGED: 内容一致，无变更
    private String action;

    private String diffContent;     // (可选) 存储具体的 Diff 文本，用于后续 Git 风格查看

    /** 租户归属ID (SaaS公司隔离) */
    private String tenantId;
}
