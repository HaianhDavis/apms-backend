package com.apms.domain.project.fieldapproval;

import com.apms.domain.profile.CompanyProfileUpdateProposal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

public class ProfileProposalFieldAccessor {

    private static final Map<String, FieldDefinition<CompanyProfileUpdateProposal>> REGISTRY = new HashMap<>();

    static {
        // identity
        register("identity.legalName", true, false, false, "proposedIdentity", "legalName");
        register("identity.tradeName", false, false, false, "proposedIdentity", "tradeName");
        register("identity.taxCode", false, false, false, "proposedIdentity", "taxCode");
        register("identity.registrationNumber", false, false, false, "proposedIdentity", "registrationNumber");

        // business
        register("business.industries", false, true, false, "proposedBusiness", "industries");
        register("business.businessModel", false, false, false, "proposedBusiness", "businessModel");
        register("business.products", false, true, true, "proposedBusiness", "products");
        register("business.markets", false, true, false, "proposedBusiness", "markets");
        register("business.targetCustomers", false, true, false, "proposedBusiness", "targetCustomers");

        // companySize
        register("companySize.employeeTier", false, false, false, "proposedCompanySize", "employeeTier");
        register("companySize.employeeCount", false, false, false, "proposedCompanySize", "employeeCount");

        // contact
        register("contact.website", false, false, false, "proposedContact", "website");
        register("contact.emails", false, true, false, "proposedContact", "emails");
        register("contact.phones", false, true, false, "proposedContact", "phones");
        register("contact.addresses", false, true, true, "proposedContact", "addresses");

        // insights
        register("insights.strengths", false, true, false, "proposedInsights", "strengths");
        register("insights.weaknesses", false, true, false, "proposedInsights", "weaknesses");
        register("insights.opportunities", false, true, false, "proposedInsights", "opportunities");
        register("insights.threats", false, true, false, "proposedInsights", "threats");
    }

    private static void register(String path, boolean required, boolean collection, boolean ordered,
                                 String mapField, String propertyKey) {
        
        Function<CompanyProfileUpdateProposal, Object> getter = p -> {
            Map<String, Object> map = getMap(p, mapField);
            return map != null ? map.get(propertyKey) : null;
        };
        
        BiConsumer<CompanyProfileUpdateProposal, Object> setter = (p, v) -> {
            Map<String, Object> map = ensureMap(p, mapField);
            if (v == null) {
                map.remove(propertyKey);
            } else {
                map.put(propertyKey, v);
            }
        };

        REGISTRY.put(path, FieldDefinition.<CompanyProfileUpdateProposal>builder()
                .canonicalPath(path)
                .required(required)
                .collection(collection)
                .orderedCollection(ordered)
                .getter(getter)
                .setter(setter)
                .build());
    }

    public static List<FieldDefinition<CompanyProfileUpdateProposal>> getAllDefinitions() {
        return new ArrayList<>(REGISTRY.values());
    }

    public static FieldDefinition<CompanyProfileUpdateProposal> getDefinition(String path) {
        return REGISTRY.get(path);
    }

    private static Map<String, Object> getMap(CompanyProfileUpdateProposal p, String mapField) {
        switch (mapField) {
            case "proposedIdentity": return p.getProposedIdentity();
            case "proposedBusiness": return p.getProposedBusiness();
            case "proposedCompanySize": return p.getProposedCompanySize();
            case "proposedContact": return p.getProposedContact();
            case "proposedInsights": return p.getProposedInsights();
            default: return null;
        }
    }

    private static Map<String, Object> ensureMap(CompanyProfileUpdateProposal p, String mapField) {
        Map<String, Object> map = getMap(p, mapField);
        if (map == null) {
            map = new HashMap<>();
            switch (mapField) {
                case "proposedIdentity": p.setProposedIdentity(map); break;
                case "proposedBusiness": p.setProposedBusiness(map); break;
                case "proposedCompanySize": p.setProposedCompanySize(map); break;
                case "proposedContact": p.setProposedContact(map); break;
                case "proposedInsights": p.setProposedInsights(map); break;
            }
        }
        return map;
    }
}
