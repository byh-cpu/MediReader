package com.baincu.medireader.model.enums;

import lombok.Getter;

@Getter
public enum WorkflowStep {

    PDF_PARSING("文档解析", "解析上传的PDF文档"),
    INFO_EXTRACTION("信息提取", "提取患者关键信息"),
    RAG_RETRIEVAL("知识检索", "检索相关医学知识"),
    LLM_ANALYSIS("用药分析", "生成用药建议");

    private final String displayName;
    private final String description;

    WorkflowStep(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
}
