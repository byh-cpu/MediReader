package com.baincu.medireader.service;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.beans.factory.annotation.Value;
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

            BufferedImage processed = preprocessForOcr(original);
            String text = tesseract.doOCR(processed);
            String cleaned = cleanOcrText(text);
            log.info("Tesseract OCR extracted {} chars from: {}", cleaned.length(), fileName);
            return cleaned;
        } catch (TesseractException | IOException e) {
            log.warn("Tesseract OCR failed for {}: {}", fileName, e.getMessage());
            return "";
        }
    }

    public BufferedImage preprocessForOcr(BufferedImage original) {
        int w = original.getWidth();
        int h = original.getHeight();

        double scale = 1.0;
        if (w < 1500) {
            scale = 1500.0 / w;
        }

        int newW = (int) (w * scale);
        int newH = (int) (h * scale);
        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(original, 0, 0, newW, newH, null);
        g.dispose();

        BufferedImage gray = new BufferedImage(newW, newH, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D gGray = gray.createGraphics();
        gGray.drawImage(scaled, 0, 0, null);
        gGray.dispose();

        float[] sharpenKernel = {0, -1, 0, -1, 5, -1, 0, -1, 0};
        ConvolveOp sharpen = new ConvolveOp(new Kernel(3, 3, sharpenKernel), ConvolveOp.EDGE_NO_OP, null);
        BufferedImage sharpened = sharpen.filter(gray, null);

        return enhanceContrast(sharpened);
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

    private String cleanOcrText(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")
                .replaceAll("\\n{3,}", "\n\n")
                .replaceAll(" {3,}", " ")
                .trim();
    }
}
