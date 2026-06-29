package com.apms.domain.ai.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Utility for detecting unknown/NA values and normalizing strings for comparison.
 */
public final class ExtractionMergeUtil {

    private static final Set<String> UNKNOWN_STRINGS = new HashSet<>(Arrays.asList(
            "na", "n/a", "unknown", "not available", "not provided", "none", ""
    ));

    private ExtractionMergeUtil() {}

    /**
     * Returns true if a string value is considered unknown/NA/missing.
     */
    public static boolean isUnknown(String value) {
        if (value == null) return true;
        return UNKNOWN_STRINGS.contains(value.trim().toLowerCase());
    }

    /**
     * Returns true if a collection is null or empty (all elements are unknown).
     */
    public static boolean isUnknownList(Collection<String> values) {
        if (values == null || values.isEmpty()) return true;
        return values.stream().allMatch(ExtractionMergeUtil::isUnknown);
    }

    /**
     * Normalizes a string for comparison: trim, collapse spaces, lowercase.
     * Returns null if the original value is null.
     */
    public static String normalizeForComparison(String value) {
        if (value == null) return null;
        return value.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    /**
     * Normalizes an email for comparison: trim + lowercase.
     */
    public static String normalizeEmail(String email) {
        if (email == null) return null;
        return email.trim().toLowerCase();
    }

    /**
     * Normalizes a website URL by trimming whitespace and removing trailing slash.
     */
    public static String normalizeWebsite(String url) {
        if (url == null) return null;
        String normalized = url.trim().toLowerCase();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * Normalizes a phone number by removing spaces for comparison.
     */
    public static String normalizePhone(String phone) {
        if (phone == null) return null;
        return phone.trim().replaceAll("\\s+", "");
    }

    /**
     * Returns true if two strings are meaningfully equivalent after normalization.
     */
    public static boolean isSameValue(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return normalizeForComparison(a).equals(normalizeForComparison(b));
    }

    /**
     * Picks the best display value between two strings.
     * Prefers the one with proper casing (more uppercase letters) or non-null.
     */
    public static String bestDisplayValue(String a, String b) {
        if (isUnknown(a) && isUnknown(b)) return null;
        if (isUnknown(a)) return b;
        if (isUnknown(b)) return a;
        // Prefer the value with more uppercase characters (better casing)
        long upperA = a.chars().filter(Character::isUpperCase).count();
        long upperB = b.chars().filter(Character::isUpperCase).count();
        return upperA >= upperB ? a : b;
    }
}
