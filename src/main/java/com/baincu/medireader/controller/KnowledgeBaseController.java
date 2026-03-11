package com.baincu.medireader.controller;

import com.baincu.medireader.model.dto.KnowledgeDocumentInfo;
import com.baincu.medireader.model.dto.KnowledgeUploadResponse;
import com.baincu.medireader.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @PostMapping("/upload")
    public ResponseEntity<KnowledgeUploadResponse> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    KnowledgeUploadResponse.builder().message("请选择要上传的PDF文件").build());
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".pdf")) {
            return ResponseEntity.badRequest().body(
                    KnowledgeUploadResponse.builder().message("仅支持PDF格式文件").build());
        }
        try {
            KnowledgeUploadResponse response = knowledgeBaseService.uploadDocument(file);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Upload failed for file: {}", originalFilename, e);
            return ResponseEntity.internalServerError().body(
                    KnowledgeUploadResponse.builder()
                            .message("上传失败: " + e.getMessage())
                            .build());
        }
    }

    @GetMapping("/list")
    public ResponseEntity<List<KnowledgeDocumentInfo>> list() {
        return ResponseEntity.ok(knowledgeBaseService.listDocuments());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String id) {
        try {
            knowledgeBaseService.deleteDocument(id);
            return ResponseEntity.ok(Map.of("message", "文档删除成功"));
        } catch (Exception e) {
            log.error("Delete failed for document: {}", id, e);
            return ResponseEntity.internalServerError().body(
                    Map.of("message", "删除失败: " + e.getMessage()));
        }
    }
}
