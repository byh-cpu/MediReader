package com.baincu.medireader.service;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@Slf4j
public class OcrService {

    @Value("${medireader.tessdata-dir:./data/tessdata}")
    private String tessdataDir;

    private Tesseract tesseract;
    private boolean tesseractAvailable = false;

    @PostConstruct
    public void init() {
        try {
            Path tessdataPath = Paths.get(System.getProperty("user.dir")).resolve(tessdataDir).normalize();
            Files.createDirectories(tessdataPath);

            Path chiSimData = tessdataPath.resolve("chi_sim.traineddata");
            if (!Files.exists(chiSimData)) {
                log.warn("Tesseract Chinese training data not found at: {}", chiSimData);
                log.warn("Please download chi_sim.traineddata from https://github.com/tesseract-ocr/tessdata");
                log.warn("and place it in: {}", tessdataPath);
                log.warn("Tesseract OCR will be disabled, falling back to vision model only.");
                return;
            }

            tesseract = new Tesseract();
            tesseract.setDatapath(tessdataPath.toString());

            Path engData = tessdataPath.resolve("eng.traineddata");
            if (Files.exists(engData)) {
                tesseract.setLanguage("chi_sim+eng");
                log.info("Tesseract configured with Chinese + English");
            } else {
                tesseract.setLanguage("chi_sim");
                log.warn("eng.traineddata not found, English text recognition may be poor");
                log.warn("Download from https://github.com/tesseract-ocr/tessdata/raw/main/eng.traineddata");
            }

            tesseract.setPageSegMode(3);
            tesseract.setOcrEngineMode(1);
            tesseractAvailable = true;
            log.info("Tesseract OCR initialized with tessdata: {}", tessdataPath);
        } catch (Exception e) {
            log.warn("Failed to initialize Tesseract OCR: {}. Falling back to vision model only.", e.getMessage());
        }
    }

    public boolean isAvailable() {
        return tesseractAvailable;
    }

    public String extractText(byte[] imageData, String fileName) {
        if (!tesseractAvailable) {
            return "";
        }

        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(imageData));
            if (original == null) {
                log.warn("Cannot read image for OCR: {}", fileName);
                return "";
            }

            String bestText = recognizeBestCandidate(original);
            log.info("Tesseract OCR extracted {} chars from: {}", bestText.length(), fileName);
            return bestText;
        } catch (TesseractException | IOException e) {
            log.warn("Tesseract OCR failed for {}: {}", fileName, e.getMessage());
            return "";
        }
    }

    public String extractTextFromPdfPages(Resource pdfResource) {
        if (!tesseractAvailable) {
            return "";
        }
        try {
            org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfResource.getInputStream().readAllBytes());
            org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(document);
            List<String> pageTexts = new ArrayList<>();
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, 220);
                String text = recognizeBestCandidate(image);
                if (!text.isBlank()) {
                    pageTexts.add("第" + (i + 1) + "页:\n" + text);
                }
            }
            document.close();
            return String.join("\n\n", pageTexts);
        } catch (Exception e) {
            log.warn("PDF OCR failed: {}", e.getMessage());
            return "";
        }
    }

    public List<Document> splitOcrPdfToDocuments(Resource pdfResource, String sourceName) {
        String text = extractTextFromPdfPages(pdfResource);
        if (text.isBlank()) {
            return List.of();
        }
        List<String> pages = splitOcrPages(text);
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            String pageText = pages.get(i);
            if (i > 0) {
                pageText = "第" + pageText;
            }
            String cleaned = cleanOcrText(pageText);
            if (cleaned.length() < 20) {
                continue;
            }
            docs.add(new Document(cleaned, java.util.Map.of(
                    "source", sourceName,
                    "page", String.valueOf(i + 1),
                    "sectionTitle", inferSectionTitle(cleaned),
                    "parseMethod", "ocr"
            )));
        }
        return docs;
    }

    public BufferedImage preprocessForOcr(BufferedImage original) {
        return createEnhancedGrayImage(original, 1800);
    }

    private String recognizeBestCandidate(BufferedImage original) throws TesseractException {
        List<BufferedImage> candidates = buildOcrCandidates(original);
        String bestText = "";
        int bestScore = -1;
        int originalPsm = 3;
        for (int i = 0; i < candidates.size(); i++) {
            BufferedImage candidate = candidates.get(i);
            int psm = i == candidates.size() - 1 ? 6 : 3;
            try {
                tesseract.setPageSegMode(psm);
                String cleaned = cleanOcrText(tesseract.doOCR(candidate));
                int score = scoreOcrText(cleaned);
                if (score > bestScore) {
                    bestScore = score;
                    bestText = cleaned;
                }
            } catch (TesseractException e) {
                log.warn("Tesseract candidate {} failed: {}", i + 1, e.getMessage());
            } finally {
                tesseract.setPageSegMode(originalPsm);
            }
        }
        return bestText;
    }

    private List<BufferedImage> buildOcrCandidates(BufferedImage original) {
        BufferedImage gray1800 = createEnhancedGrayImage(original, 1800);
        BufferedImage gray2400 = createEnhancedGrayImage(original, 2400);
        BufferedImage binary1800 = binarize(gray1800);
        BufferedImage binary2400 = binarize(gray2400);
        BufferedImage sharpened = sharpen(gray2400);
        return List.of(gray1800, gray2400, binary1800, binary2400, sharpened);
    }

    private BufferedImage createEnhancedGrayImage(BufferedImage original, int targetMinWidth) {
        BufferedImage scaled = scaleImage(original, targetMinWidth);
        BufferedImage gray = toGray(scaled);
        return enhanceContrast(gray);
    }

    private BufferedImage scaleImage(BufferedImage original, int targetMinWidth) {
        int w = original.getWidth();
        int h = original.getHeight();
        double scale = Math.max(1.0, targetMinWidth / (double) Math.max(1, w));
        int newW = Math.max(1, (int) Math.round(w * scale));
        int newH = Math.max(1, (int) Math.round(h * scale));
        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, newW, newH);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(original, 0, 0, newW, newH, Color.WHITE, null);
        g.dispose();
        return scaled;
    }

    private BufferedImage toGray(BufferedImage image) {
        BufferedImage gray = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return gray;
    }

    private BufferedImage sharpen(BufferedImage image) {
        float[] sharpenKernel = {0, -0.8f, 0, -0.8f, 4.2f, -0.8f, 0, -0.8f, 0};
        ConvolveOp sharpen = new ConvolveOp(new Kernel(3, 3, sharpenKernel), ConvolveOp.EDGE_NO_OP, null);
        return sharpen.filter(image, null);
    }

    private BufferedImage binarize(BufferedImage image) {
        BufferedImage gray = image.getType() == BufferedImage.TYPE_BYTE_GRAY ? image : toGray(image);
        int threshold = otsuThreshold(gray);
        BufferedImage binary = new BufferedImage(gray.getWidth(), gray.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < gray.getHeight(); y++) {
            for (int x = 0; x < gray.getWidth(); x++) {
                int value = gray.getRaster().getSample(x, y, 0);
                int rgb = value > threshold ? 0xFFFFFF : 0x000000;
                binary.setRGB(x, y, rgb);
            }
        }
        return binary;
    }

    private int otsuThreshold(BufferedImage gray) {
        int[] histogram = new int[256];
        int width = gray.getWidth();
        int height = gray.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                histogram[gray.getRaster().getSample(x, y, 0)]++;
            }
        }

        int total = width * height;
        double sum = 0;
        for (int i = 0; i < histogram.length; i++) {
            sum += i * histogram[i];
        }

        double sumB = 0;
        int weightB = 0;
        double maxVariance = -1;
        int threshold = 180;
        for (int i = 0; i < histogram.length; i++) {
            weightB += histogram[i];
            if (weightB == 0) {
                continue;
            }
            int weightF = total - weightB;
            if (weightF == 0) {
                break;
            }
            sumB += i * histogram[i];
            double meanB = sumB / weightB;
            double meanF = (sum - sumB) / weightF;
            double variance = (double) weightB * weightF * (meanB - meanF) * (meanB - meanF);
            if (variance > maxVariance) {
                maxVariance = variance;
                threshold = i;
            }
        }
        return Math.max(120, Math.min(220, threshold));
    }

    private int scoreOcrText(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int chinese = 0;
        int digits = 0;
        int medicalSeparators = 0;
        int noise = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                chinese++;
            } else if (Character.isDigit(c)) {
                digits++;
            } else if (c == ':' || c == '：' || c == '/' || c == '-' || c == '+' || c == '.') {
                medicalSeparators++;
            } else if (!Character.isLetter(c) && !Character.isWhitespace(c) && c != ',' && c != '，' && c != '。' && c != ';' && c != '；') {
                noise++;
            }
        }
        return text.length() + chinese * 4 + digits * 2 + medicalSeparators - noise * 3;
    }

    private BufferedImage enhanceContrast(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);

        int min = 255, max = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = image.getRaster().getSample(x, y, 0);
                min = Math.min(min, pixel);
                max = Math.max(max, pixel);
            }
        }

        if (max == min) return image;

        double range = max - min;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = image.getRaster().getSample(x, y, 0);
                int stretched = (int) ((pixel - min) / range * 255);
                result.getRaster().setSample(x, y, 0, Math.min(255, Math.max(0, stretched)));
            }
        }
        return result;
    }

    private String inferSectionTitle(String text) {
        String firstLine = text.lines().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).findFirst().orElse("OCR分页内容");
        return firstLine.length() <= 30 ? firstLine : firstLine.substring(0, 30);
    }

    private String cleanOcrText(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        int consecutiveSpaces = 0;
        int consecutiveNewlines = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x00 && c <= 0x08) || c == 0x0B || c == 0x0C || (c >= 0x0E && c <= 0x1F)) {
                continue;
            }
            if (c == '\r') {
                continue;
            }
            if (c == '\n') {
                consecutiveSpaces = 0;
                if (consecutiveNewlines < 2) {
                    sb.append(c);
                }
                consecutiveNewlines++;
                continue;
            }
            consecutiveNewlines = 0;
            if (c == ' ') {
                if (consecutiveSpaces < 2) {
                    sb.append(c);
                }
                consecutiveSpaces++;
                continue;
            }
            consecutiveSpaces = 0;
            sb.append(c);
        }
        return sb.toString().trim();
    }

    private List<String> splitOcrPages(String text) {
        List<String> pages = new ArrayList<>();
        String marker = "\n\n第";
        int start = 0;
        int markerIndex;
        while ((markerIndex = text.indexOf(marker, start)) >= 0) {
            if (markerIndex > start) {
                pages.add(text.substring(start, markerIndex));
            }
            start = markerIndex + 2;
        }
        if (start < text.length()) {
            pages.add(text.substring(start));
        }
        return pages;
    }
}
