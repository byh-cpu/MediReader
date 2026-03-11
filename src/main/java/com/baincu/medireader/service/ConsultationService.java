package com.baincu.medireader.service;

import com.baincu.medireader.model.dto.WorkflowStepEvent;
import com.baincu.medireader.model.enums.WorkflowStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ConsultationService {

    private final PdfParserService pdfParserService;
    private final SimpleVectorStore vectorStore;
    private final ChatClient.Builder chatClientBuilder;
    private final PromptService promptService;

    public ConsultationService(PdfParserService pdfParserService,
                               SimpleVectorStore vectorStore,
                               ChatClient.Builder chatClientBuilder,
                               PromptService promptService) {
        this.pdfParserService = pdfParserService;
        this.vectorStore = vectorStore;
        this.chatClientBuilder = chatClientBuilder;
        this.promptService = promptService;
    }

    public void analyzePatientDiagnosis(byte[] pdfBytes, String fileName, SseEmitter emitter) {
        try {
            // Step 1: Parse PDF
            sendEvent(emitter, WorkflowStepEvent.builder()
                    .step(WorkflowStep.PDF_PARSING)
                    .status("running")
                    .message("正在解析诊断书: " + fileName)
                    .build());

            Resource resource = new ByteArrayResource(pdfBytes) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            };
            String diagnosisText = pdfParserService.extractFullText(resource);

            sendEvent(emitter, WorkflowStepEvent.builder()
                    .step(WorkflowStep.PDF_PARSING)
                    .status("done")
                    .message("诊断书解析完成")
                    .data(Map.of(
                            "textLength", diagnosisText.length(),
                            "preview", truncate(diagnosisText, 300)
                    ))
                    .build());

            // Step 2: Extract patient info via LLM
            sendEvent(emitter, WorkflowStepEvent.builder()
                    .step(WorkflowStep.INFO_EXTRACTION)
                    .status("running")
                    .message("正在提取患者关键信息...")
                    .build());

            String patientSummary = extractPatientInfo(diagnosisText);

            sendEvent(emitter, WorkflowStepEvent.builder()
                    .step(WorkflowStep.INFO_EXTRACTION)
                    .status("done")
                    .message("患者信息提取完成")
                    .data(Map.of("patientSummary", patientSummary))
                    .build());

            // Step 3: RAG Retrieval
            sendEvent(emitter, WorkflowStepEvent.builder()
                    .step(WorkflowStep.RAG_RETRIEVAL)
                    .status("running")
                    .message("正在检索相关医学知识...")
                    .build());

            List<Document> relevantDocs = searchRelevantDocuments(patientSummary);
            List<Map<String, String>> sources = relevantDocs.stream()
                    .map(doc -> Map.of(
                            "content", truncate(doc.getText() != null ? doc.getText() : "", 200),
                            "source", String.valueOf(doc.getMetadata().getOrDefault("source", "未知"))
                    ))
                    .toList();

            sendEvent(emitter, WorkflowStepEvent.builder()
                    .step(WorkflowStep.RAG_RETRIEVAL)
                    .status("done")
                    .message("检索到 " + relevantDocs.size() + " 条相关医学知识")
                    .data(Map.of("sources", sources, "count", relevantDocs.size()))
                    .build());

            // Step 4: LLM Streaming Analysis
            sendEvent(emitter, WorkflowStepEvent.builder()
                    .step(WorkflowStep.LLM_ANALYSIS)
                    .status("running")
                    .message("正在生成用药建议...")
                    .build());

            streamMedicationAnalysis(patientSummary, relevantDocs, emitter);

        } catch (Exception e) {
            log.error("Consultation analysis failed", e);
            sendEvent(emitter, WorkflowStepEvent.builder()
                    .step(WorkflowStep.LLM_ANALYSIS)
                    .status("error")
                    .message("分析失败: " + e.getMessage())
                    .build());
            emitter.completeWithError(e);
        }
    }

    private String extractPatientInfo(String diagnosisText) {
        ChatClient chatClient = chatClientBuilder.build();
        return chatClient.prompt()
                .system(promptService.getExtractionPrompt())
                .user(diagnosisText)
                .call()
                .content();
    }

    private List<Document> searchRelevantDocuments(String patientSummary) {
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(patientSummary)
                    .topK(5)
                    .similarityThreshold(0.3)
                    .build();
            List<Document> results = vectorStore.similaritySearch(request);
            return results != null ? results : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Vector search returned no results or failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void streamMedicationAnalysis(String patientSummary, List<Document> relevantDocs, SseEmitter emitter) {
        ChatClient chatClient = chatClientBuilder.build();
        String systemPrompt = promptService.getSystemPrompt();
        String userPrompt = promptService.buildAnalysisPrompt(patientSummary, relevantDocs);

        chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .stream()
                .content()
                .doOnNext(token -> sendEvent(emitter, WorkflowStepEvent.builder()
                        .step(WorkflowStep.LLM_ANALYSIS)
                        .status("streaming")
                        .token(token)
                        .build()))
                .doOnError(error -> {
                    log.error("LLM streaming error", error);
                    sendEvent(emitter, WorkflowStepEvent.builder()
                            .step(WorkflowStep.LLM_ANALYSIS)
                            .status("error")
                            .message("生成失败: " + error.getMessage())
                            .build());
                    emitter.completeWithError(error);
                })
                .doOnComplete(() -> {
                    sendEvent(emitter, WorkflowStepEvent.builder()
                            .step(WorkflowStep.LLM_ANALYSIS)
                            .status("done")
                            .message("分析完成")
                            .build());
                    emitter.complete();
                })
                .blockLast();
    }

    private void sendEvent(SseEmitter emitter, WorkflowStepEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name("workflow")
                    .data(event, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            log.warn("Failed to send SSE event: {}", e.getMessage());
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
