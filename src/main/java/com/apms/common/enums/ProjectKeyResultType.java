package com.apms.common.enums;

import lombok.Getter;

@Getter
public enum ProjectKeyResultType {
    BASIC_COMPANY_INFORMATION("Basic Company Information", "Complete required basic company profile information"),
    FINANCIAL_INFORMATION("Financial Information", "Research and verify available financial information"),
    MANAGEMENT_MEMBERS("Management Members", "Research company management/key members"),
    CONTRACT_INFORMATION("Contract Information", "Collect relevant contract information");

    private final String displayName;
    private final String description;

    ProjectKeyResultType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
}
