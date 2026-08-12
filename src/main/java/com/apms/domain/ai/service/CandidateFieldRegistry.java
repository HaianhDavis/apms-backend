package com.apms.domain.ai.service;

import java.util.Map;

public class CandidateFieldRegistry {
    /**
     * Maps AI extraction flat field names to canonical Candidate Domain Paths.
     * This ensures the Backend is the single source of truth for the field hierarchy.
     */
    private static final Map<String, String> FLAT_TO_PATH = Map.ofEntries(
        Map.entry("legalName", "identity.legalName"),
        Map.entry("tradeName", "identity.tradeName"),
        Map.entry("taxCode", "identity.taxCode"),
        Map.entry("address", "contact.address"),
        Map.entry("website", "contact.website"),
        Map.entry("email", "contact.emails"),
        Map.entry("phone", "contact.phones"),
        Map.entry("businessModel", "business.businessModel"),
        Map.entry("industries", "business.industries"),
        Map.entry("markets", "business.markets"),
        Map.entry("targetCustomers", "business.targetCustomers"),
        Map.entry("products", "business.products"),
        Map.entry("employeeTier", "companySize.employeeTier"),
        Map.entry("employeeCount", "companySize.employeeCount"),
        Map.entry("revenueTier", "companySize.revenueTier"),
        Map.entry("companySize", "companySize.revenueTier"), // Backward compatibility if AI outputs "companySize"
        Map.entry("strengths", "insights.strengths"),
        Map.entry("weaknesses", "insights.weaknesses"),
        Map.entry("opportunities", "insights.opportunities"),
        Map.entry("threats", "insights.threats"),
        Map.entry("financial", "financial"),
        Map.entry("market", "market"),
        Map.entry("innovation", "innovation"),
        Map.entry("risk", "risk"),
        Map.entry("compliance", "compliance")
    );
    
    /**
     * Converts a flat AI field name to its corresponding Domain Path.
     * Returns the original string if no mapping exists.
     */
    public static String toPath(String flatKey) {
        if (flatKey == null) return null;
        return FLAT_TO_PATH.getOrDefault(flatKey, flatKey);
    }
}
