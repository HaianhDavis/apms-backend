package com.apms.domain.document;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * MongoDB document representing the raw content of an uploaded file or manual input.
 * Linked to ImportJob (SQL) via importJobId (cross-DB soft reference).
 *
 * Collection: raw_documents
 */
@Document(collection = "raw_documents")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RawDocument {

    @Id
    private String id;

    /**
     * SQL Server projects.id — cross-DB soft reference (stored as String).
     */
    @Indexed
    private String projectId;

    /**
     * SQL Server import_jobs.id — cross-DB soft reference (stored as String).
     */
    @Indexed
    private String importJobId;

    /**
     * FILE_UPLOAD or MANUAL_INPUT (mirrors ImportJob.inputType).
     */
    private String inputType;

    /**
     * Source information about the original document or manual entry.
     */
    private Source source;

    /**
     * File storage information on the local filesystem.
     */
    private Storage storage;

    /**
     * AI processing output — populated later by AI extraction phase.
     */
    @Builder.Default
    private Processing processing = new Processing();

    /**
     * Audit metadata.
     */
    private Metadata metadata;

    // ─────────────────────────────────────────────────────────────
    // Embedded sub-documents
    // ─────────────────────────────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Source {
        /** Original file name — null for MANUAL_INPUT. */
        private String originalFileName;

        /** MIME content type — null for MANUAL_INPUT. */
        private String contentType;

        /**
         * The raw text content entered by staff for MANUAL_INPUT.
         * Also used to store extracted text from FILE_UPLOAD once AI runs.
         */
        private String inputText;

        /** Optional company name provided at manual input time for quick reference. */
        private String companyNameHint;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Storage {
        /** Relative path in uploads/ directory — null for MANUAL_INPUT. */
        private String localFilePath;

        /** File size in bytes — null for MANUAL_INPUT. */
        private Long fileSizeBytes;

        private LocalDateTime storedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Processing {
        /**
         * Full text extracted from the document — populated by AI extraction.
         * For MANUAL_INPUT, this mirrors source.inputText until AI runs.
         */
        private String extractedText;

        /** Raw JSON response from Spring AI — null until AI extraction is triggered. */
        private String aiRawOutput;

        private LocalDateTime processedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Metadata {
        /** userId of who uploaded/submitted this document (stored as String). */
        private String uploadedBy;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }
}
