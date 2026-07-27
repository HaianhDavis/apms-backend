package com.apms.domain.score.draft;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class SourceSnapshotHasher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Data
    @Builder
    public static class CanonicalSourceRecord {
        private String referenceId;
        private String sourceType;
        private Long sqlSourceId;
        private String mongoSourceId;
        private Integer sourceVersionNumber;
        private String sourceHash;
        private String criterionKey;
        private boolean sharedAcrossCriteria;
    }

    public static String hash(List<ApprovedSourceReference> references) {
        if (references == null || references.isEmpty()) {
            return computeSha256("[]");
        }

        // Validate duplicates by referenceId
        Set<String> referenceIds = references.stream()
                .map(ApprovedSourceReference::getReferenceId)
                .collect(Collectors.toSet());
        if (referenceIds.size() < references.size()) {
            throw new IllegalArgumentException("Duplicate referenceId detected in pinned sources");
        }

        // Validate duplicate immutable identities: sourceType + immutable source ID + source version + criterionKey
        long uniqueIdentities = references.stream()
                .map(r -> String.format("%s:%s:%s:%s",
                        r.getSourceType(),
                        r.getSqlSourceId() != null ? r.getSqlSourceId() : r.getMongoSourceId(),
                        r.getSourceVersionNumber(),
                        r.getCriterionKey()))
                .distinct()
                .count();

        if (uniqueIdentities < references.size()) {
            throw new IllegalArgumentException("Duplicate immutable source identity detected");
        }

        List<CanonicalSourceRecord> canonicalList = references.stream()
                .map(r -> CanonicalSourceRecord.builder()
                        .referenceId(r.getReferenceId())
                        .sourceType(r.getSourceType().name())
                        .sqlSourceId(r.getSqlSourceId())
                        .mongoSourceId(r.getMongoSourceId())
                        .sourceVersionNumber(r.getSourceVersionNumber())
                        .sourceHash(r.getSourceHash())
                        .criterionKey(r.getCriterionKey())
                        .sharedAcrossCriteria(r.isSharedAcrossCriteria())
                        .build())
                .sorted(Comparator.comparing(CanonicalSourceRecord::getCriterionKey, Comparator.nullsFirst(String::compareTo))
                        .thenComparing(CanonicalSourceRecord::isSharedAcrossCriteria)
                        .thenComparing(CanonicalSourceRecord::getSourceType, Comparator.nullsFirst(String::compareTo))
                        .thenComparing(CanonicalSourceRecord::getReferenceId, Comparator.nullsFirst(String::compareTo))
                        .thenComparing(r -> r.getSqlSourceId() != null ? r.getSqlSourceId().toString() : (r.getMongoSourceId() != null ? r.getMongoSourceId() : ""), Comparator.nullsFirst(String::compareTo))
                        .thenComparing(CanonicalSourceRecord::getSourceVersionNumber, Comparator.nullsFirst(Integer::compareTo)))
                .toList();

        try {
            String json = MAPPER.writeValueAsString(canonicalList);
            return computeSha256(json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize canonical source list", e);
        }
    }

    private static String computeSha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not supported", e);
        }
    }
}
