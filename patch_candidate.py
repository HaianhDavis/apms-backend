import re

with open('src/main/java/com/apms/domain/candidate/CompanyCandidate.java', 'r') as f:
    content = f.read()

# Add fields
fields_to_add = """    private RelationshipSuggestion relationshipSuggestion;
    private Lifecycle lifecycle;

    // ─────────────────────────────────────────────────────────────
    // Flexible Data Embedded Documents
"""
content = content.replace("    // ─────────────────────────────────────────────────────────────\n    // Flexible Data Embedded Documents\n", fields_to_add)

# Replace Identity through Insights classes
new_classes = """    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Identity {
        private String legalName;
        private String tradeName;
        private String taxCode;
        private String registrationNumber;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Business {
        private java.util.List<String> industries;
        private String businessModel;
        private java.util.List<Product> products;
        private java.util.List<String> markets;
        private java.util.List<String> targetCustomers;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Product {
        private String name;
        private String category;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompanySize {
        private String employeeTier;
        private Integer employeeCount;
        private String revenueTier;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Contact {
        private String website;
        private java.util.List<String> emails;
        private java.util.List<String> phones;
        private java.util.List<Address> addresses;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Address {
        private String type;
        private String fullAddress;
        private String city;
        private String country;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Insights {
        private java.util.List<String> strengths;
        private java.util.List<String> weaknesses;
        private java.util.List<String> opportunities;
        private java.util.List<String> threats;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelationshipSuggestion {
        private RelationshipType suggestedType;
        private Double confidence;
        private java.util.List<AlternativeRelationship> alternatives;
        private java.util.List<String> reasoning;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AlternativeRelationship {
        private RelationshipType type;
        private Double confidence;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Lifecycle {
        private CandidateStatus status;
        private String convertedCompanyProfileId;
    }"""

pattern = re.compile(r"    @Data\n    @Builder\n    @NoArgsConstructor\n    @AllArgsConstructor\n    public static class Identity \{.*?(?=    @Data\n    @Builder\n    @NoArgsConstructor\n    @AllArgsConstructor\n    public static class Validation \{)", re.DOTALL)
content = pattern.sub(new_classes + "\n\n", content)

with open('src/main/java/com/apms/domain/candidate/CompanyCandidate.java', 'w') as f:
    f.write(content)
