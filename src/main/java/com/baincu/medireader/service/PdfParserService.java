package com.baincu.medireader.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PdfParserService {

    private static final int MIN_CHUNK_LENGTH = 20;

    private final OcrService ocrService;

    public PdfParserService(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    public List<Document> parsePdf(Resource pdfResource) {
        log.info("Parsing PDF: {}", pdfResource.getDescription());
        PagePdfDocumentReader reader = new PagePdfDocumentReader(pdfResource);
        return reader.read();
    }

    public List<Document> parsePdfAndSplit(Resource pdfResource) {
        List<Document> documents = parsePdfWithFallback(pdfResource, "未知PDF");
        TokenTextSplitter splitter = new TokenTextSplitter();
        List<Document> chunks = splitter.split(documents);

        List<Document> cleaned = chunks.stream()
                .filter(doc -> {
                    String text = doc.getText();
                    if (text == null) return false;
                    String trimmed = cleanText(text);
                    return trimmed.length() >= MIN_CHUNK_LENGTH;
                })
                .map(doc -> {
                    String cleaned2 = cleanText(doc.getText());
                    return doc.mutate().text(cleaned2).build();
                })
                .toList();

        log.info("PDF split into {} chunks, {} after cleaning", chunks.size(), cleaned.size());
        return cleaned;
    }

    public String extractFullText(Resource pdfResource) {
        List<Document> documents = parsePdf(pdfResource);
        String extracted = documents.stream()
                .map(doc -> Objects.requireNonNullElse(doc.getText(), ""))
                .map(this::cleanText)
                .collect(Collectors.joining("\n\n"));
        if (looksLikeScannedPdf(extracted) && ocrService.isAvailable()) {
            String ocrText = ocrService.extractTextFromPdfPages(pdfResource);
            if (!ocrText.isBlank()) {
                return cleanText(ocrText);
            }
        }
        return extracted;
    }

    public List<Document> parsePdfWithFallback(Resource pdfResource, String sourceName) {
        List<Document> documents = parsePdf(pdfResource);
        String merged = documents.stream()
                .map(doc -> Objects.requireNonNullElse(doc.getText(), ""))
                .map(this::cleanText)
                .collect(Collectors.joining("\n\n"));

        if (!looksLikeScannedPdf(merged)) {
            return enrichDocuments(documents, sourceName, "pdfbox");
        }

        log.info("PDF appears to be scanned, trying OCR fallback: {}", sourceName);
        List<Document> ocrDocs = ocrService.splitOcrPdfToDocuments(pdfResource, sourceName);
        if (!ocrDocs.isEmpty()) {
            return ocrDocs;
        }
        return enrichDocuments(documents, sourceName, "pdfbox");
    }

    private List<Document> enrichDocuments(List<Document> documents, String sourceName, String parseMethod) {
        List<Document> enriched = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            String text = cleanText(doc.getText());
            if (text.length() < MIN_CHUNK_LENGTH) {
                continue;
            }
            enriched.add(doc.mutate()
                    .text(text)
                    .metadata("source", sourceName)
                    .metadata("page", String.valueOf(i + 1))
                    .metadata("sectionTitle", inferSectionTitle(text))
                    .metadata("parseMethod", parseMethod)
                    .build());
        }
        return enriched;
    }

    private boolean looksLikeScannedPdf(String text) {
        if (text == null) {
            return true;
        }
        String cleaned = cleanText(text);
        if (cleaned.length() < 80) {
            return true;
        }
        long chineseChars = cleaned.chars().filter(c -> c >= 0x4E00 && c <= 0x9FFF).count();
        return chineseChars < 10 && cleaned.length() < 200;
    }

    private String inferSectionTitle(String text) {
        return text.lines()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .map(line -> line.length() <= 30 ? line : line.substring(0, 30))
                .orElse("未识别章节");
    }

    private String cleanText(String text) {
        if (text == null) return "";
        return text
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
