package com.apms.domain.ai.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class CompanyNameNormalizer {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    private static final Pattern NON_ALPHANUMERIC_SPACE = Pattern.compile("[^a-z0-9\\s]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    // Vietnamese legal prefixes (normalized/ascii form for comparison)
    private static final List<String> VN_PREFIXES = List.of(
            "cong ty co phan", "cong ty tnhh", "cong ty trach nhiem huu han",
            "tap doan", "tong cong ty", "cong ty"
    );

    // English/international legal suffixes
    private static final List<String> LEGAL_SUFFIXES = List.of(
            "corporation", "corp", "co ltd", "company limited", "limited", "ltd",
            "inc", "incorporated", "joint stock company", "jsc", "llc", "plc",
            "gmbh", "ag", "sa", "pte", "pvt", "vietnam", "viet nam", "group", "holdings"
    );

    /**
     * Normalize a company name for comparison:
     * 1. Lowercase
     * 2. Strip diacritics (Unicode NFKD)
     * 3. Remove punctuation
     * 4. Strip legal prefixes/suffixes
     * 5. Collapse whitespace and trim
     */
    public String normalize(String name) {
        if (!StringUtils.hasText(name)) return "";

        // Lowercase
        String result = name.toLowerCase(Locale.ROOT).trim();

        // Unicode normalize and strip diacritics
        String nfkd = Normalizer.normalize(result, Normalizer.Form.NFKD);
        result = DIACRITICS.matcher(nfkd).replaceAll("");

        // Remove punctuation (keep alphanumeric and spaces)
        result = NON_ALPHANUMERIC_SPACE.matcher(result).replaceAll(" ");

        // Collapse whitespace
        result = MULTI_SPACE.matcher(result).replaceAll(" ").trim();

        // Strip Vietnamese prefixes
        for (String prefix : VN_PREFIXES) {
            if (result.startsWith(prefix + " ")) {
                result = result.substring(prefix.length()).trim();
                break;
            }
        }

        // Strip legal suffixes
        for (String suffix : LEGAL_SUFFIXES) {
            if (result.endsWith(" " + suffix)) {
                result = result.substring(0, result.length() - suffix.length() - 1).trim();
                break;
            }
        }

        return result;
    }

    /**
     * Check if two company names likely refer to the same company.
     * Uses normalized comparison with containment fallback.
     */
    public boolean isSameCompany(String name1, String name2) {
        String n1 = normalize(name1);
        String n2 = normalize(name2);

        if (!StringUtils.hasText(n1) || !StringUtils.hasText(n2)) return false;

        // Exact normalized match
        if (n1.equals(n2)) return true;

        // One contains the other (e.g., "fpt" in "fpt corporation")
        if (n1.contains(n2) || n2.contains(n1)) return true;

        // Levenshtein similarity for close matches (threshold 0.85)
        double similarity = calculateSimilarity(n1, n2);
        return similarity >= 0.85;
    }

    /**
     * Normalize a URL/domain to just the base domain.
     */
    public String normalizeDomain(String url) {
        if (!StringUtils.hasText(url)) return "";
        String domain = url.toLowerCase(Locale.ROOT).trim();
        // Strip protocol
        domain = domain.replaceFirst("^https?://", "");
        // Strip www.
        domain = domain.replaceFirst("^www\\.", "");
        // Strip path
        int slash = domain.indexOf('/');
        if (slash > 0) domain = domain.substring(0, slash);
        // Strip query
        int question = domain.indexOf('?');
        if (question > 0) domain = domain.substring(0, question);
        return domain;
    }

    /**
     * Normalize a tax code for comparison: strip spaces, dashes.
     */
    public String normalizeTaxCode(String taxCode) {
        if (!StringUtils.hasText(taxCode)) return "";
        return taxCode.replaceAll("[\\s\\-]", "").trim();
    }

    private double calculateSimilarity(String s1, String s2) {
        int maxLen = Math.max(s1.length(), s2.length());
        if (maxLen == 0) return 1.0;
        int distance = levenshteinDistance(s1, s2);
        return 1.0 - ((double) distance / maxLen);
    }

    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= s2.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(
                        dp[i - 1][j] + 1,
                        dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost);
            }
        }
        return dp[s1.length()][s2.length()];
    }
}
