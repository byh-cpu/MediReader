package com.baincu.medireader.service;

import com.baincu.medireader.model.dto.EvaluationMetrics;
import com.baincu.medireader.model.dto.PatientStructuredInfo;
import com.baincu.medireader.model.dto.WorkflowStepEvent;
import com.baincu.medireader.model.enums.WorkflowStep;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class ConsultationService {

    private final PdfParserService pdfParserService;
    private final SimpleVectorStore vectorStore;
    private final ChatClient.Builder chatClientBuilder;
    private final PromptService promptService;
    private final OcrService ocrService;
    private final PatientStructuredExtractionService structuredExtractionService;
    private final ObjectMapper objectMapper;

    @Value("${medireader.vision-model:minicpm-v}")
    private String visionModel;

    public ConsultationService(PdfParserService pdfParserService,
                               SimpleVectorStore vectorStore,
                               ChatClient.Builder chatClientBuilder,
                               PromptService promptService,
                               OcrService ocrService,
                               PatientStructuredExtractionService structuredExtractionService) {
        this.pdfParserService = pdfParserService;
        this.vectorStore = vectorStore;
        this.chatClientBuilder = chatClientBuilder;
        this.promptService = promptService;
        this.ocrService = ocrService;
        this.structuredExtractionService = structuredExtractionService;
        this.objectMapper = new ObjectMapper();
    }

    public record UploadedFile(byte[] data, String fileName, String type, String contentType) {}

    public void analyzePatientDiagnosis(List<UploadedFile> files, SseEmitter emitter) {
        AtomicBoolean emitterDead = new AtomicBoolean(false);
        emitter.onTimeout(() -> emitterDead.set(true));
        emitter.onError(e -> emitterDead.set(true));
        emitter.onCompletion(() -> emitterDead.set(true));

        long parseStart = System.currentTimeMillis();
        int textLength;

        try {
            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                    .step(WorkflowStep.PDF_PARSING)
                    .status("running")
                    .message("Parsing uploaded files...")
                    .build());

            List<String> textParts = new ArrayList<>();
            List<String> fileNames = new ArrayList<>();

            for (UploadedFile file : files) {
                if (emitterDead.get()) {
                    log.warn("Emitter closed, aborting workflow");
                    return;
                }
                fileNames.add(file.fileName());
                if ("pdf".equals(file.type())) {
                    Resource resource = new ByteArrayResource(file.data()) {
                        @Override
                        public String getFilename() {
                            return file.fileName();
                        }
                    };
                    String text = pdfParserService.extractFullText(resource);
                    textParts.add(text);
                    log.info("Parsed PDF: {} ({} chars)", file.fileName(), text.length());
                } else if ("image".equals(file.type())) {
                    String text = executeWithHeartbeat(
                            emitter,
                            emitterDead,
                            WorkflowStep.PDF_PARSING,
                            "Recognizing image [" + file.fileName() + "] with OCR and vision model",
                            () -> extractTextFromImage(file.data(), file.fileName())
                    );
                    textParts.add(text);
                    log.info("Recognized image: {} ({} chars)", file.fileName(), text.length());
                }
            }

            if (emitterDead.get()) {
                log.warn("Emitter closed after parsing, aborting workflow");
                return;
            }

            String diagnosisText = String.join("\n\n", textParts);
            textLength = diagnosisText.length();
            long parseCostMs = System.currentTimeMillis() - parseStart;

            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                    .step(WorkflowStep.PDF_PARSING)
                    .status("done")
                    .message("File parsing completed (" + fileNames.size() + " files)")
                    .data(Map.of(
                            "textLength", textLength,
                            "preview", truncate(diagnosisText, 300),
                            "files", fileNames,
                            "parseCostMs", parseCostMs
                    ))
                    .build());

            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                    .step(WorkflowStep.INFO_EXTRACTION)
                    .status("running")
                    .message("Extracting structured patient information...")
                    .build());

            long extractStart = System.currentTimeMillis();
            PatientStructuredInfo patientInfo = extractPatientInfo(diagnosisText);
            String patientSummary = toJson(patientInfo);
            long extractCostMs = System.currentTimeMillis() - extractStart;

            if (emitterDead.get()) {
                log.warn("Emitter closed after info extraction, aborting workflow");
                return;
            }

            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                    .step(WorkflowStep.INFO_EXTRACTION)
                    .status("done")
                    .message("Patient information extraction completed")
                    .data(Map.of(
                            "patientSummary", patientSummary,
                            "structuredInfo", patientInfo,
                            "extractCostMs", extractCostMs
                    ))
                    .build());

            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                    .step(WorkflowStep.RAG_RETRIEVAL)
                    .status("running")
                    .message("Retrieving relevant medical knowledge...")
                    .build());

            long retrievalStart = System.currentTimeMillis();
            List<Document> relevantDocs = searchRelevantDocuments(patientInfo);
            long retrievalCostMs = System.currentTimeMillis() - retrievalStart;
            List<Map<String, String>> sources = relevantDocs.stream()
                    .map(doc -> Map.of(
                            "content", truncate(doc.getText() != null ? doc.getText() : "", 200),
                            "source", String.valueOf(doc.getMetadata().getOrDefault("source", "unknown")),
                            "sectionTitle", String.valueOf(doc.getMetadata().getOrDefault("sectionTitle", "unknown section")),
                            "page", String.valueOf(doc.getMetadata().getOrDefault("page", "unknown page"))
                    ))
                    .toList();

            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                    .step(WorkflowStep.RAG_RETRIEVAL)
                    .status("done")
                    .message("Retrieved " + relevantDocs.size() + " relevant medical references")
                    .data(Map.of(
                            "sources", sources,
                            "count", relevantDocs.size(),
                            "retrievalCostMs", retrievalCostMs
                    ))
                    .build());

            if (emitterDead.get()) {
                log.warn("Emitter closed after RAG, aborting workflow");
                return;
            }

            EvaluationMetrics metrics = EvaluationMetrics.builder()
                    .inputFileCount(files.size())
                    .parsedTextLength(textLength)
                    .retrievedDocumentCount(relevantDocs.size())
                    .extractedDiagnosisCount(patientInfo.getDiagnoses().size())
                    .extractedLabResultCount(patientInfo.getLabResults().size())
                    .uncertaintyCount(patientInfo.getUncertainties().size())
                    .parseCostMs(parseCostMs)
                    .extractCostMs(extractCostMs)
                    .retrievalCostMs(retrievalCostMs)
                    .build();

            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                    .step(WorkflowStep.LLM_ANALYSIS)
                    .status("running")
                    .message("Generating medication advice...")
                    .data(Map.of("evaluationMetrics", metrics))
                    .build());

            streamMedicationAnalysis(patientSummary, relevantDocs, emitter, emitterDead);
        } catch (Exception e) {
            log.error("Consultation analysis failed", e);
            if (!emitterDead.get()) {
                sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                        .step(WorkflowStep.LLM_ANALYSIS)
                        .status("error")
                        .message("Analysis failed: " + e.getMessage())
                        .build());
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void extractPatientInformationForReview(List<UploadedFile> files, SseEmitter emitter) {
        AtomicBoolean emitterDead = new AtomicBoolean(false);
        emitter.onTimeout(() -> emitterDead.set(true));
        emitter.onError(e -> emitterDead.set(true));
        emitter.onCompletion(() -> emitterDead.set(true));

        long parseStart = System.currentTimeMillis();
        try {
            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                    .step(WorkflowStep.PDF_PARSING)
                    .status("running")
                    .message("Parsing uploaded files...")
                    .build());

            List<String> textParts = new ArrayList<>();
            List<String> fileNames = new ArrayList<>();
            for (UploadedFile file : files) {
                if (emitterDead.get()) {
                    return;
                }
                fileNames.add(file.fileName());
                if ("pdf".equals(file.type())) {
                    Resource resource = new ByteArrayResource(file.data()) {
                        @Override
                        public String getFilename() {
                            return file.fileName();
                        }
                    };
                    textParts.add(pdfParserService.extractFullText(resource));
                } else if ("image".equals(file.type())) {
                    String text = executeWithHeartbeat(
                            emitter,
                            emitterDead,
                            WorkflowStep.PDF_PARSING,
                            "Recognizing image [" + file.fileName() + "] with OCR and vision model",
                            () -> extractTextFromImage(file.data(), file.fileName())
                    );
                    textParts.add(text);
                }
            }

            String diagnosisText = String.join("\n\n", textParts);
            long parseCostMs = System.currentTimeMillis() - parseStart;
            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                    .step(WorkflowStep.PDF_PARSING)
                    .status("done")
                    .message("File parsing completed (" + fileNames.size() + " files)")
                    .data(Map.of(
                            "textLength", diagnosisText.length(),
                            "preview", truncate(diagnosisText, 300),
                            "files", fileNames,
                            "parseCostMs", parseCostMs
                    ))
                    .build());

            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                    .step(WorkflowStep.INFO_EXTRACTION)
                    .status("running")
                    .message("Extracting structured patient information...")
                    .build());

            long extractStart = System.currentTimeMillis();
            PatientStructuredInfo patientInfo = extractPatientInfo(diagnosisText);
            String patientSummary = toJson(patientInfo);
            long extractCostMs = System.currentTimeMillis() - extractStart;

            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                    .step(WorkflowStep.INFO_EXTRACTION)
                    .status("done")
                    .message("患者信息已识别，请核对并编辑后继续分析")
                    .data(Map.of(
                            "patientSummary", patientSummary,
                            "structuredInfo", patientInfo,
                            "extractCostMs", extractCostMs,
                            "awaitingReview", true
                    ))
                    .build());
            emitter.complete();
        } catch (Exception e) {
            log.error("Patient information extraction failed", e);
            if (!emitterDead.get()) {
                sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                        .step(WorkflowStep.INFO_EXTRACTION)
                        .status("error")
                        .message("信息识别失败: " + e.getMessage())
                        .build());
                emitter.completeWithError(e);
            }
        }
    }

    public void continueAnalysisWithReviewedInfo(PatientStructuredInfo patientInfo, SseEmitter emitter) {
        AtomicBoolean emitterDead = new AtomicBoolean(false);
        emitter.onTimeout(() -> emitterDead.set(true));
        emitter.onError(e -> emitterDead.set(true));
        emitter.onCompletion(() -> emitterDead.set(true));

        try {
            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                    .step(WorkflowStep.RAG_RETRIEVAL)
                    .status("running")
                    .message("Retrieving relevant medical knowledge...")
                    .build());

            long retrievalStart = System.currentTimeMillis();
            List<Document> relevantDocs = searchRelevantDocuments(patientInfo);
            long retrievalCostMs = System.currentTimeMillis() - retrievalStart;
            List<Map<String, String>> sources = relevantDocs.stream()
                    .map(doc -> Map.of(
                            "content", truncate(doc.getText() != null ? doc.getText() : "", 200),
                            "source", String.valueOf(doc.getMetadata().getOrDefault("source", "unknown")),
                            "sectionTitle", String.valueOf(doc.getMetadata().getOrDefault("sectionTitle", "unknown section")),
                            "page", String.valueOf(doc.getMetadata().getOrDefault("page", "unknown page"))
                    ))
                    .toList();

            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                    .step(WorkflowStep.RAG_RETRIEVAL)
                    .status("done")
                    .message("Retrieved " + relevantDocs.size() + " relevant medical references")
                    .data(Map.of(
                            "sources", sources,
                            "count", relevantDocs.size(),
                            "retrievalCostMs", retrievalCostMs
                    ))
                    .build());

            EvaluationMetrics metrics = EvaluationMetrics.builder()
                    .inputFileCount(0)
                    .parsedTextLength(toJson(patientInfo).length())
                    .retrievedDocumentCount(relevantDocs.size())
                    .extractedDiagnosisCount(patientInfo.getDiagnoses().size())
                    .extractedLabResultCount(patientInfo.getLabResults().size())
                    .uncertaintyCount(patientInfo.getUncertainties().size())
                    .parseCostMs(0)
                    .extractCostMs(0)
                    .retrievalCostMs(retrievalCostMs)
                    .build();

            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                    .step(WorkflowStep.LLM_ANALYSIS)
                    .status("running")
                    .message("Generating medication advice...")
                    .data(Map.of("evaluationMetrics", metrics))
                    .build());

            streamMedicationAnalysis(toJson(patientInfo), relevantDocs, emitter, emitterDead);
        } catch (Exception e) {
            log.error("Reviewed consultation analysis failed", e);
            if (!emitterDead.get()) {
                sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                        .step(WorkflowStep.LLM_ANALYSIS)
                        .status("error")
                        .message("分析失败: " + e.getMessage())
                        .build());
                emitter.completeWithError(e);
            }
        }
    }

    private String extractTextFromImage(byte[] imageData, String fileName) {
        StringBuilder combined = new StringBuilder();
        String ocrText = "";
        if (ocrService.isAvailable()) {
            log.info("Running Tesseract OCR on: {}", fileName);
            ocrText = ocrService.extractText(imageData, fileName);
            if (!ocrText.isBlank()) {
                combined.append("[OCR_RESULT]\n").append(ocrText).append("\n\n");
            }
        }

        log.info("Recognizing image with vision model [{}]: {}", visionModel, fileName);
        byte[] jpegData = convertToJpeg(imageData, fileName);
        String visionText = callVisionModel(jpegData, fileName, ocrText);
        if (!visionText.isBlank()) {
            combined.append("[VISION_MODEL_RESULT]\n").append(visionText);
        }

        String result = combined.toString().trim();
        if (result.isEmpty()) {
            throw new RuntimeException("Image recognition failed: OCR and vision model extracted no valid text");
        }
        return result;
    }

    private String callVisionModel(byte[] jpegData, String fileName, String ocrReference) {
        Resource imageResource = new ByteArrayResource(jpegData) {
            @Override
            public String getFilename() {
                return fileName.replaceAll("\\.[^.]+$", ".jpg");
            }
        };

        String userText = ocrReference.isBlank()
                ? "请完整识别这张医疗文档/医院系统截图。先按可见分区列出文字，再单独整理患者基本信息、诊断、检验检查、用药、病史等字段。不要遗漏浅色小字和表格行。"
                : "下面是本地 OCR 初步识别结果，可能有错漏。请根据图片逐项校正和补充，尤其注意浅色小字、红色诊断、黄色标记、表格数值、单位和参考范围。\n\n[OCR参考文本]\n" + truncate(ocrReference, 5000);

        int maxRetries = 2;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                ChatClient chatClient = chatClientBuilder.build();
                String result = chatClient.prompt()
                        .system(promptService.getImageExtractionPrompt())
                        .user(u -> u.text(userText).media(MimeTypeUtils.IMAGE_JPEG, imageResource))
                        .options(OllamaOptions.builder()
                                .model(visionModel)
                                .numPredict(4096)
                                .temperature(0.1)
                                .repeatPenalty(1.5)
                                .build())
                        .call()
                        .content();
                return result != null ? result : "";
            } catch (Exception e) {
                log.warn("Vision model attempt {}/{} failed for {}: {}", attempt, maxRetries, fileName, e.getMessage());
                if (attempt == maxRetries) {
                    log.error("Vision model failed after {} retries for: {}", maxRetries, fileName);
                    return "";
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return "";
    }

    private byte[] convertToJpeg(byte[] imageData, String fileName) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
            if (image == null) {
                log.warn("ImageIO cannot read image: {}, sending raw bytes", fileName);
                return imageData;
            }
            BufferedImage rgbImage = createVisionImage(image);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(rgbImage, "jpg", out);
            log.info("Converted image to enhanced JPEG: {} ({}KB -> {}KB, {}x{})", fileName, imageData.length / 1024, out.size() / 1024, rgbImage.getWidth(), rgbImage.getHeight());
            return out.toByteArray();
        } catch (IOException e) {
            log.warn("Failed to convert image to JPEG: {}, sending raw bytes", fileName, e);
            return imageData;
        }
    }

    private BufferedImage createVisionImage(BufferedImage image) {
        int targetWidth = Math.max(image.getWidth(), 1600);
        int targetHeight = Math.max(1, (int) Math.round(image.getHeight() * (targetWidth / (double) image.getWidth())));
        BufferedImage rgbImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgbImage.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, targetWidth, targetHeight);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(image, 0, 0, targetWidth, targetHeight, Color.WHITE, null);
        g.dispose();
        return rgbImage;
    }

    private PatientStructuredInfo extractPatientInfo(String diagnosisText) {
        ChatClient chatClient = chatClientBuilder.build();
        String jsonText = chatClient.prompt()
                .system(promptService.getStructuredExtractionPrompt())
                .user(diagnosisText)
                .options(OllamaOptions.builder()
                        .model("qwen2.5:7b-instruct")
                        .temperature(0.1)
                        .build())
                .call()
                .content();
        return structuredExtractionService.parseAndValidate(jsonText, diagnosisText);
    }

    private List<Document> searchRelevantDocuments(PatientStructuredInfo patientInfo) {
        try {
            String query = buildRetrievalQuery(patientInfo);
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(5)
                    .similarityThreshold(0.35)
                    .build();
            List<Document> results = vectorStore.similaritySearch(request);
            return results != null ? results : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Vector search returned no results or failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String buildRetrievalQuery(PatientStructuredInfo patientInfo) {
        List<String> parts = new ArrayList<>();
        parts.addAll(patientInfo.getDiagnoses());
        parts.addAll(patientInfo.getChiefComplaints());
        parts.addAll(patientInfo.getRiskFactors());
        for (PatientStructuredInfo.LabResult lab : patientInfo.getLabResults()) {
            if (lab.getFlag() != null && !lab.getFlag().isBlank()) {
                parts.add(lab.getItem() + " " + lab.getFlag());
            }
        }
        if (patientInfo.getBasicInfo() != null) {
            if (patientInfo.getBasicInfo().getAge() != null) {
                parts.add(patientInfo.getBasicInfo().getAge());
            }
            if (patientInfo.getBasicInfo().getGender() != null) {
                parts.add(patientInfo.getBasicInfo().getGender());
            }
        }
        return String.join("; ", parts);
    }

    private void streamMedicationAnalysis(String patientSummary, List<Document> relevantDocs,
                                          SseEmitter emitter, AtomicBoolean emitterDead) {
        ChatClient chatClient = chatClientBuilder.build();
        String systemPrompt = promptService.getSystemPrompt();
        String userPrompt = promptService.buildAnalysisPrompt(patientSummary, relevantDocs);

        chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .stream()
                .content()
                .doOnNext(token -> {
                    if (!emitterDead.get()) {
                        sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                                .step(WorkflowStep.LLM_ANALYSIS)
                                .status("streaming")
                                .token(token)
                                .build());
                    }
                })
                .doOnError(error -> {
                    log.error("LLM streaming error", error);
                    if (!emitterDead.get()) {
                        sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                                .step(WorkflowStep.LLM_ANALYSIS)
                                .status("error")
                                .message("Generation failed: " + error.getMessage())
                                .build());
                        try {
                            emitter.completeWithError(error);
                        } catch (Exception ignored) {
                        }
                    }
                })
                .doOnComplete(() -> {
                    if (!emitterDead.get()) {
                        sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                                .step(WorkflowStep.LLM_ANALYSIS)
                                .status("done")
                                .message("Analysis completed")
                                .build());
                        try {
                            emitter.complete();
                        } catch (Exception ignored) {
                        }
                    }
                })
                .blockLast();
    }

    private <T> T executeWithHeartbeat(SseEmitter emitter, AtomicBoolean emitterDead,
                                       WorkflowStep step, String message, Callable<T> task) throws Exception {
        ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger elapsed = new AtomicInteger(0);

        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (emitterDead.get()) {
                return;
            }
            int secs = elapsed.addAndGet(10);
            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                    .step(step)
                    .status("running")
                    .message(message + " (elapsed " + secs + " seconds)")
                    .build());
        }, 10, 10, TimeUnit.SECONDS);

        try {
            return task.call();
        } finally {
            heartbeat.cancel(false);
            heartbeatScheduler.shutdown();
        }
    }

    private void sendEvent(SseEmitter emitter, AtomicBoolean emitterDead, WorkflowStepEvent event) {
        if (emitterDead.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("workflow").data(event, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send SSE event (connection likely closed): {}", e.getMessage());
            emitterDead.set(true);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}

