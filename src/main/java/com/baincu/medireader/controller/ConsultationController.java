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

@RestController
@RequestMapping("/api/consultation")
@Slf4j
public class ConsultationController {

    private final ConsultationService consultationService;
    private final TaskExecutor workflowExecutor;

    public ConsultationController(ConsultationService consultationService,
                                  @Qualifier("workflowExecutor") TaskExecutor workflowExecutor) {
        this.consultationService = consultationService;
        this.workflowExecutor = workflowExecutor;
    }

    @PostMapping(value = "/analyze", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyze(@RequestParam("file") MultipartFile file) {
        SseEmitter emitter = new SseEmitter(300_000L);

        byte[] fileBytes;
        String fileName = file.getOriginalFilename();
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("文件读取失败", e);
        }

        workflowExecutor.execute(() -> {
            try {
                consultationService.analyzePatientDiagnosis(fileBytes, fileName, emitter);
            } catch (Exception e) {
                log.error("Workflow execution failed", e);
                emitter.completeWithError(e);
            }
        });

        emitter.onTimeout(() -> log.warn("SSE connection timed out for file: {}", fileName));
        emitter.onError(e -> log.error("SSE error for file: {}", fileName, e));

        return emitter;
    }
}
