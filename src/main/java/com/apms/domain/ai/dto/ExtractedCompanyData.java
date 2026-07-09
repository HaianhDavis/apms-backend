package com.apms.domain.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
    private String website;
    private List<String> email; // Adding missing fields based on prompt
    private List<String> phone;
    private String address;
    private String headquarters;
    private String description;
    private String companySize;
    private List<String> keyPeople;
    private String notes;
    private List<String> strengths;
    private List<String> weaknesses;
    private List<String> opportunities;
    private List<String> threats;

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
