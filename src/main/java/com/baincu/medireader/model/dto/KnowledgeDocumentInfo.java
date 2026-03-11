package com.baincu.medireader.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentInfo {

    private String id;
    private String fileName;
    private long fileSize;
    private int chunkCount;
    private List<String> chunkIds;
    private LocalDateTime uploadTime;
}
