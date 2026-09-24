package com.apms.domain.profile.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOwnerEnterpriseRequest {

    @NotBlank(message = "Legal Name is required")
    private String legalName;

    @NotBlank(message = "Trade Name is required")
    private String tradeName;

    @NotBlank(message = "Tax Code is required")
    private String taxCode;

    private String registrationNumber;
    private String stockTicker;
    private String stockExchange;

    private String website;
    private List<String> emails;
    private List<String> phones;
    private List<String> addresses;
    private String address;

    private Integer employeeCount;
    private String employeeTier;
    private String revenueTier;
    private Integer foundedYear;

    private String companyDescription;
    private String businessModel;

    private List<String> industries;
    private List<String> products;
    private List<String> markets;
    private List<String> targetCustomers;
}
