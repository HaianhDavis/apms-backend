package com.apms.domain.project.fieldapproval;

import com.apms.domain.candidate.CompanyCandidate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

public class CandidateFieldAccessor {

    private static final Map<String, FieldDefinition<CompanyCandidate>> REGISTRY = new HashMap<>();

    static {
        // identity
        register("identity.legalName", true, false, false,
                c -> c.getIdentity() != null ? c.getIdentity().getLegalName() : null,
                (c, v) -> ensureIdentity(c).setLegalName((String) v));
        register("identity.tradeName", false, false, false,
                c -> c.getIdentity() != null ? c.getIdentity().getTradeName() : null,
                (c, v) -> ensureIdentity(c).setTradeName((String) v));
        register("identity.taxCode", false, false, false,
                c -> c.getIdentity() != null ? c.getIdentity().getTaxCode() : null,
                (c, v) -> ensureIdentity(c).setTaxCode((String) v));
        register("identity.registrationNumber", false, false, false,
                c -> c.getIdentity() != null ? c.getIdentity().getRegistrationNumber() : null,
                (c, v) -> ensureIdentity(c).setRegistrationNumber((String) v));

        // business
        register("business.industries", false, true, false,
                c -> c.getBusiness() != null ? c.getBusiness().getIndustries() : null,
                (c, v) -> ensureBusiness(c).setIndustries((List<String>) v));
        register("business.businessModel", false, false, false,
                c -> c.getBusiness() != null ? c.getBusiness().getBusinessModel() : null,
                (c, v) -> ensureBusiness(c).setBusinessModel((String) v));
        register("business.products", false, true, true,
                c -> c.getBusiness() != null ? c.getBusiness().getProducts() : null,
                (c, v) -> ensureBusiness(c).setProducts((List<CompanyCandidate.Product>) v));
        register("business.markets", false, true, false,
                c -> c.getBusiness() != null ? c.getBusiness().getMarkets() : null,
                (c, v) -> ensureBusiness(c).setMarkets((List<String>) v));
        register("business.targetCustomers", false, true, false,
                c -> c.getBusiness() != null ? c.getBusiness().getTargetCustomers() : null,
                (c, v) -> ensureBusiness(c).setTargetCustomers((List<String>) v));

        // companySize
        register("companySize.employeeTier", false, false, false,
                c -> c.getCompanySize() != null ? c.getCompanySize().getEmployeeTier() : null,
                (c, v) -> ensureCompanySize(c).setEmployeeTier((String) v));
        register("companySize.employeeCount", false, false, false,
                c -> c.getCompanySize() != null ? c.getCompanySize().getEmployeeCount() : null,
                (c, v) -> ensureCompanySize(c).setEmployeeCount((Integer) v));

        // contact
        register("contact.website", false, false, false,
                c -> c.getContact() != null ? c.getContact().getWebsite() : null,
                (c, v) -> ensureContact(c).setWebsite((String) v));
        register("contact.emails", false, true, false,
                c -> c.getContact() != null ? c.getContact().getEmails() : null,
                (c, v) -> ensureContact(c).setEmails((List<String>) v));
        register("contact.phones", false, true, false,
                c -> c.getContact() != null ? c.getContact().getPhones() : null,
                (c, v) -> ensureContact(c).setPhones((List<String>) v));
        register("contact.addresses", false, true, true,
                c -> c.getContact() != null ? c.getContact().getAddresses() : null,
                (c, v) -> ensureContact(c).setAddresses((List<CompanyCandidate.Address>) v));

        // insights
        register("insights.strengths", false, true, false,
                c -> c.getInsights() != null ? c.getInsights().getStrengths() : null,
                (c, v) -> ensureInsights(c).setStrengths((List<String>) v));
        register("insights.weaknesses", false, true, false,
                c -> c.getInsights() != null ? c.getInsights().getWeaknesses() : null,
                (c, v) -> ensureInsights(c).setWeaknesses((List<String>) v));
        register("insights.opportunities", false, true, false,
                c -> c.getInsights() != null ? c.getInsights().getOpportunities() : null,
                (c, v) -> ensureInsights(c).setOpportunities((List<String>) v));
        register("insights.threats", false, true, false,
                c -> c.getInsights() != null ? c.getInsights().getThreats() : null,
                (c, v) -> ensureInsights(c).setThreats((List<String>) v));
    }

    private static void register(String path, boolean required, boolean collection, boolean ordered,
                                 Function<CompanyCandidate, Object> getter,
                                 BiConsumer<CompanyCandidate, Object> setter) {
        REGISTRY.put(path, FieldDefinition.<CompanyCandidate>builder()
                .canonicalPath(path)
                .required(required)
                .collection(collection)
                .orderedCollection(ordered)
                .getter(getter)
                .setter(setter)
                .build());
    }

    public static List<FieldDefinition<CompanyCandidate>> getAllDefinitions() {
        return new ArrayList<>(REGISTRY.values());
    }

    public static FieldDefinition<CompanyCandidate> getDefinition(String path) {
        return REGISTRY.get(path);
    }

    private static CompanyCandidate.Identity ensureIdentity(CompanyCandidate c) {
        if (c.getIdentity() == null) c.setIdentity(new CompanyCandidate.Identity());
        return c.getIdentity();
    }

    private static CompanyCandidate.Business ensureBusiness(CompanyCandidate c) {
        if (c.getBusiness() == null) c.setBusiness(new CompanyCandidate.Business());
        return c.getBusiness();
    }

    private static CompanyCandidate.CompanySize ensureCompanySize(CompanyCandidate c) {
        if (c.getCompanySize() == null) c.setCompanySize(new CompanyCandidate.CompanySize());
        return c.getCompanySize();
    }

    private static CompanyCandidate.Contact ensureContact(CompanyCandidate c) {
        if (c.getContact() == null) c.setContact(new CompanyCandidate.Contact());
        return c.getContact();
    }

    private static CompanyCandidate.Insights ensureInsights(CompanyCandidate c) {
        if (c.getInsights() == null) c.setInsights(new CompanyCandidate.Insights());
        return c.getInsights();
    }
}
