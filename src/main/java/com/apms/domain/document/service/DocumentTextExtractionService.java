package com.apms.domain.document.service;

import com.apms.domain.document.RawDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentTextExtractionService {

    private final StorageService storageService;

    public String extractText(RawDocument rawDocument) {
        if (rawDocument == null || rawDocument.getSource() == null) {
            return "";
        }
        String existingText = rawDocument.getSource().getInputText();
        if (StringUtils.hasText(existingText)) {
            return existingText;
        }
        if (rawDocument.getStorage() == null || !StringUtils.hasText(rawDocument.getStorage().getPath())) {
            return "";
        }
        return extractText(rawDocument.getSource().getType(), rawDocument.getStorage().getPath());
    }

    public String extractText(String sourceType, String storagePath) {
        if (!StringUtils.hasText(sourceType) || !StringUtils.hasText(storagePath)) {
            return "";
        }

        try {
            String normalizedType = sourceType.toUpperCase();
            Path path = storageService.load(storagePath).normalize().toAbsolutePath();
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                log.warn("Cannot extract text because file does not exist: {}", path);
                return "";
            }

            return switch (normalizedType) {
                case "PDF" -> extractPdf(path.toFile());
                case "CSV", "TXT" -> Files.readString(path);
                default -> "";
            };
        } catch (Exception e) {
            log.warn("Failed to extract text from document type={} path={}: {}", sourceType, storagePath, e.getMessage());
            return "";
        }
    }

    private String extractPdf(File file) throws Exception {
        try (PDDocument document = Loader.loadPDF(file)) {
            return new PDFTextStripper().getText(document);
        }
    }
}
