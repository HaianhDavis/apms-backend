package com.apms.domain.score.service;

import com.apms.common.enums.RelationshipType;
import com.apms.domain.company.enums.CompanyRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelationshipTypeToCompanyRoleMapperTest {

    private final RelationshipTypeToCompanyRoleMapper mapper = new RelationshipTypeToCompanyRoleMapper();

    @Test
    void shouldMapPartnerWith() {
        assertThat(mapper.map(RelationshipType.PARTNER_WITH)).isEqualTo(CompanyRole.PARTNER);
    }

    @Test
    void shouldMapPotentialPartnerOf() {
        assertThat(mapper.map(RelationshipType.POTENTIAL_PARTNER_OF)).isEqualTo(CompanyRole.POTENTIAL_PARTNER);
    }

    @Test
    void shouldMapCompetitorOf() {
        assertThat(mapper.map(RelationshipType.COMPETITOR_OF)).isEqualTo(CompanyRole.COMPETITOR);
    }

    @Test
    void shouldMapCustomerOf() {
        assertThat(mapper.map(RelationshipType.CUSTOMER_OF)).isEqualTo(CompanyRole.CUSTOMER);
    }

    @Test
    void shouldMapSupplierOf() {
        assertThat(mapper.map(RelationshipType.SUPPLIER_OF)).isEqualTo(CompanyRole.SUPPLIER);
    }

    @Test
    void shouldRejectNull() {
        assertThatThrownBy(() -> mapper.map(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RelationshipType cannot be null");
    }
}
