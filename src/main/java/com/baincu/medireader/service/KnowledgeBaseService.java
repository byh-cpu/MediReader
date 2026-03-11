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
        new File(knowledgeDir).mkdirs();
    }

    public KnowledgeUploadResponse uploadDocument(MultipartFile file) throws IOException {
        String docId = UUID.randomUUID().toString();

        File savedFile = new File(knowledgeDir, docId + ".pdf");
        file.transferTo(savedFile);

        Resource resource = new FileSystemResource(savedFile);
        List<Document> chunks = pdfParserService.parsePdfAndSplit(resource);

        List<String> chunkIds = new ArrayList<>();
        for (Document chunk : chunks) {
            chunk.getMetadata().put("documentId", docId);
            chunk.getMetadata().put("source", file.getOriginalFilename());
            chunkIds.add(chunk.getId());
        }

        vectorStore.add(chunks);
        vectorStore.save(new File(vectorStorePath));

        KnowledgeDocumentInfo info = KnowledgeDocumentInfo.builder()
                .id(docId)
                .fileName(file.getOriginalFilename())
                .fileSize(file.getSize())
                .chunkCount(chunks.size())
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
            vectorStore.save(new File(vectorStorePath));
        }

        new File(knowledgeDir, id + ".pdf").delete();

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
        File file = new File(metadataPath);
        if (!file.exists()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(file, new TypeReference<List<KnowledgeDocumentInfo>>() {});
        } catch (IOException e) {
            log.error("Failed to load metadata", e);
            return new ArrayList<>();
        }
    }

    private void saveAllMetadata(List<KnowledgeDocumentInfo> docs) {
        try {
            File file = new File(metadataPath);
            file.getParentFile().mkdirs();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, docs);
        } catch (IOException e) {
            log.error("Failed to save metadata", e);
        }
    }
}
