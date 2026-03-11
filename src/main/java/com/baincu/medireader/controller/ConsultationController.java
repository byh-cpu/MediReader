package com.baincu.medireader.controller;

import com.baincu.medireader.service.ConsultationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/consultation")
@Slf4j
public class ConsultationController {

    private static final List<String> IMAGE_TYPES = List.of(
            "image/jpeg", "image/png", "image/jpg");

    private final ConsultationService consultationService;
    private final TaskExecutor workflowExecutor;

    public ConsultationController(ConsultationService consultationService,
                                  @Qualifier("workflowExecutor") TaskExecutor workflowExecutor) {
        this.consultationService = consultationService;
        this.workflowExecutor = workflowExecutor;
    }

    @PostMapping(value = "/analyze", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyze(@RequestParam("files") List<MultipartFile> files) {
        SseEmitter emitter = new SseEmitter(600_000L);

        List<ConsultationService.UploadedFile> uploadedFiles = new ArrayList<>();
        for (MultipartFile file : files) {
            String contentType = file.getContentType();
            boolean isImage = contentType != null && IMAGE_TYPES.contains(contentType.toLowerCase());
            boolean isPdf = contentType != null && contentType.equalsIgnoreCase("application/pdf");

            if (!isImage && !isPdf) {
                log.warn("Skipping unsupported file type: {} ({})", file.getOriginalFilename(), contentType);
                continue;
            }

            try {
                uploadedFiles.add(new ConsultationService.UploadedFile(
                        file.getBytes(),
                        file.getOriginalFilename(),
                        isImage ? "image" : "pdf",
                        contentType
                ));
            } catch (IOException e) {
                log.error("Failed to read file: {}", file.getOriginalFilename(), e);
            }
        }

        if (uploadedFiles.isEmpty()) {
            emitter.completeWithError(new IllegalArgumentException("没有有效的文件"));
            return emitter;
        }

        String displayName = uploadedFiles.stream()
                .map(ConsultationService.UploadedFile::fileName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("未知文件");

        workflowExecutor.execute(() -> {
            try {
                consultationService.analyzePatientDiagnosis(uploadedFiles, emitter);
            } catch (Exception e) {
                log.error("Workflow execution failed", e);
                try { emitter.completeWithError(e); } catch (Exception ignored) {}
            }
        });

        emitter.onTimeout(() -> log.warn("SSE connection timed out for: {}", displayName));
        emitter.onError(e -> log.warn("SSE error for: {}", displayName, e));

        return emitter;
    }
}
