package com.apms.domain.ai.service.provider;

import com.apms.common.enums.RelationshipType;
import com.apms.domain.ai.dto.ExtractedCompanyData;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MockExtractionProvider implements ExtractionProvider {

    @Override
    public ExtractedCompanyData extract(String sourceText) {
        ExtractedCompanyData.RelationshipSuggestion suggestion = ExtractedCompanyData.RelationshipSuggestion.builder()
                .suggestedType(RelationshipType.POTENTIAL_PARTNER_OF)
                .confidence(0.87)
                .reasoning(List.of("Strong synergy in cloud services", "Complementary target markets"))
                .build();

        return ExtractedCompanyData.builder()
                .legalName("CMC Corporation")
                .tradeName("CMC")
                .taxCode(null)
                .industries(List.of("Technology", "Cloud", "Cybersecurity"))
                .businessModel("B2B Technology Services")
                .products(List.of(
                        ExtractedCompanyData.Product.builder().name("Cloud Services").category("Cloud Computing").description("Enterprise cloud infrastructure and services").build(),
                        ExtractedCompanyData.Product.builder().name("Cybersecurity Services").category("Security").description("End-to-end cybersecurity solutions").build(),
                        ExtractedCompanyData.Product.builder().name("Software Outsourcing").category("IT Services").description("Custom software development outsourcing").build()
                ))
                .markets(List.of("Vietnam", "International"))
                .targetCustomers(List.of("Enterprise", "SME"))
                .employeeTier("UNKNOWN")
                .website("https://www.cmc.com.vn")
                .strengths(List.of("Strong market presence in Vietnam", "Diversified tech portfolio"))
                .weaknesses(List.of("High competition in cloud space"))
                .opportunities(List.of("Growing demand for digital transformation"))
                .threats(List.of("Global tech giants entering local market"))
                .relationshipSuggestion(suggestion)
                .build();
    }
}
