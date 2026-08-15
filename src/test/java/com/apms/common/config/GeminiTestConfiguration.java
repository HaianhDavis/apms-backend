//package com.apms.common.config;
//
//import com.apms.domain.ai.service.provider.PartnerAiPromptProvider;
//import com.apms.domain.ai.service.provider.PartnerCriterionSuggestionProvider;
//import org.mockito.Mockito;
//import org.springframework.boot.test.context.TestConfiguration;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Primary;
//import org.springframework.context.annotation.Profile;
//
//@TestConfiguration
//public class GeminiTestConfiguration {
//
//    @Bean
//    @Primary
//    public PartnerCriterionSuggestionProvider partnerCriterionSuggestionProviderMock() {
//        PartnerCriterionSuggestionProvider mock = Mockito.mock(PartnerCriterionSuggestionProvider.class);
//        Mockito.when(mock.generateSuggestionJson(Mockito.any(), Mockito.anyString()))
//               .thenAnswer(invocation -> {
//                   String requestedKey = invocation.getArgument(1);
//                   return "{\n" +
//                          "  \"criterionKey\": \"" + requestedKey + "\",\n" +
//                          "  \"rationale\": \"test rationale\",\n" +
//                          "  \"missingDataNotes\": \"test notes\",\n" +
//                          "  \"confidence\": 0.9,\n" +
//                          "  \"evidenceReferenceIds\": [\"doc1\"]\n" +
//                          "}";
//               });
//        return mock;
//    }
//
//    @Bean
//    @Primary
//    public PartnerAiPromptProvider partnerAiPromptProviderMock() {
//        PartnerAiPromptProvider mock = Mockito.mock(PartnerAiPromptProvider.class);
//        Mockito.when(mock.getPromptTemplate(Mockito.anyString())).thenReturn("test prompt template");
//        return mock;
//    }
//}
