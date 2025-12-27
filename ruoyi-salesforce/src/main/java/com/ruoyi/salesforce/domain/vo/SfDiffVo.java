package com.ruoyi.salesforce.domain.vo;

import java.io.Serializable;

public class SfDiffVo implements Serializable {
    private String sourceContent; // 源环境代码 (通常是 Dev)
    private String targetContent; // 目标环境代码 (通常是 Prod/UAT)

    public SfDiffVo(String sourceContent, String targetContent) {
        this.sourceContent = sourceContent;
        this.targetContent = targetContent;
    }

    // Getter & Setter
    public String getSourceContent() {
        return sourceContent;
    }

    public void setSourceContent(String sourceContent) {
        this.sourceContent = sourceContent;
    }

    public String getTargetContent() {
        return targetContent;
    }

    public void setTargetContent(String targetContent) {
        this.targetContent = targetContent;
    }
}
