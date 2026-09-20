package com.apms.domain.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Raw data structure produced by AI extraction from uploaded documents.
 *
 * <h3>Semantic Boundary: AI Output vs Authoritative Data</h3>
 * <p>All fields in this DTO are <b>AI-generated extraction output</b> and are not
 * authoritative business data. This data is used to populate a
 * {@code CompanyCandidate} for human review. It becomes authoritative only after
 * the candidate review/approval workflow promotes it into {@code CompanyProfile}.</p>
 * <p>SWOT fields ({@code strengths}, {@code weaknesses}, {@code opportunities},
 * {@code threats}) are advisory AI observations and must not silently overwrite
 * verified factual data in the approved profile.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExtractedCompanyData {

    private String legalName;
    private String tradeName;
    private String taxCode;
    private List<String> industries;
    private String businessModel;
    private List<Product> products;
    private List<String> markets;
    private List<String> targetCustomers;
    private String employeeTier;
    private Integer employeeCount;
    private Integer foundedYear;
    private String companyDescription;
    private String website;
    private List<String> email; // Adding missing fields based on prompt
    private List<String> phone;
    private String address;
    private List<String> addresses;
    private String headquarters;
    private String description;
    private String companySize;
    private List<String> keyPeople;
    private String notes;
    private List<String> strengths;
    private List<String> weaknesses;
    private List<String> opportunities;
    private List<String> threats;

    private com.apms.domain.company.model.FinancialInfo financial;
    private com.apms.domain.company.model.MarketInfo market;
    private com.apms.domain.company.model.InnovationInfo innovation;
    private com.apms.domain.company.model.RiskInfo risk;
    private com.apms.domain.company.model.ComplianceInfo compliance;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Product {
        private String name;
        private String category;
        private String description;
    }
}
