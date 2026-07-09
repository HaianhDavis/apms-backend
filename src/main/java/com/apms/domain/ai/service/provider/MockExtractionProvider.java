package com.apms.domain.ai.service.provider;

import com.apms.domain.ai.dto.ExtractedCompanyData;
import com.apms.domain.ai.dto.ExtractionFieldResult;
import com.apms.domain.ai.dto.RawExtractionOutput;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class MockExtractionProvider implements ExtractionProvider {

    @Override
    public RawExtractionOutput extract(String sourceText) {
        ExtractedCompanyData data = ExtractedCompanyData.builder()
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
                .build();
                
        Map<String, ExtractionFieldResult> fieldResults = new HashMap<>();
        fieldResults.put("legalName", ExtractionFieldResult.builder().fieldName("legalName").value("CMC Corporation").evidenceText("Document states CMC Corporation").build());
        fieldResults.put("tradeName", ExtractionFieldResult.builder().fieldName("tradeName").value("CMC").build());
        fieldResults.put("website", ExtractionFieldResult.builder().fieldName("website").value("https://www.cmc.com.vn").build());

        return RawExtractionOutput.builder()
                .extractedData(data)
                .fieldResults(fieldResults)
                .rawAiOutputString("{}")
                .build();
    }
}
