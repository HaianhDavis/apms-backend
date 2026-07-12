package com.apms.domain.score.service;

import com.apms.common.enums.RelationshipType;
import com.apms.domain.company.enums.CompanyRole;
import org.springframework.stereotype.Component;

@Component
public class RelationshipTypeToCompanyRoleMapper {

    public CompanyRole map(RelationshipType relationshipType) {
        if (relationshipType == null) {
            throw new IllegalArgumentException("RelationshipType cannot be null.");
        }
        return switch (relationshipType) {
            case PARTNER_WITH -> CompanyRole.PARTNER;
            case POTENTIAL_PARTNER_OF -> CompanyRole.POTENTIAL_PARTNER;
            case COMPETITOR_OF -> CompanyRole.COMPETITOR;
            case CUSTOMER_OF -> CompanyRole.CUSTOMER;
            case SUPPLIER_OF -> CompanyRole.SUPPLIER;
            default -> throw new IllegalArgumentException("Unsupported relationship type for canonical scoring: " + relationshipType);
        };
    }
}
