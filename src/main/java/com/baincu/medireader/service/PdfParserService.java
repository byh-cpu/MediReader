package com.baincu.medireader.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PdfParserService {

    private static final int MIN_CHUNK_LENGTH = 20;

    public List<Document> parsePdf(Resource pdfResource) {
        log.info("Parsing PDF: {}", pdfResource.getDescription());
        PagePdfDocumentReader reader = new PagePdfDocumentReader(pdfResource);
        return reader.read();
    }

    public List<Document> parsePdfAndSplit(Resource pdfResource) {
        List<Document> documents = parsePdf(pdfResource);
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
        return documents.stream()
                .map(doc -> Objects.requireNonNullElse(doc.getText(), ""))
                .map(this::cleanText)
                .collect(Collectors.joining("\n\n"));
    }

    private String cleanText(String text) {
        if (text == null) return "";
        return text
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
