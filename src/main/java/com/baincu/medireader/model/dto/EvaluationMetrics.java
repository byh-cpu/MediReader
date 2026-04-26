package com.baincu.medireader.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationMetrics {
    private int inputFileCount;
    private int parsedTextLength;
    private int retrievedDocumentCount;
    private int extractedDiagnosisCount;
    private int extractedLabResultCount;
    private int uncertaintyCount;
    private long parseCostMs;
    private long extractCostMs;
    private long retrievalCostMs;
}
