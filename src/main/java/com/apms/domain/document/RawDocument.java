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
     * Source information about the original document or manual entry.
     */
    private Source source;

    /**
     * File storage metadata.
     */
    private Storage storage;

    /**
     * Processing lifecycle tracking.
     */
    @Builder.Default
    private Processing processing = new Processing();

    @Builder.Default
    private Boolean isHidden = false;

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
        /**
         * MANUAL_INPUT, PDF, DOCX, XLSX, CSV, WEBSITE
         */
        private String type;

        /** Original file name — null for MANUAL_INPUT. */
        private String fileName;

        /** Original URL — used for WEBSITE type. */
        private String originalUrl;

        /**
         * Raw text content entered by staff for MANUAL_INPUT,
         * or extracted text from file after parsing.
         */
        private String inputText;

        /** Optional company name hint provided at manual input time. */
        private String companyNameHint;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Storage {
        /**
         * LOCAL, MANUAL, WEBSITE
         */
        private String provider;

        /** Relative path on the local filesystem — null for MANUAL or WEBSITE. */
        private String path;

        /** MIME content type — null for MANUAL_INPUT. */
        private String mimeType;

        /** File size in bytes — null for MANUAL_INPUT. */
        private Long sizeBytes;

        /** File checksum (MD5/SHA256) — optional integrity check. */
        private String checksum;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Processing {
        /**
         * UPLOADED, EXTRACTED, FAILED
         */
        private String status;

        /** Number of candidates created from this document. */
        private Integer candidateCount;

        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
        private String errorMessage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Metadata {
        /** userId of who uploaded/submitted this document (stored as String). */
        private String uploadedBy;
        private LocalDateTime uploadedAt;
        private LocalDateTime updatedAt;
        private LocalDateTime hiddenAt;
    }
}
