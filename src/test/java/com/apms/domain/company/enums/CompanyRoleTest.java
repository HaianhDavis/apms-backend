package com.apms.domain.company.enums;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyRoleTest {

    @Test
    void shouldHaveExactlyFiveValuesAndNoOwner() {
        List<CompanyRole> roles = Arrays.asList(CompanyRole.values());

        assertThat(roles).hasSize(5);
        assertThat(roles).containsExactlyInAnyOrder(
                CompanyRole.PARTNER,
                CompanyRole.POTENTIAL_PARTNER,
                CompanyRole.COMPETITOR,
                CompanyRole.CUSTOMER,
                CompanyRole.SUPPLIER
        );

        // Explicitly verify OWNER is not present
        assertThat(Arrays.stream(CompanyRole.values()).map(Enum::name)).doesNotContain("OWNER");
    }
}
