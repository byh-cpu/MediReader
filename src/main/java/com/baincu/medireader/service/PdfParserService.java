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

    public List<Document> parsePdf(Resource pdfResource) {
        log.info("Parsing PDF: {}", pdfResource.getDescription());
        PagePdfDocumentReader reader = new PagePdfDocumentReader(pdfResource);
        return reader.read();
    }

    public List<Document> parsePdfAndSplit(Resource pdfResource) {
        List<Document> documents = parsePdf(pdfResource);
        TokenTextSplitter splitter = new TokenTextSplitter();
        List<Document> chunks = splitter.split(documents);
        log.info("PDF split into {} chunks", chunks.size());
        return chunks;
    }

    public String extractFullText(Resource pdfResource) {
        List<Document> documents = parsePdf(pdfResource);
        return documents.stream()
                .map(doc -> Objects.requireNonNullElse(doc.getText(), ""))
                .collect(Collectors.joining("\n\n"));
    }
}
