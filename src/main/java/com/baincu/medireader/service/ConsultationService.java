package com.baincu.medireader.service;

import com.baincu.medireader.model.dto.WorkflowStepEvent;
import com.baincu.medireader.model.enums.WorkflowStep;
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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
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

    @Value("${medireader.vision-model:minicpm-v}")
    private String visionModel;

    public ConsultationService(PdfParserService pdfParserService,
                               SimpleVectorStore vectorStore,
                               ChatClient.Builder chatClientBuilder,
                               PromptService promptService,
                               OcrService ocrService) {
        this.pdfParserService = pdfParserService;
        this.vectorStore = vectorStore;
        this.chatClientBuilder = chatClientBuilder;
        this.promptService = promptService;
        this.ocrService = ocrService;
    }

    public record UploadedFile(byte[] data, String fileName, String type, String contentType) {}

    public void analyzePatientDiagnosis(List<UploadedFile> files, SseEmitter emitter) {
        AtomicBoolean emitterDead = new AtomicBoolean(false);
        emitter.onTimeout(() -> emitterDead.set(true));
        emitter.onError(e -> emitterDead.set(true));
        emitter.onCompletion(() -> emitterDead.set(true));

        try {
            // Step 1: Parse files
            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                    .step(WorkflowStep.PDF_PARSING)
                    .status("running")
                    .message("正在解析上传的文件...")
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
                        public String getFilename() { return file.fileName(); }
                    };
                    String text = pdfParserService.extractFullText(resource);
                    textParts.add(text);
                    log.info("Parsed PDF: {} ({} chars)", file.fileName(), text.length());
                } else if ("image".equals(file.type())) {
                    String text = executeWithHeartbeat(emitter, emitterDead, WorkflowStep.PDF_PARSING,
                            "正在识别图片 [" + file.fileName() + "]（OCR + 视觉模型）",
                            () -> extractTextFromImage(file.data(), file.contentType(), file.fileName()));
                    textParts.add(text);
                    log.info("Recognized image: {} ({} chars)", file.fileName(), text.length());
                }
            }

            if (emitterDead.get()) {
                log.warn("Emitter closed after parsing, aborting workflow");
                return;
            }

            String diagnosisText = String.join("\n\n", textParts);

            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                    .step(WorkflowStep.PDF_PARSING)
                    .status("done")
                    .message("文件解析完成 (" + fileNames.size() + " 个文件)")
                    .data(Map.of(
                            "textLength", diagnosisText.length(),
                            "preview", truncate(diagnosisText, 300),
                            "files", fileNames
                    ))
                    .build());

            // Step 2: Extract patient info via LLM
            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                    .step(WorkflowStep.INFO_EXTRACTION)
                    .status("running")
                    .message("正在提取患者关键信息...")
                    .build());

            String patientSummary = extractPatientInfo(diagnosisText);

            if (emitterDead.get()) {
                log.warn("Emitter closed after info extraction, aborting workflow");
                return;
            }

            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                    .step(WorkflowStep.INFO_EXTRACTION)
                    .status("done")
                    .message("患者信息提取完成")
                    .data(Map.of("patientSummary", patientSummary))
                    .build());

            // Step 3: RAG Retrieval
            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
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

            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                    .step(WorkflowStep.RAG_RETRIEVAL)
                    .status("done")
                    .message("检索到 " + relevantDocs.size() + " 条相关医学知识")
                    .data(Map.of("sources", sources, "count", relevantDocs.size()))
                    .build());

            if (emitterDead.get()) {
                log.warn("Emitter closed after RAG, aborting workflow");
                return;
            }

            // Step 4: LLM Streaming Analysis
            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                    .step(WorkflowStep.LLM_ANALYSIS)
                    .status("running")
                    .message("正在生成用药建议...")
                    .build());

            streamMedicationAnalysis(patientSummary, relevantDocs, emitter, emitterDead);

        } catch (Exception e) {
            log.error("Consultation analysis failed", e);
            if (!emitterDead.get()) {
                sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                        .step(WorkflowStep.LLM_ANALYSIS)
                        .status("error")
                        .message("分析失败: " + e.getMessage())
                        .build());
                try { emitter.completeWithError(e); } catch (Exception ignored) {}
            }
        }
    }

    private String extractTextFromImage(byte[] imageData, String contentType, String fileName) {
        StringBuilder combined = new StringBuilder();

        // Phase 1: Tesseract OCR (fast, good for printed text)
        String ocrText = "";
        if (ocrService.isAvailable()) {
            log.info("Running Tesseract OCR on: {}", fileName);
            ocrText = ocrService.extractText(imageData, fileName);
            if (!ocrText.isBlank()) {
                combined.append("【OCR识别结果】\n").append(ocrText).append("\n\n");
            }
        }

        // Phase 2: Vision model (good for understanding layout and handwriting)
        log.info("Recognizing image with vision model [{}]: {}", visionModel, fileName);
        byte[] jpegData = convertToJpeg(imageData, fileName);
        String visionText = callVisionModel(jpegData, fileName, ocrText);
        if (!visionText.isBlank()) {
            combined.append("【视觉模型识别结果】\n").append(visionText);
        }

        String result = combined.toString().trim();
        if (result.isEmpty()) {
            throw new RuntimeException("图片识别失败：OCR 和视觉模型均未能提取到有效文字");
        }
        return result;
    }

    private String callVisionModel(byte[] jpegData, String fileName, String ocrReference) {
        Resource imageResource = new ByteArrayResource(jpegData) {
            @Override
            public String getFilename() { return fileName.replaceAll("\\.[^.]+$", ".jpg"); }
        };

        String userText = ocrReference.isBlank()
                ? "请仔细识别这张医疗文档图片中的所有文字内容。包括表格中的每一行数据。如果是手写文字，请尽量辨认但对不确定的标注[不确定]。"
                : "OCR已初步识别出以下文字，请对照图片内容进行校正和补充，特别注意表格中的数值数据：\n" + truncate(ocrReference, 3000);

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
                try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
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
            BufferedImage rgbImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            rgbImage.createGraphics().drawImage(image, 0, 0, java.awt.Color.WHITE, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(rgbImage, "jpg", out);
            log.info("Converted image to JPEG: {} ({}KB -> {}KB)", fileName, imageData.length / 1024, out.size() / 1024);
            return out.toByteArray();
        } catch (IOException e) {
            log.warn("Failed to convert image to JPEG: {}, sending raw bytes", fileName, e);
            return imageData;
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
                                .message("生成失败: " + error.getMessage())
                                .build());
                        try { emitter.completeWithError(error); } catch (Exception ignored) {}
                    }
                })
                .doOnComplete(() -> {
                    if (!emitterDead.get()) {
                        sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                                .step(WorkflowStep.LLM_ANALYSIS)
                                .status("done")
                                .message("分析完成")
                                .build());
                        try { emitter.complete(); } catch (Exception ignored) {}
                    }
                })
                .blockLast();
    }

    private <T> T executeWithHeartbeat(SseEmitter emitter, AtomicBoolean emitterDead,
                                       WorkflowStep step, String message, Callable<T> task) throws Exception {
        ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger elapsed = new AtomicInteger(0);

        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (emitterDead.get()) return;
            int secs = elapsed.addAndGet(10);
            sendEvent(emitter, emitterDead, WorkflowStepEvent.builder()
                    .step(step)
                    .status("running")
                    .message(message + "（已耗时 " + secs + " 秒，请耐心等待）")
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
        if (emitterDead.get()) return;
        try {
            emitter.send(SseEmitter.event()
                    .name("workflow")
                    .data(event, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send SSE event (connection likely closed): {}", e.getMessage());
            emitterDead.set(true);
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
