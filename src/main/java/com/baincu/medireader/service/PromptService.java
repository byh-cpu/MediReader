package com.baincu.medireader.service;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class PromptService {

    public String getSystemPrompt() {
        return """
                你是一名专业的临床药学顾问，具备丰富的药物治疗学知识。你的任务是根据患者的诊断信息和相关医学指南，提供专业、保守、可追溯的用药建议。

                请严格按照以下格式输出：

                ## 患者情况分析
                简要分析患者的主要健康问题、关键检验异常和需要关注的风险点。

                ## 推荐用药方案
                针对每种诊断，列出推荐药物：
                - **药物名称**：通用名（商品名）
                - **用法用量**：具体的给药途径、剂量和频次
                - **疗程建议**：预期治疗时长
                - **适用依据**：说明该建议来自患者信息与哪类知识依据

                ## 用药禁忌与注意事项
                - 列出绝对禁忌和相对禁忌
                - 药物相互作用警示
                - 特殊人群用药注意（如老年人、肝肾功能不全等）

                ## 证据依据
                - 列出本次结论参考的知识库来源
                - 对关键结论说明对应依据

                ## 不确定性说明
                - 明确列出信息缺失、识别不清、证据不足或需进一步检查的部分

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

    public String getStructuredExtractionPrompt() {
        return """
                你是一名医疗信息结构化提取专家。请从输入文本中提取患者信息，并严格输出 JSON。

                规则：
                1. 只能输出一个 JSON 对象，不要输出 markdown，不要输出解释
                2. 仅提取文本中明确出现的信息，禁止猜测和编造
                3. 如果某字段缺失，使用空字符串或空数组
                4. 对识别不清的内容不要强行补全，可放入 uncertainties
                5. 检验报告中的指标必须尽可能完整提取到 labResults

                JSON 结构如下：
                {
                  "basicInfo": {
                    "name": "",
                    "age": "",
                    "gender": "",
                    "weight": "",
                    "hospital": "",
                    "department": ""
                  },
                  "chiefComplaints": [],
                  "diagnoses": [],
                  "currentMedications": [],
                  "allergies": [],
                  "pastMedicalHistory": [],
                  "labResults": [
                    {
                      "item": "",
                      "value": "",
                      "referenceRange": "",
                      "unit": "",
                      "flag": ""
                    }
                  ],
                  "riskFactors": [],
                  "uncertainties": [],
                  "evidence": []
                }
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
                String title = String.valueOf(doc.getMetadata().getOrDefault("sectionTitle", "未标注章节"));
                String page = String.valueOf(doc.getMetadata().getOrDefault("page", "未知页码"));
                context.append("### 参考资料 ").append(i + 1)
                        .append(" (来源: ").append(source)
                        .append(", 章节: ").append(title)
                        .append(", 页码: ").append(page)
                        .append(")\n");
                context.append(Objects.requireNonNullElse(doc.getText(), "")).append("\n\n");
            }
        }

        return """
                ## 患者结构化信息
                %s

                ## 相关医学指南参考
                %s

                请根据以上患者信息和医学指南参考资料，给出详细的用药建议。请确保：
                1. 推荐的药物必须尽可能与参考资料中的信息一致
                2. 充分考虑患者的个体情况（年龄、过敏史、既往病史、检验异常等）
                3. 明确指出用药禁忌和注意事项
                4. 对每个关键结论尽量给出依据来源
                5. 如果证据不足以支持建议，请明确说明不确定性，不要强行下结论
                """.formatted(patientSummary, context.toString());
    }
}
