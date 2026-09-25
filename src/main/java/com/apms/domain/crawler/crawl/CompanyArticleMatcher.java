package com.apms.domain.crawler.crawl;

import com.apms.domain.crawler.domain.CrawledArticle;
import com.apms.domain.crawler.domain.CompanyMatch;
import com.apms.domain.crawler.domain.TrackedCompany;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
public class CompanyArticleMatcher {

    private static final String[] COMPANY_PREFIXES = {
        "công ty cổ phần ", "công ty tnhh ", "công ty trách nhiệm hữu hạn ", "tập đoàn ", "tổng công ty ", "công ty "
    };
    private static final String[] COMPANY_SUFFIXES = {
        " co., ltd.", " co., ltd", " ltd.", " ltd", " corporation", " corp.", " inc.", " incorporated", " plc.", " jsc"
    };
    private static final String[] BRAND_SUFFIXES = {
        " vietnam", " việt nam", " group", " holdings"
    };

    public List<CrawledArticle> matchAndAssign(List<CrawledArticle> articles, List<TrackedCompany> companies) {
        List<CrawledArticle> matchedArticles = new ArrayList<>();
        
        log.info("CompanyArticleMatcher: trackedCompanies={}", companies.size());
        
        Map<String, Integer> companyMatchCounts = new LinkedHashMap<>();
        
        // Prepare aliases for debugging
        for (TrackedCompany company : companies) {
            companyMatchCounts.put(company.getCompanyName(), 0);
            if (log.isDebugEnabled()) {
                log.debug("TrackedCompanyAliases: {}: {}", company.getCompanyName(), generateAliases(company));
            }
        }

        int checked = 0;
        int debugLogged = 0;

        for (CrawledArticle article : articles) {
            checked++;
            String title = article.getTitle() != null ? article.getTitle() : "";
            String summary = article.getSummary() != null ? article.getSummary() : "";
            
            // Normalize HTML entities and tags
            title = normalizeHtml(title);
            summary = normalizeHtml(summary);

            String combinedText = normalizeText(title + " " + summary);
            List<CompanyMatch> matches = new ArrayList<>();

            for (TrackedCompany company : companies) {
                if (Boolean.FALSE.equals(company.getIsActive())) {
                    continue;
                }

                Set<String> aliases = generateAliases(company);
                String matchedAlias = findMatch(combinedText, aliases);
                if (matchedAlias != null) {
                    companyMatchCounts.put(company.getCompanyName(), companyMatchCounts.get(company.getCompanyName()) + 1);
                    CompanyMatch match = CompanyMatch.builder()
                            .companyId(company.getId())
                            .companyName(company.getCompanyName())
                            .matchReason("Matched text: " + matchedAlias)
                            .matchType(matchedAlias.equalsIgnoreCase(company.getCompanyName()) ? "EXACT" : "ALIAS")
                            .confidenceScore(0.8)
                            .build();
                    matches.add(match);
                }
            }

            if (!matches.isEmpty()) {
                article.setMatchedCompanies(matches);
                article.setAiProcessingStatus("MATCHED");
                matchedArticles.add(article);
                
                if (log.isDebugEnabled() && debugLogged < 10) {
                    log.debug("MATCH SUCCESS: Article '{}' matches {}", title, matches.get(0).getCompanyName());
                    debugLogged++;
                }
            } else {
                if (log.isDebugEnabled() && debugLogged < 10) {
                    log.debug("NO_MATCH: Article '{}'", title);
                    debugLogged++;
                }
            }
        }
        
        log.info("--- COMPANY MATCHING SUMMARY ---");
        for (TrackedCompany company : companies) {
            log.info("{}: aliases={} checked={} matched={}", 
                     company.getCompanyName(), generateAliases(company).size(), checked, companyMatchCounts.get(company.getCompanyName()));
        }
        log.info("--------------------------------");

        log.info("Crawler matching summary: Fetched/Checked={}, Matched Articles={}, Total relations={}", 
                 checked, matchedArticles.size(), 
                 matchedArticles.stream().mapToInt(a -> a.getMatchedCompanies().size()).sum());
                 
        return matchedArticles;
    }

    private String normalizeHtml(String html) {
        if (html == null) return "";
        // Strip basic HTML tags
        String text = html.replaceAll("<[^>]+>", " ");
        // Decode common entities (very basic, usually Jsoup handles it but just in case)
        text = text.replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
        return text;
    }

    private Set<String> generateAliases(TrackedCompany company) {
        Set<String> aliases = new HashSet<>();
        if (company.getCompanyName() != null && !company.getCompanyName().isBlank()) {
            aliases.add(company.getCompanyName().trim());
        }
        if (company.getAliases() != null) {
            for (String a : company.getAliases()) {
                if (a != null && !a.isBlank()) {
                    aliases.add(a.trim());
                }
            }
        }
        
        Set<String> derived = new HashSet<>();
        for (String baseAlias : aliases) {
            String derivedName = baseAlias;
            String lowerName = derivedName.toLowerCase(Locale.ROOT);
            boolean changed = true;
            
            while(changed) {
                changed = false;
                for (String prefix : COMPANY_PREFIXES) {
                    if (lowerName.startsWith(prefix) && lowerName.length() > prefix.length() + 2) {
                        derivedName = derivedName.substring(prefix.length()).trim();
                        lowerName = derivedName.toLowerCase(Locale.ROOT);
                        changed = true;
                        break;
                    }
                }
                for (String suffix : COMPANY_SUFFIXES) {
                    if (lowerName.endsWith(suffix) && lowerName.length() > suffix.length() + 2) {
                        derivedName = derivedName.substring(0, derivedName.length() - suffix.length()).trim();
                        lowerName = derivedName.toLowerCase(Locale.ROOT);
                        changed = true;
                        break;
                    }
                }
                for (String suffix : BRAND_SUFFIXES) {
                    if (lowerName.endsWith(suffix) && lowerName.length() > suffix.length() + 2) {
                        derivedName = derivedName.substring(0, derivedName.length() - suffix.length()).trim();
                        lowerName = derivedName.toLowerCase(Locale.ROOT);
                        changed = true;
                        break;
                    }
                }
            }
            
            if (!derivedName.equalsIgnoreCase(baseAlias)) {
                derived.add(derivedName);
                
                // Safe acronym generation
                String[] words = derivedName.split("\\s+");
                if (words.length >= 3) {
                    StringBuilder acronym = new StringBuilder();
                    for (String word : words) {
                        if (word.length() > 0 && Character.isUpperCase(word.charAt(0))) {
                            acronym.append(word.charAt(0));
                        }
                    }
                    if (acronym.length() >= 3 && acronym.length() == words.length) {
                        derived.add(acronym.toString());
                    }
                }
            }
        }
        aliases.addAll(derived);

        if (company.getSubsidiaries() != null) {
            for (String s : company.getSubsidiaries()) {
                if (s != null && !s.isBlank()) {
                    aliases.add(s.trim());
                }
            }
        }
        return aliases;
    }

    private String findMatch(String combinedText, Set<String> aliases) {
        for (String alias : aliases) {
            if (isMatch(combinedText, alias)) {
                return alias;
            }
        }
        return null;
    }

    private boolean isMatch(String text, String keyword) {
        if (keyword == null || keyword.isBlank()) return false;
        String normalizedKeyword = normalizeText(keyword);
        
        if (normalizedKeyword.length() <= 5) {
            // Use word boundary for short aliases like "FPT"
            Pattern pattern = Pattern.compile("\\b" + Pattern.quote(normalizedKeyword) + "\\b");
            return pattern.matcher(text).find();
        } else {
            return text.contains(normalizedKeyword);
        }
    }

    private String normalizeText(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }
}
