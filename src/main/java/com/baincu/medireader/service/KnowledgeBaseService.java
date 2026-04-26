package com.baincu.medireader.service;

import com.baincu.medireader.model.dto.KnowledgeDocumentInfo;
import com.baincu.medireader.model.dto.KnowledgeUploadResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class KnowledgeBaseService {

    private final PdfParserService pdfParserService;
    private final SimpleVectorStore vectorStore;
    private final ObjectMapper objectMapper;

    private File knowledgeDirFile;
    private File vectorStoreFile;
    private File metadataFile;

    @Value("${medireader.knowledge-dir}")
    private String knowledgeDir;

    @Value("${medireader.vectorstore-path}")
    private String vectorStorePath;

    @Value("${medireader.metadata-path}")
    private String metadataPath;

    public KnowledgeBaseService(PdfParserService pdfParserService, SimpleVectorStore vectorStore) {
        this.pdfParserService = pdfParserService;
        this.vectorStore = vectorStore;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @PostConstruct
    public void init() {
        Path base = Paths.get(System.getProperty("user.dir"));
        this.knowledgeDirFile = base.resolve(knowledgeDir).normalize().toFile();
        this.vectorStoreFile = base.resolve(vectorStorePath).normalize().toFile();
        this.metadataFile = base.resolve(metadataPath).normalize().toFile();

        knowledgeDirFile.mkdirs();
        vectorStoreFile.getParentFile().mkdirs();
        log.info("Knowledge dir: {}", knowledgeDirFile.getAbsolutePath());
    }

    public KnowledgeUploadResponse uploadDocument(MultipartFile file) throws IOException {
        String docId = UUID.randomUUID().toString();

        File savedFile = new File(knowledgeDirFile, docId + ".pdf");
        file.transferTo(savedFile.getAbsoluteFile());

        Resource resource = new FileSystemResource(savedFile);
        List<Document> chunks = pdfParserService.parsePdfAndSplit(resource);

        int chunkIndex = 0;
        for (Document chunk : chunks) {
            chunk.getMetadata().put("documentId", docId);
            chunk.getMetadata().put("source", file.getOriginalFilename());
            chunk.getMetadata().putIfAbsent("chunkIndex", chunkIndex++);
        }

        List<String> chunkIds = new ArrayList<>();
        int failed = 0;
        for (Document chunk : chunks) {
            try {
                vectorStore.add(List.of(chunk));
                chunkIds.add(chunk.getId());
            } catch (Exception e) {
                failed++;
                log.warn("Skipping chunk {} (embedding failed): {}", chunk.getId(),
                        e.getMessage().length() > 100 ? e.getMessage().substring(0, 100) : e.getMessage());
            }
        }
        if (chunkIds.isEmpty()) {
            throw new IOException("所有文本块嵌入均失败，请检查 PDF 内容或 Ollama 嵌入模型状态");
        }
        vectorStore.save(vectorStoreFile);
        log.info("Embedded {}/{} chunks ({} failed)", chunkIds.size(), chunks.size(), failed);

        KnowledgeDocumentInfo info = KnowledgeDocumentInfo.builder()
                .id(docId)
                .fileName(file.getOriginalFilename())
                .fileSize(file.getSize())
                .chunkCount(chunkIds.size())
                .chunkIds(chunkIds)
                .uploadTime(LocalDateTime.now())
                .build();
        saveMetadata(info);

        log.info("Document uploaded: {} -> {} chunks", file.getOriginalFilename(), chunks.size());

        return KnowledgeUploadResponse.builder()
                .id(docId)
                .fileName(file.getOriginalFilename())
                .chunkCount(chunks.size())
                .message("文档上传成功，已分割为 " + chunks.size() + " 个文本块")
                .build();
    }

    public List<KnowledgeDocumentInfo> listDocuments() {
        return loadAllMetadata();
    }

    public void deleteDocument(String id) {
        List<KnowledgeDocumentInfo> docs = loadAllMetadata();
        KnowledgeDocumentInfo doc = docs.stream()
                .filter(d -> d.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("文档不存在: " + id));

        if (doc.getChunkIds() != null && !doc.getChunkIds().isEmpty()) {
            vectorStore.delete(doc.getChunkIds());
            vectorStore.save(vectorStoreFile);
        }

        new File(knowledgeDirFile, id + ".pdf").delete();

        docs.removeIf(d -> d.getId().equals(id));
        saveAllMetadata(docs);

        log.info("Document deleted: {}", doc.getFileName());
    }

    private void saveMetadata(KnowledgeDocumentInfo info) {
        List<KnowledgeDocumentInfo> docs = loadAllMetadata();
        docs.add(info);
        saveAllMetadata(docs);
    }

    private List<KnowledgeDocumentInfo> loadAllMetadata() {
        if (!metadataFile.exists()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(metadataFile, new TypeReference<List<KnowledgeDocumentInfo>>() {});
        } catch (IOException e) {
            log.error("Failed to load metadata", e);
            return new ArrayList<>();
        }
    }

    private void saveAllMetadata(List<KnowledgeDocumentInfo> docs) {
        try {
            metadataFile.getParentFile().mkdirs();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(metadataFile, docs);
        } catch (IOException e) {
            log.error("Failed to save metadata", e);
        }
    }
}
