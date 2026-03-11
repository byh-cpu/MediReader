package com.baincu.medireader.service;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class PromptService {

    public String getSystemPrompt() {
        return """
                你是一名专业的临床药学顾问，具备丰富的药物治疗学知识。你的任务是根据患者的诊断信息和相关医学指南，提供专业的用药建议。

                请严格按照以下格式输出：

                ## 患者情况分析
                简要分析患者的主要健康问题和需要关注的要点。

                ## 推荐用药方案
                针对每种诊断，列出推荐药物：
                - **药物名称**：通用名（商品名）
                - **用法用量**：具体的给药途径、剂量和频次
                - **疗程建议**：预期治疗时长
                - **预期效果**：该药物的治疗目标

                ## 用药禁忌与注意事项
                - 根据患者具体情况（年龄、过敏史、既往病史等），列出绝对禁忌和相对禁忌
                - 药物相互作用警示
                - 特殊人群用药注意（如老年人、孕妇、肝肾功能不全等）

                ## 监测建议
                需要监测的指标和随访建议。

                **免责声明：以上建议仅供参考，不能替代专业医师的诊疗意见。实际用药请遵循主治医师的处方。**
                """;
    }

    public String getImageExtractionPrompt() {
        return """
                你是一名专业的医疗文档识别专家，擅长识别印刷体和手写体中文医疗文档。

                重要规则：
                1. 完整提取图片中所有文字，包括表格中每一行每一列的数据
                2. 对于表格，按行提取：序号、代号、名称、结果、参考范围、单位
                3. 只输出图片中实际存在的文字，禁止编造
                4. 手写文字无法辨认时标注[难以辨认]
                5. 输出完所有内容后立即停止，不要重复
                6. 不要添加解释或评论
                """;
    }

    public String getExtractionPrompt() {
        return """
                你是一名医疗信息提取专家。请从以下文本中提取并整理关键患者信息。

                注意：输入可能包含OCR识别结果和视觉模型识别结果，两者可能有差异。
                请综合分析两种识别结果，取最合理的内容。对于标注[难以辨认]或[不确定]的内容，如实标注。

                请按以下格式输出：
                - 基本信息：姓名、年龄、性别、科室、医院名称（如有）
                - 主诉/诊断：患者的主要症状、就诊原因或诊断结果
                - 既往病史/过敏史：既往疾病、过敏情况（如有）
                - 当前用药/处理建议：正在使用的药物或医生建议（如有）
                - 检查检验结果：完整列出所有检查指标及其数值、参考范围和单位。
                  对于检验报告，请逐项列出，格式如：指标名称 结果值 参考范围 单位，并标注异常项（高于或低于参考范围的用↑或↓标记）

                重要：如果文本包含检验报告（如血常规、生化等），必须完整提取每一项检查指标的数据，不要遗漏。
                如果某项信息缺失，标注"未提供"。只提取文本中实际存在的信息，不要推测或编造。
                """;
    }

    public String buildAnalysisPrompt(String patientSummary, List<Document> relevantDocs) {
        StringBuilder context = new StringBuilder();
        if (relevantDocs.isEmpty()) {
            context.append("（知识库中暂无相关参考资料，请基于你的医学知识进行分析，并明确标注这一点）\n");
        } else {
            for (int i = 0; i < relevantDocs.size(); i++) {
                Document doc = relevantDocs.get(i);
                String source = String.valueOf(doc.getMetadata().getOrDefault("source", "未知来源"));
                context.append("### 参考资料 ").append(i + 1)
                        .append(" (来源: ").append(source).append(")\n");
                context.append(Objects.requireNonNullElse(doc.getText(), "")).append("\n\n");
            }
        }

        return """
                ## 患者诊断信息
                %s

                ## 相关医学指南参考
                %s

                请根据以上患者信息和医学指南参考资料，给出详细的用药建议。请确保：
                1. 推荐的药物必须与参考资料中的信息一致
                2. 充分考虑患者的个体情况（年龄、过敏史、既往病史等）
                3. 明确指出用药禁忌和注意事项
                4. 如果参考资料不足以支持建议，请明确说明
                """.formatted(patientSummary, context.toString());
    }
}
