package com.apms.domain.crawler.ai;

import com.apms.domain.crawler.domain.TrackedCompany;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the Gemini AI prompt for company detection.
 * Dynamically includes the current tracked company list with their aliases,
 * subsidiaries, products, and key people for semantic matching.
 */
@Component
public class CompanyDetectionPrompt {

    /**
     * Build the full prompt for company detection.
     *
     * @param title             article title
     * @param content           article content (summary + full text)
     * @param trackedCompanies  current list of tracked companies
     * @return the complete prompt string
     */
    public String buildPrompt(String title, String content, List<TrackedCompany> trackedCompanies) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are a business intelligence analyst. Your task is to determine which tracked companies ");
        sb.append("are mentioned in or semantically related to the following news article.\n\n");

        sb.append("RULES:\n");
        sb.append("1. A company is MENTIONED if its name, alias, subsidiary, product, or key person appears in the article.\n");
        sb.append("2. A company is SEMANTICALLY RELATED if the article discusses a topic, event, or technology ");
        sb.append("closely associated with that company, even without an exact name match.\n");
        sb.append("3. Be precise â€” only match companies with clear relevance. Do NOT guess or stretch connections.\n");
        sb.append("4. One article can match MULTIPLE companies (e.g., partnership announcements).\n");
        sb.append("5. Set confidence between 0.0 and 1.0:\n");
        sb.append("   - 0.9â€“1.0: Company name or alias explicitly mentioned\n");
        sb.append("   - 0.7â€“0.9: Subsidiary, product, or key person mentioned\n");
        sb.append("   - 0.5â€“0.7: Strong semantic association\n");
        sb.append("   - Below 0.5: Do NOT include\n\n");

        // Build tracked companies section
        sb.append("TRACKED COMPANIES:\n");
        for (int i = 0; i < trackedCompanies.size(); i++) {
            TrackedCompany company = trackedCompanies.get(i);
            sb.append(i + 1).append(". ").append(company.getCompanyName());

            List<String> context = new java.util.ArrayList<>();
            if (company.getAliases() != null && !company.getAliases().isEmpty()) {
                context.add("aliases: " + String.join(", ", company.getAliases()));
            }
            if (company.getSubsidiaries() != null && !company.getSubsidiaries().isEmpty()) {
                context.add("subsidiaries: " + String.join(", ", company.getSubsidiaries()));
            }
            if (company.getProducts() != null && !company.getProducts().isEmpty()) {
                context.add("products: " + String.join(", ", company.getProducts()));
            }
            if (company.getKeyPeople() != null && !company.getKeyPeople().isEmpty()) {
                context.add("key people: " + String.join(", ", company.getKeyPeople()));
            }

            if (!context.isEmpty()) {
                sb.append(" (").append(String.join("; ", context)).append(")");
            }
            sb.append("\n");
        }

        sb.append("\nARTICLE:\n");
        sb.append("Title: ").append(title != null ? title : "N/A").append("\n");
        sb.append("Content: ").append(content != null ? content : "N/A").append("\n\n");

        sb.append("RESPONSE FORMAT (strict JSON, no markdown fences):\n");
        sb.append("{\n");
        sb.append("  \"isRelevant\": true/false,\n");
        sb.append("  \"matches\": [\n");
        sb.append("    {\n");
        sb.append("      \"companyName\": \"exact name from tracked list\",\n");
        sb.append("      \"confidence\": 0.95,\n");
        sb.append("      \"reason\": \"brief explanation of why this company matches\"\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n\n");
        sb.append("If no tracked company is relevant, return: {\"isRelevant\": false, \"matches\": []}");

        return sb.toString();
    }
}

