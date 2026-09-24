package com.apms.domain.profile;

import com.apms.domain.ai.dto.ExtractedCompanyData;
import com.apms.domain.ai.dto.RawExtractionOutput;
import com.apms.domain.ai.service.provider.AiExtractionResponseMapper;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.profile.dto.AdminEnterpriseProductRequest;
import com.apms.domain.profile.dto.UpdateOwnerCompanyProfileRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProductBackwardCompatibilityTest {

    private ObjectMapper objectMapper;
    private AiExtractionResponseMapper aiExtractionResponseMapper;
    private MappingMongoConverter mongoConverter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        aiExtractionResponseMapper = new AiExtractionResponseMapper(objectMapper);

        org.springframework.data.mongodb.core.convert.MongoCustomConversions conversions =
                new org.springframework.data.mongodb.core.convert.MongoCustomConversions(List.of());

        MongoMappingContext mappingContext = new MongoMappingContext();
        mappingContext.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        mappingContext.afterPropertiesSet();

        mongoConverter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, mappingContext);
        mongoConverter.setCustomConversions(conversions);
        mongoConverter.afterPropertiesSet();
    }

    @Test
    @DisplayName("Stage 1: AI extraction response mapping trims, discards blank/null, deduplicates case-insensitively, and ignores legacy category/description")
    void testAiExtractionResponseMapper_ProductNormalization() throws Exception {
        String aiResponseJson = """
            {
              "products": {
                "value": [
                  {
                    "name": "  Gel pens  ",
                    "category": "Writing Instruments",
                    "description": "Smooth writing gel pens"
                  },
                  {
                    "name": "gel pens",
                    "category": "Different Category",
                    "description": "Duplicate should be removed"
                  },
                  {
                    "name": "   ",
                    "category": "Invalid",
                    "description": "Blank name must be dropped"
                  },
                  {
                    "name": null,
                    "category": "Invalid",
                    "description": "Null name must be dropped"
                  },
                  {
                    "name": "Ballpoint pens"
                  }
                ],
                "confidence": 0.95,
                "evidenceText": "Company makes Gel pens and Ballpoint pens"
              },
              "industries": {
                "value": ["Stationery & Consumer Goods"],
                "confidence": 0.9
              }
            }
            """;

        RawExtractionOutput output = aiExtractionResponseMapper.mapResponse(aiResponseJson);

        assertThat(output).isNotNull();
        assertThat(output.getExtractedData()).isNotNull();

        // 1. Check ExtractedCompanyData.products
        List<ExtractedCompanyData.Product> products = output.getExtractedData().getProducts();
        assertThat(products).isNotNull().hasSize(2);
        assertThat(products.get(0).getName()).isEqualTo("Gel pens");
        assertThat(products.get(1).getName()).isEqualTo("Ballpoint pens");

        // 2. Check fieldResults['products'].value
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> productMaps = (List<Map<String, Object>>) output.getFieldResults().get("products").getValue();
        assertThat(productMaps).hasSize(2);
        assertThat(productMaps.get(0)).containsOnly(Map.entry("name", "Gel pens"));
        assertThat(productMaps.get(1)).containsOnly(Map.entry("name", "Ballpoint pens"));

        // 3. Confirm company industries is completely independent
        assertThat(output.getExtractedData().getIndustries()).containsExactly("Stationery & Consumer Goods");
    }

    @Test
    @DisplayName("Stage 2: ExtractedCompanyData safely deserializes legacy JSON containing category and description")
    void testExtractedCompanyData_LegacyJsonDeserialization() throws Exception {
        String legacyJson = """
            {
              "legalName": "Thien Long Group",
              "products": [
                {
                  "name": "Ballpoint pens",
                  "category": "Stationery",
                  "description": "Smooth ink ballpoint"
                }
              ]
            }
            """;

        ExtractedCompanyData data = objectMapper.readValue(legacyJson, ExtractedCompanyData.class);

        assertThat(data.getLegalName()).isEqualTo("Thien Long Group");
        assertThat(data.getProducts()).hasSize(1);
        assertThat(data.getProducts().get(0).getName()).isEqualTo("Ballpoint pens");
    }

    @Test
    @DisplayName("Stage 3: CompanyCandidate safely deserializes legacy JSON containing category and description")
    void testCompanyCandidate_LegacyJsonDeserialization() throws Exception {
        String legacyCandidateJson = """
            {
              "business": {
                "industries": ["Manufacturing"],
                "products": [
                  {
                    "name": "Whiteboard markers",
                    "category": "Markers",
                    "description": "Dry erase marker"
                  }
                ]
              }
            }
            """;

        CompanyCandidate candidate = objectMapper.readValue(legacyCandidateJson, CompanyCandidate.class);

        assertThat(candidate.getBusiness()).isNotNull();
        assertThat(candidate.getBusiness().getIndustries()).containsExactly("Manufacturing");
        assertThat(candidate.getBusiness().getProducts()).hasSize(1);
        assertThat(candidate.getBusiness().getProducts().get(0).getName()).isEqualTo("Whiteboard markers");
    }

    @Test
    @DisplayName("Stage 4: Spring Data MongoDB MappingMongoConverter reads legacy Mongo documents containing product.category and product.description without error")
    void testMappingMongoConverter_LegacyMongoDocument() {
        // Build simulated raw BSON Document from existing MongoDB collection
        Document legacyDoc = new Document();
        legacyDoc.put("_id", "profile-legacy-1");
        legacyDoc.put("reviewStatus", "APPROVED");

        Document businessDoc = new Document();
        businessDoc.put("industries", List.of("Office Supplies"));

        Document prod1 = new Document();
        prod1.put("name", "Highlighters");
        prod1.put("category", "Stationery");
        prod1.put("description", "Fluorescent highlighters for office and school");

        Document prod2 = new Document();
        prod2.put("name", "Permanent markers");
        prod2.put("category", "Markers");
        prod2.put("description", "Waterproof permanent markers");

        businessDoc.put("products", List.of(prod1, prod2));
        legacyDoc.put("business", businessDoc);

        // Read using Spring Data MongoDB MappingMongoConverter
        CompanyProfile profile = mongoConverter.read(CompanyProfile.class, legacyDoc);

        assertThat(profile).isNotNull();
        assertThat(profile.getId()).isEqualTo("profile-legacy-1");
        assertThat(profile.getBusiness()).isNotNull();
        assertThat(profile.getBusiness().getIndustries()).containsExactly("Office Supplies");

        List<CompanyProfile.Product> mappedProducts = profile.getBusiness().getProducts();
        assertThat(mappedProducts).hasSize(2);
        assertThat(mappedProducts.get(0).getName()).isEqualTo("Highlighters");
        assertThat(mappedProducts.get(1).getName()).isEqualTo("Permanent markers");
    }

    @Test
    @DisplayName("Stage 5: HTTP Request DTOs deserialize legacy JSON containing category and description without 400 errors")
    void testHttpRequestDtos_LegacyPayloads() throws Exception {
        // 1. AdminEnterpriseProductRequest
        String legacyAdminProductJson = """
            {
              "name": "Cloud Suite",
              "category": "Cloud Computing",
              "description": "Enterprise cloud platform"
            }
            """;

        AdminEnterpriseProductRequest adminRequest = objectMapper.readValue(legacyAdminProductJson, AdminEnterpriseProductRequest.class);
        assertThat(adminRequest.getName()).isEqualTo("Cloud Suite");

        // 2. UpdateOwnerCompanyProfileRequest with products
        String legacyOwnerProfileJson = """
            {
              "legalName": "FPT Corporation",
              "industries": ["Information Technology"],
              "products": [
                {
                  "name": "Software Outsourcing",
                  "category": "IT Services",
                  "description": "Custom development"
                }
              ]
            }
            """;

        UpdateOwnerCompanyProfileRequest ownerRequest = objectMapper.readValue(legacyOwnerProfileJson, UpdateOwnerCompanyProfileRequest.class);
        assertThat(ownerRequest.getLegalName()).isEqualTo("FPT Corporation");
        assertThat(ownerRequest.getIndustries()).containsExactly("Information Technology");
        assertThat(ownerRequest.getProducts()).hasSize(1);
        assertThat(ownerRequest.getProducts().get(0).getName()).isEqualTo("Software Outsourcing");
    }

    @Test
    @DisplayName("Stage 6: Product contract output everywhere is strictly { name: '...' } with independent industries")
    void testProductContract_FinalExpectedJson() throws Exception {
        CompanyProfile profile = CompanyProfile.builder()
                .business(CompanyProfile.Business.builder()
                        .industries(List.of("Stationery & Consumer Goods"))
                        .products(List.of(
                                CompanyProfile.Product.builder().name("Ballpoint pens").build(),
                                CompanyProfile.Product.builder().name("Gel pens").build(),
                                CompanyProfile.Product.builder().name("Crayons").build()
                        ))
                        .build())
                .build();

        String serializedJson = objectMapper.writeValueAsString(profile.getBusiness());

        // Assert JSON does not contain category or description keys for products
        assertThat(serializedJson).doesNotContain("category");
        assertThat(serializedJson).doesNotContain("description");
        assertThat(serializedJson).contains("\"products\":[{\"name\":\"Ballpoint pens\"},{\"name\":\"Gel pens\"},{\"name\":\"Crayons\"}]");
        assertThat(serializedJson).contains("\"industries\":[\"Stationery & Consumer Goods\"]");
    }
}
