package com.apms.domain.project.fieldapproval;

import com.apms.domain.candidate.CompanyCandidate;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

public class CandidateFieldAccessor {

    public static final java.util.Set<String> REVIEWABLE_FIELD_PATHS = java.util.Set.of(
        "identity.tradeName",
        "contact.addresses", "contact.website", "contact.emails", "contact.phones",
        "business.businessModel", "business.industries", "business.foundedYear", "business.companyDescription",
        "business.markets", "business.targetCustomers", "business.products",
        "companySize.employeeCount"
    );

    private static final Map<String, FieldDefinition<CompanyCandidate>> REGISTRY = new HashMap<>();

    static {
        // identity
        register("identity.tradeName", false, false, false,
                c -> c.getIdentity() != null ? c.getIdentity().getTradeName() : null,
                (c, v) -> ensureIdentity(c).setTradeName((String) v));
        // business
        register("business.industries", false, true, false,
                c -> c.getBusiness() != null ? c.getBusiness().getIndustries() : null,
                (c, v) -> ensureBusiness(c).setIndustries((List<String>) v));
        register("business.businessModel", false, false, false,
                c -> c.getBusiness() != null ? c.getBusiness().getBusinessModel() : null,
                (c, v) -> ensureBusiness(c).setBusinessModel((String) v));
        register("business.foundedYear", false, false, false,
                c -> c.getBusiness() != null ? c.getBusiness().getFoundedYear() : null,
                (c, v) -> ensureBusiness(c).setFoundedYear(v instanceof Number ? ((Number) v).intValue() : null));
        register("business.companyDescription", false, false, false,
                c -> c.getBusiness() != null ? c.getBusiness().getCompanyDescription() : null,
                (c, v) -> ensureBusiness(c).setCompanyDescription((String) v));
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
        register("companySize.revenueTier", false, false, false,
                c -> c.getCompanySize() != null ? c.getCompanySize().getRevenueTier() : null,
                (c, v) -> ensureCompanySize(c).setRevenueTier((String) v));

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
        register("contact.addresses", false, true, false,
                c -> {
                    if (c.getContact() == null) {
                        return null;
                    }
                    List<String> list = c.getContact().getEffectiveAddressStrings();
                    return list.isEmpty() ? null : list;
                },
                (c, v) -> {
                    CompanyCandidate.Contact contact = ensureContact(c);
                    List<String> normalized = normalizeAddressList(v);
                    contact.setAddresses(CompanyCandidate.Contact.toAddressObjects(normalized));
                });
        register("contact.address", false, false, false,
                c -> {
                    if (c.getContact() == null) {
                        return null;
                    }
                    List<String> list = c.getContact().getEffectiveAddressStrings();
                    return list.isEmpty() ? null : list.get(0);
                },
                (c, v) -> {
                    CompanyCandidate.Contact contact = ensureContact(c);
                    List<String> normalized = normalizeAddressList(v);
                    contact.setAddresses(CompanyCandidate.Contact.toAddressObjects(normalized));
                });

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

        // analysis
        register("financial", false, false, false,
                CompanyCandidate::getFinancial,
                (c, v) -> c.setFinancial((com.apms.domain.company.model.FinancialInfo) v));
        register("innovation", false, false, false,
                CompanyCandidate::getInnovation,
                (c, v) -> c.setInnovation((com.apms.domain.company.model.InnovationInfo) v));
        register("market", false, false, false,
                CompanyCandidate::getMarket,
                (c, v) -> c.setMarket((com.apms.domain.company.model.MarketInfo) v));
        register("risk", false, false, false,
                CompanyCandidate::getRisk,
                (c, v) -> c.setRisk((com.apms.domain.company.model.RiskInfo) v));
        register("compliance", false, false, false,
                CompanyCandidate::getCompliance,
                (c, v) -> c.setCompliance((com.apms.domain.company.model.ComplianceInfo) v));
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

    public static List<FieldDefinition<CompanyCandidate>> getReviewableDefinitions() {
        return REGISTRY.values().stream()
                .filter(def -> REVIEWABLE_FIELD_PATHS.contains(def.getCanonicalPath()))
                .toList();
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

    public static List<String> normalizeAddressList(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        List<String> rawList = new ArrayList<>();
        if (value instanceof java.util.Collection<?> col) {
            for (Object item : col) {
                if (item != null) {
                    if (item instanceof CompanyCandidate.Address addr) {
                        if (StringUtils.hasText(addr.getFullAddress())) {
                            rawList.add(addr.getFullAddress());
                        }
                    } else {
                        rawList.add(String.valueOf(item));
                    }
                }
            }
        } else if (value instanceof CompanyCandidate.Address addr) {
            if (StringUtils.hasText(addr.getFullAddress())) {
                rawList.add(addr.getFullAddress());
            }
        } else if (value instanceof String s) {
            rawList.add(s);
        }

        List<String> normalized = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String raw : rawList) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String cleaned = raw.trim().replaceAll("\\s+", " ");
            if (cleaned.isEmpty() || "N/A".equalsIgnoreCase(cleaned) || "NA".equalsIgnoreCase(cleaned)) {
                continue;
            }
            if (cleaned.length() > 500) {
                cleaned = cleaned.substring(0, 500).trim();
            }
            String lower = cleaned.toLowerCase(java.util.Locale.ROOT);
            if (seen.add(lower)) {
                normalized.add(cleaned);
            }
        }
        return normalized;
    }
}
