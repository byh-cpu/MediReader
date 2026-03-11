package com.baincu.medireader.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeUploadResponse {

    private String id;
    private String fileName;
    private int chunkCount;
    private String message;
}
