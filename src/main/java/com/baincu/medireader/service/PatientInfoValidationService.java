package com.baincu.medireader.service;

import com.baincu.medireader.model.dto.PatientStructuredInfo;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PatientInfoValidationService {

    private static final Pattern AGE_PATTERN = Pattern.compile("(\\d{1,3})岁");
    private static final List<String> HALLUCINATION_TERMS = List.of("肾虚", "气血不足", "托起按医治疗", "考虑用药", "建议进一步检查");

    public PatientStructuredInfo normalize(PatientStructuredInfo info) {
        if (info == null) {
            return PatientStructuredInfo.builder().build();
        }

        normalizeBasicInfo(info);
        info.setChiefComplaints(distinctValid(info.getChiefComplaints()));
        info.setDiagnoses(distinctValid(filterHallucinations(info.getDiagnoses(), info)));
        info.setCurrentMedications(distinctValid(filterHallucinations(info.getCurrentMedications(), info)));
        info.setAllergies(distinctValid(info.getAllergies()));
        info.setPastMedicalHistory(distinctValid(info.getPastMedicalHistory()));
        info.setRiskFactors(distinctValid(info.getRiskFactors()));
        info.setEvidence(distinctValid(info.getEvidence()));
        info.setUncertainties(distinctValid(info.getUncertainties()));
        info.setLabResults(normalizeLabResults(info.getLabResults()));
        return info;
    }

    private void normalizeBasicInfo(PatientStructuredInfo info) {
        PatientStructuredInfo.BasicInfo basic = info.getBasicInfo();
        if (basic == null) {
            basic = new PatientStructuredInfo.BasicInfo();
            info.setBasicInfo(basic);
        }
        basic.setName(cleanValue(basic.getName()));
        basic.setAge(normalizeAge(basic.getAge(), info));
        basic.setGender(normalizeGender(basic.getGender(), info));
        basic.setWeight(cleanValue(basic.getWeight()));
        basic.setHospital(cleanValue(basic.getHospital()));
        basic.setDepartment(cleanValue(basic.getDepartment()));
    }

    private String normalizeAge(String age, PatientStructuredInfo info) {
        String cleaned = cleanValue(age);
        if (!StringUtils.hasText(cleaned)) {
            return null;
        }
        Matcher matcher = AGE_PATTERN.matcher(cleaned);
        if (matcher.find()) {
            int years = Integer.parseInt(matcher.group(1));
            if (years >= 0 && years <= 120) {
                return years + "岁";
            }
        }
        info.getUncertainties().add("年龄字段格式异常，已保留原文：" + cleaned);
        return cleaned;
    }

    private String normalizeGender(String gender, PatientStructuredInfo info) {
        String cleaned = cleanValue(gender);
        if (!StringUtils.hasText(cleaned)) {
            return null;
        }
        if (cleaned.contains("男")) return "男";
        if (cleaned.contains("女")) return "女";
        info.getUncertainties().add("性别字段无法标准化：" + cleaned);
        return cleaned;
    }

    private List<PatientStructuredInfo.LabResult> normalizeLabResults(List<PatientStructuredInfo.LabResult> labResults) {
        if (labResults == null || labResults.isEmpty()) {
            return new ArrayList<>();
        }
        List<PatientStructuredInfo.LabResult> normalized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PatientStructuredInfo.LabResult lab : labResults) {
            if (lab == null || !StringUtils.hasText(lab.getItem()) || !StringUtils.hasText(lab.getValue())) {
                continue;
            }
            String key = (lab.getItem() + "|" + lab.getValue() + "|" + cleanValue(lab.getUnit())).toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                continue;
            }
            lab.setItem(cleanValue(lab.getItem()));
            lab.setValue(cleanValue(lab.getValue()));
            lab.setReferenceRange(cleanValue(lab.getReferenceRange()));
            lab.setUnit(cleanValue(lab.getUnit()));
            lab.setFlag(normalizeFlag(lab.getFlag()));
            normalized.add(lab);
        }
        return normalized;
    }

    private String normalizeFlag(String flag) {
        String cleaned = cleanValue(flag);
        if (!StringUtils.hasText(cleaned)) {
            return null;
        }
        if (cleaned.contains("高") || cleaned.contains("↑")) return "↑";
        if (cleaned.contains("低") || cleaned.contains("↓")) return "↓";
        return cleaned;
    }

    private List<String> filterHallucinations(List<String> values, PatientStructuredInfo info) {
        List<String> filtered = new ArrayList<>();
        for (String value : values == null ? List.<String>of() : values) {
            String cleaned = cleanValue(value);
            if (!StringUtils.hasText(cleaned)) {
                continue;
            }
            boolean hallucinated = HALLUCINATION_TERMS.stream().anyMatch(cleaned::contains);
            if (hallucinated) {
                info.getUncertainties().add("已过滤疑似幻觉字段：" + cleaned);
                continue;
            }
            filtered.add(cleaned);
        }
        return filtered;
    }

    private List<String> distinctValid(List<String> values) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String cleaned = cleanValue(value);
            if (StringUtils.hasText(cleaned) && !"未提供".equals(cleaned)) {
                unique.add(cleaned);
            }
        }
        return new ArrayList<>(unique);
    }

    private String cleanValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String cleaned = value.trim();
        cleaned = cleaned.replace("[难以辨认]", "").replace("[不确定]", "").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
