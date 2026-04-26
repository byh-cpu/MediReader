package com.baincu.medireader.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientStructuredInfo {

    @Builder.Default
    private BasicInfo basicInfo = new BasicInfo();

    @Builder.Default
    private List<String> chiefComplaints = new ArrayList<>();

    @Builder.Default
    private List<String> diagnoses = new ArrayList<>();

    @Builder.Default
    private List<String> currentMedications = new ArrayList<>();

    @Builder.Default
    private List<String> allergies = new ArrayList<>();

    @Builder.Default
    private List<String> pastMedicalHistory = new ArrayList<>();

    @Builder.Default
    private List<LabResult> labResults = new ArrayList<>();

    @Builder.Default
    private List<String> riskFactors = new ArrayList<>();

    @Builder.Default
    private List<String> uncertainties = new ArrayList<>();

    @Builder.Default
    private List<String> evidence = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BasicInfo {
        private String name;
        private String age;
        private String gender;
        private String weight;
        private String hospital;
        private String department;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LabResult {
        private String item;
        private String value;
        private String referenceRange;
        private String unit;
        private String flag;
    }
}
