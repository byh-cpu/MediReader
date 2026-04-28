package com.baincu.medireader.service;

import com.baincu.medireader.model.dto.PatientStructuredInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

@Service
@Slf4j
public class PatientStructuredExtractionService {

    private final ObjectMapper objectMapper;
    private final PatientInfoValidationService validationService;

    public PatientStructuredExtractionService(PatientInfoValidationService validationService) {
        this.validationService = validationService;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public PatientStructuredInfo parseAndValidate(String jsonText, String originalText) {
        PatientStructuredInfo parsed = tryParseJson(jsonText);
        if (parsed == null) {
            parsed = fallbackFromPlainText(originalText);
            parsed.getUncertainties().add("结构化提取结果解析失败，已使用降级规则抽取");
        }
        return validationService.normalize(parsed);
    }

    private PatientStructuredInfo tryParseJson(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        String json = trimmed.substring(start, end + 1);
        try {
            JsonNode root = objectMapper.readTree(json);
            return parseFlexiblePatientInfo(root);
        } catch (Exception e) {
            log.warn("Failed to parse structured extraction JSON: {}", e.getMessage());
            return null;
        }
    }

    private PatientStructuredInfo parseFlexiblePatientInfo(JsonNode root) {
        PatientStructuredInfo info = PatientStructuredInfo.builder().build();
        JsonNode basic = root.path("basicInfo");
        if (basic.isObject()) {
            info.getBasicInfo().setName(textOf(basic.get("name")));
            info.getBasicInfo().setAge(textOf(basic.get("age")));
            info.getBasicInfo().setGender(textOf(basic.get("gender")));
            info.getBasicInfo().setWeight(textOf(basic.get("weight")));
            info.getBasicInfo().setHospital(textOf(basic.get("hospital")));
            info.getBasicInfo().setDepartment(textOf(basic.get("department")));
        }
        info.setChiefComplaints(stringListOf(root.get("chiefComplaints")));
        info.setDiagnoses(stringListOf(root.get("diagnoses")));
        info.setCurrentMedications(stringListOf(root.get("currentMedications")));
        info.setAllergies(stringListOf(root.get("allergies")));
        info.setPastMedicalHistory(stringListOf(root.get("pastMedicalHistory")));
        info.setRiskFactors(stringListOf(root.get("riskFactors")));
        info.setUncertainties(stringListOf(root.get("uncertainties")));
        info.setEvidence(stringListOf(root.get("evidence")));
        info.setLabResults(labResultsOf(root.get("labResults")));
        return info;
    }

    private List<String> stringListOf(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return values;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = textOf(item);
                if (StringUtils.hasText(value)) {
                    values.add(value);
                }
            }
            return values;
        }
        String value = textOf(node);
        if (StringUtils.hasText(value)) {
            values.add(value);
        }
        return values;
    }

    private List<PatientStructuredInfo.LabResult> labResultsOf(JsonNode node) {
        List<PatientStructuredInfo.LabResult> labs = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return labs;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                PatientStructuredInfo.LabResult lab = labOf(item);
                if (lab != null) {
                    labs.add(lab);
                }
            }
            return labs;
        }
        PatientStructuredInfo.LabResult lab = labOf(node);
        if (lab != null) {
            labs.add(lab);
        }
        return labs;
    }

    private PatientStructuredInfo.LabResult labOf(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isObject()) {
            String value = textOf(node);
            return StringUtils.hasText(value) ? new PatientStructuredInfo.LabResult(value, "", "", "", "") : null;
        }
        return new PatientStructuredInfo.LabResult(
                firstText(node, "item", "name", "项目", "指标"),
                firstText(node, "value", "result", "结果", "数值"),
                firstText(node, "referenceRange", "reference", "range", "参考范围"),
                firstText(node, "unit", "单位"),
                firstText(node, "flag", "status", "异常标记", "标记")
        );
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            String value = textOf(node.get(name));
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String textOf(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isValueNode()) {
            return node.asText("").trim();
        }
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : node) {
                String value = textOf(item);
                if (StringUtils.hasText(value)) {
                    parts.add(value);
                }
            }
            return String.join("；", parts);
        }
        if (node.isObject()) {
            StringJoiner joiner = new StringJoiner("，");
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String value = textOf(field.getValue());
                if (StringUtils.hasText(value)) {
                    joiner.add(field.getKey() + ": " + value);
                }
            }
            return joiner.toString();
        }
        return "";
    }

    private PatientStructuredInfo fallbackFromPlainText(String originalText) {
        PatientStructuredInfo info = PatientStructuredInfo.builder().build();
        if (!StringUtils.hasText(originalText)) {
            return info;
        }
        String compact = originalText.replaceAll("\\s+", " ");
        setBasicInfoFromText(info, compact);
        addSectionValue(info.getChiefComplaints(), compact, "主诉", "现病史", "既往史", "体格检查", "辅助检查", "诊断", "处理");
        addSectionValue(info.getPastMedicalHistory(), compact, "既往史", "体格检查", "辅助检查", "诊断", "处理");
        addSectionValue(info.getDiagnoses(), compact, "诊断", "处理", "病情变化及处置", "注意事项", "医生签名");
        addSectionValue(info.getCurrentMedications(), compact, "处理", "病情变化及处置", "注意事项", "医生签名");
        addSectionValue(info.getRiskFactors(), compact, "现病史", "既往史", "体格检查", "辅助检查", "诊断");
        return info;
    }

    private void setBasicInfoFromText(PatientStructuredInfo info, String compact) {
        String name = extractBetween(compact, "姓名", "性别", "年龄", "登记号", "就诊时间");
        if (StringUtils.hasText(name)) {
            info.getBasicInfo().setName(cleanLabelValue(name));
        }
        if (compact.contains("男")) {
            info.getBasicInfo().setGender("男");
        } else if (compact.contains("女")) {
            info.getBasicInfo().setGender("女");
        }
        java.util.regex.Matcher ageMatcher = java.util.regex.Pattern.compile("(\\d{1,3})\\s*岁").matcher(compact);
        if (ageMatcher.find()) {
            info.getBasicInfo().setAge(ageMatcher.group(1) + "岁");
        }
        String hospital = extractHospital(compact);
        if (StringUtils.hasText(hospital)) {
            info.getBasicInfo().setHospital(hospital);
        }
        String department = extractBetween(compact, "科室", "主诉", "现病史", "诊断");
        if (StringUtils.hasText(department)) {
            info.getBasicInfo().setDepartment(cleanLabelValue(department));
        }
    }

    private void addSectionValue(List<String> target, String text, String startLabel, String... endLabels) {
        String value = extractBetween(text, startLabel, endLabels);
        value = cleanLabelValue(value);
        if (StringUtils.hasText(value)) {
            target.add(value);
        }
    }

    private String extractHospital(String text) {
        int idx = text.indexOf("医院");
        if (idx < 0) {
            return "";
        }
        int start = Math.max(0, idx - 20);
        return cleanLabelValue(text.substring(start, idx + 2));
    }

    private String extractBetween(String text, String startLabel, String... endLabels) {
        int start = text.indexOf(startLabel);
        if (start < 0) {
            return "";
        }
        start += startLabel.length();
        int end = text.length();
        for (String label : endLabels) {
            int idx = text.indexOf(label, start);
            if (idx > start && idx < end) {
                end = idx;
            }
        }
        return text.substring(start, end);
    }

    private String cleanLabelValue(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String cleaned = value.trim();
        while (cleaned.startsWith(":") || cleaned.startsWith("：") || cleaned.startsWith(";") || cleaned.startsWith("；")) {
            cleaned = cleaned.substring(1).trim();
        }
        return cleaned;
    }
}
