package com.baincu.medireader.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PdfParserService {

    private static final int MIN_CHUNK_LENGTH = 20;
    private static final int CHUNK_SIZE = 1200;
    private static final int CHUNK_OVERLAP = 150;

    private final OcrService ocrService;

    public PdfParserService(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    public List<Document> parsePdf(Resource pdfResource) {
        log.info("Parsing PDF: {}", pdfResource.getDescription());
        List<Document> documents = new ArrayList<>();
        try (PDDocument pdf = Loader.loadPDF(pdfResource.getInputStream().readAllBytes())) {
            int pageCount = pdf.getNumberOfPages();
            for (int page = 1; page <= pageCount; page++) {
                String text = extractPageText(pdf, page, true);
                if (text.isBlank()) {
                    text = extractPageText(pdf, page, false);
                }
                String cleaned = cleanText(text);
                if (cleaned.length() < MIN_CHUNK_LENGTH) {
                    continue;
                }
                documents.add(new Document(cleaned, Map.of("page", String.valueOf(page))));
            }
        } catch (IOException e) {
            throw new RuntimeException("PDF解析失败: " + e.getMessage(), e);
        }
        return documents;
    }

    private String extractPageText(PDDocument pdf, int page, boolean sortByPosition) throws IOException {
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(sortByPosition);
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            return stripper.getText(pdf);
        } catch (IllegalArgumentException e) {
            if (sortByPosition && e.getMessage() != null && e.getMessage().contains("Comparison method violates")) {
                log.warn("PDF page {} text position sorting failed, retrying without sorting", page);
                return "";
            }
            throw e;
        } catch (StackOverflowError e) {
            log.warn("PDF page {} text extraction overflowed with sortByPosition={}, skipping this attempt", page, sortByPosition);
            return "";
        }
    }

    public List<Document> parsePdfAndSplit(Resource pdfResource) {
        List<Document> documents = parsePdfWithFallback(pdfResource, "未知PDF");
        List<Document> chunks = splitDocumentsSafely(documents);
        log.info("PDF split into {} safe chunks", chunks.size());
        return chunks;
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

    private List<Document> splitDocumentsSafely(List<Document> documents) {
        List<Document> chunks = new ArrayList<>();
        int globalIndex = 0;
        for (Document doc : documents) {
            String text = cleanText(doc.getText());
            if (text.length() < MIN_CHUNK_LENGTH) {
                continue;
            }

            if (text.length() <= CHUNK_SIZE) {
                chunks.add(doc.mutate()
                        .text(text)
                        .metadata("chunkIndex", globalIndex++)
                        .build());
                continue;
            }

            int start = 0;
            while (start < text.length()) {
                int end = Math.min(start + CHUNK_SIZE, text.length());
                int adjustedEnd = adjustChunkEnd(text, start, end);
                String chunkText = text.substring(start, adjustedEnd).trim();
                if (chunkText.length() >= MIN_CHUNK_LENGTH) {
                    chunks.add(doc.mutate()
                            .text(chunkText)
                            .metadata("chunkIndex", globalIndex++)
                            .build());
                }
                if (adjustedEnd >= text.length()) {
                    break;
                }
                start = Math.max(adjustedEnd - CHUNK_OVERLAP, start + 1);
            }
        }
        return chunks;
    }

    private int adjustChunkEnd(String text, int start, int end) {
        if (end >= text.length()) {
            return text.length();
        }
        int minEnd = Math.min(text.length(), start + CHUNK_SIZE / 2);
        for (int i = end; i > minEnd; i--) {
            char c = text.charAt(i - 1);
            if (c == '。' || c == '；' || c == ';' || c == '\n' || c == '.' || c == ',') {
                return i;
            }
        }
        return end;
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
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        boolean previousWhitespace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x00 && c <= 0x08) || c == 0x0B || c == 0x0C || (c >= 0x0E && c <= 0x1F)) {
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (!previousWhitespace) {
                    sb.append(' ');
                    previousWhitespace = true;
                }
            } else {
                sb.append(c);
                previousWhitespace = false;
            }
        }
        return sb.toString().trim();
    }
}
