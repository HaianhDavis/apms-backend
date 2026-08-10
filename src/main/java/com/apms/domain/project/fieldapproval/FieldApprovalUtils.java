package com.apms.domain.project.fieldapproval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FieldApprovalUtils {

    /**
     * Converts a list of FieldApprovalRecords to a Map keyed by fieldPath.
     * Treats null list as empty.
     * Rejects null records.
     * Requires non-blank fieldPath.
     * Trims fieldPath.
     * Rejects duplicate fieldPath values.
     * Preserves deterministic insertion order.
     * Never silently overwrites duplicate entries.
     */
    public static Map<String, FieldApprovalRecord> toMap(List<FieldApprovalRecord> records) {
        Map<String, FieldApprovalRecord> map = new LinkedHashMap<>();
        if (records == null) {
            return map;
        }

        for (FieldApprovalRecord record : records) {
            if (record == null) {
                throw new IllegalArgumentException("FieldApprovalRecord cannot be null");
            }
            if (record.getFieldPath() == null || record.getFieldPath().trim().isEmpty()) {
                throw new IllegalArgumentException("FieldApprovalRecord fieldPath cannot be blank");
            }
            String path = record.getFieldPath().trim();
            if (map.containsKey(path)) {
                throw new IllegalArgumentException("Duplicate fieldPath found: " + path);
            }
            map.put(path, record);
        }

        return map;
    }
}
