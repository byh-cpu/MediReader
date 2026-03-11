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

    public String getExtractionPrompt() {
        return """
                你是一名医疗信息提取专家。请从以下诊断书或病历文本中，提取并整理关键患者信息。

                请按以下格式输出：
                - 基本信息：姓名、年龄、性别、体重（如有）
                - 主诉：患者的主要症状和就诊原因
                - 现病史：当前疾病的发展过程
                - 既往病史：既往疾病、手术史等
                - 过敏史：药物或其他过敏情况
                - 诊断结果：明确的诊断
                - 当前用药：正在使用的药物（如有）
                - 检查结果：重要的检验检查结果（如有）

                如果某项信息缺失，标注"未提供"。请确保提取准确，不要添加原文中没有的信息。
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
