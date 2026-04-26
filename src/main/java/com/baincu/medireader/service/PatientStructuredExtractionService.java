package com.baincu.medireader.service;

import com.baincu.medireader.model.dto.PatientStructuredInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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
            return objectMapper.readValue(json, PatientStructuredInfo.class);
        } catch (Exception e) {
            log.warn("Failed to parse structured extraction JSON: {}", e.getMessage());
            return null;
        }
    }

    private PatientStructuredInfo fallbackFromPlainText(String originalText) {
        PatientStructuredInfo info = PatientStructuredInfo.builder().build();
        if (!StringUtils.hasText(originalText)) {
            return info;
        }
        String compact = originalText.replaceAll("\\s+", " ");
        if (compact.contains("男")) {
            info.getBasicInfo().setGender("男");
        } else if (compact.contains("女")) {
            info.getBasicInfo().setGender("女");
        }
        java.util.regex.Matcher ageMatcher = java.util.regex.Pattern.compile("(\\d{1,3})岁").matcher(compact);
        if (ageMatcher.find()) {
            info.getBasicInfo().setAge(ageMatcher.group(1) + "岁");
        }
        return info;
    }
}
