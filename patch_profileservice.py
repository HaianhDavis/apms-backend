import re

with open('src/main/java/com/apms/domain/profile/service/ProfileService.java', 'r') as f:
    content = f.read()

new_mappers = """    private CompanyProfile.Identity mapIdentity(CompanyCandidate.Identity i) {
        if (i == null) return null;
        return CompanyProfile.Identity.builder()
                .legalName(i.getLegalName())
                .tradeName(i.getTradeName())
                .taxCode(i.getTaxCode())
                .registrationNumber(i.getRegistrationNumber())
                .build();
    }

    private CompanyProfile.Business mapBusiness(CompanyCandidate.Business b) {
        if (b == null) return null;
        return CompanyProfile.Business.builder()
                .industries(b.getIndustries())
                .businessModel(b.getBusinessModel())
                .products(b.getProducts() != null ? b.getProducts().stream()
                        .map(p -> CompanyProfile.Product.builder()
                                .name(p.getName())
                                .category(p.getCategory())
                                .description(p.getDescription())
                                .build())
                        .toList() : null)
                .markets(b.getMarkets())
                .targetCustomers(b.getTargetCustomers())
                .build();
    }

    private CompanyProfile.CompanySize mapCompanySize(CompanyCandidate.CompanySize s) {
        if (s == null) return null;
        return CompanyProfile.CompanySize.builder()
                .employeeTier(s.getEmployeeTier())
                .employeeCount(s.getEmployeeCount())
                .revenueTier(s.getRevenueTier())
                .build();
    }

    private CompanyProfile.Contact mapContact(CompanyCandidate.Contact c) {
        if (c == null) return null;
        return CompanyProfile.Contact.builder()
                .website(c.getWebsite())
                .emails(c.getEmails())
                .phones(c.getPhones())
                .addresses(c.getAddresses() != null ? c.getAddresses().stream()
                        .map(a -> CompanyProfile.Address.builder()
                                .type(a.getType())
                                .fullAddress(a.getFullAddress())
                                .city(a.getCity())
                                .country(a.getCountry())
                                .build())
                        .toList() : null)
                .build();
    }

    private CompanyProfile.Insights mapInsights(CompanyCandidate.Insights i) {
        if (i == null) return null;
        return CompanyProfile.Insights.builder()
                .strengths(i.getStrengths())
                .weaknesses(i.getWeaknesses())
                .opportunities(i.getOpportunities())
                .threats(i.getThreats())
                .build();
    }"""

pattern = re.compile(r"    private CompanyProfile\.Identity mapIdentity\(CompanyCandidate\.Identity i\) \{.*?(?=\n\})", re.DOTALL)
content = pattern.sub(new_mappers, content)

with open('src/main/java/com/apms/domain/profile/service/ProfileService.java', 'w') as f:
    f.write(content)
