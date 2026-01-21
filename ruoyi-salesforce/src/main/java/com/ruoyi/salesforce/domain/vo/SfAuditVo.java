package com.ruoyi.salesforce.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

@Data
public class SfAuditVo {
    private Long historyId;
    private Long deploymentId;
    private String type;            // 操作类型 (Deploy, Quick...)
    private String status;          // 结果 (Succeeded, Failed)
    private String createBy;        // 操作人

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;

    // 明细信息
    private String metadataType;
    private String memberName;
    private String action;
    private String diffContent;     // 核心差异文本
    private String backupPath;      // 用于判断是否显示"预览备份"按钮

    /** 部署包标题 (新增) */
    private String deploymentTitle;
}
