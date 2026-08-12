package com.apms.domain.ai.service;

public class FieldKeyCodec {

    /**
     * Encodes a dotted domain path into a safe MongoDB Map key.
     * E.g. "companySize.employeeCount" -> "companySize_employeeCount"
     * E.g. "contact.phones" -> "contact_phones"
     */
    public static String encode(String fieldPath) {
        if (fieldPath == null) {
            return null;
        }
        return fieldPath.replace(".", "_");
    }

    /**
     * Note: decoding is not generally required if the original fieldPath is persisted
     * within the map's value object (e.g. inside ExtractionFieldResult.fieldName).
     */
}
