package com.apms.domain.externaldata.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.ExternalDataCategory;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.common.exception.ServiceUnavailableException;
import com.apms.common.security.ProjectSecurityEvaluator;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.externaldata.ExternalDataItem;
import com.apms.domain.externaldata.dto.ExternalDataItemResponse;
import com.apms.domain.externaldata.repository.mongo.ExternalDataRepository;
<<<<<<< HEAD
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.repository.sql.ProjectRepository;
=======
import com.apms.domain.crawler.domain.CrawledArticle;
import com.apms.domain.crawler.domain.CompanyMatch;
>>>>>>> origin/nguyen-feature
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
<<<<<<< HEAD
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
=======
import java.util.ArrayList;
>>>>>>> origin/nguyen-feature
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalDataService {

    /** Sources approved for Vietnamese company-intelligence news collection. */
    private static final List<String> APPROVED_NEWS_SOURCE_DOMAINS = List.of(
            "vietstock.vn", "cafef.vn", "vnexpress.net", "vietnamnet.vn", "vietnamnews.vn"
    );

    private final ExternalDataRepository externalDataRepository;
    private final MongoTemplate mongoTemplate;
    private final AuditLogService auditLogService;
<<<<<<< HEAD
    private final ProjectRepository projectRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final ProjectSecurityEvaluator projectSecurity;
    private final NewsIntelligenceEnrichmentService newsIntelligenceEnrichmentService;
=======
    private final com.apms.domain.crawler.repository.TrackedCompanyRepository trackedCompanyRepository;
>>>>>>> origin/nguyen-feature

    public Page<ExternalDataItemResponse> getExternalData(
            ExternalDataCategory category,
            String keyword,
            String source,
<<<<<<< HEAD
            Long projectId,
=======
            String companyName,
>>>>>>> origin/nguyen-feature
            LocalDateTime fromDate,
            LocalDateTime toDate,
            String sentiment,
            String importance,
            Pageable pageable) {

        Criteria criteria = new Criteria();

<<<<<<< HEAD
        // Every record must remain associated with a company profile. Project ownership
        // is optional because the scheduled owner intelligence crawl covers all approved profiles.
        criteria.and("companyProfileId").exists(true).ne(null);
        applyReadableProjectScope(criteria, projectId);

        if (category != null) {
            criteria.and("category").is(category);
        }
=======
        // category is ignored because CrawledArticle does not have a category field.
>>>>>>> origin/nguyen-feature

        if (StringUtils.hasText(source)) {
            criteria.and("sourceName").is(source);
        }

        if (StringUtils.hasText(companyName)) {
            List<Criteria> orCriterias = new ArrayList<>();
            orCriterias.add(Criteria.where("matchedCompanies.companyName").regex(companyName.trim(), "i"));
            
            trackedCompanyRepository.findByCompanyNameIgnoreCase(companyName.trim()).ifPresent(tc -> {
                if (tc.getAliases() != null) {
                    for (String alias : tc.getAliases()) {
                        orCriterias.add(Criteria.where("matchedCompanies.companyName").regex(alias.trim(), "i"));
                    }
                }
            });
            
            criteria.andOperator(new Criteria().orOperator(orCriterias.toArray(new Criteria[0])));
        }

        if (StringUtils.hasText(keyword)) {
            criteria.orOperator(
                    Criteria.where("title").regex(keyword, "i"),
                    Criteria.where("summary").regex(keyword, "i")
            );
        }

        if (fromDate != null) {
            criteria.and("publishedDate").gte(fromDate.toString());
        }
        if (toDate != null) {
            criteria.andOperator(Criteria.where("publishedDate").lte(toDate.toString()));
        }
        
        if (StringUtils.hasText(sentiment)) {
            criteria.and("sentiment").is(sentiment.toUpperCase());
        }
        
        if (StringUtils.hasText(importance)) {
            criteria.and("priorityLevel").is(importance.toUpperCase());
        }

        Sort sort = pageable.getSort();
        if (sort.getOrderFor("publishedAt") != null) {
            Sort.Order order = sort.getOrderFor("publishedAt");
            sort = Sort.by(order.getDirection(), "publishedDate");
            pageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
        }

        Query query = new Query(criteria);
        long total = mongoTemplate.count(query, CrawledArticle.class, "crawled_articles");
        query.with(pageable);
        List<CrawledArticle> items = mongoTemplate.find(query, CrawledArticle.class, "crawled_articles");

        List<ExternalDataItemResponse> responses = items.stream().map(this::crawledToResponse).collect(Collectors.toList());
        return new PageImpl<>(responses, pageable, total);
    }

<<<<<<< HEAD
    public String fetch(Long projectId) {
        ProjectCompanyScope scope = requireProjectCompanyScope(projectId);
=======
    public ExternalDataItemResponse getExternalDataById(String id) {
        CrawledArticle article = mongoTemplate.findById(id, CrawledArticle.class, "crawled_articles");
        if (article != null) {
            return crawledToResponse(article);
        }
        return null;
    }

    public String simulateFetch() {
        ExternalDataItem demoNews = ExternalDataItem.builder()
                .title("Tech Corp announces new strategic AI partnership")
                .summary("Tech Corp is expanding its operations into the AI space with a new partnership.")
                .source("TechCrunch Demo")
                .url("https://example.com/demo-news")
                .imageUrl("https://example.com/images/demo.jpg")
                .publishedAt(LocalDateTime.now())
                .category(ExternalDataCategory.NEWS)
                .build();
>>>>>>> origin/nguyen-feature

        int saved = crawlGoogleNews(projectId, scope);

        Long currentUserId = getCurrentUserId();
        if (currentUserId != null) {
            auditLogService.log(currentUserId, AuditAction.EXTERNAL_DATA_FETCHED, "Project", String.valueOf(projectId),
                    "Crawler scope prepared for " + scope.companyName());
        }

        return "Fetched " + saved + " new article(s) for " + scope.companyName() + ".";
    }

    public String fetchAllApprovedProfiles() {
        List<CompanyProfile> profiles = companyProfileRepository.findAll().stream()
                .filter(profile -> !Boolean.TRUE.equals(profile.getIsDeleted()))
                .toList();

        int saved = 0;
        int scanned = 0;
        for (CompanyProfile profile : profiles) {
            List<String> searchTerms = companySearchTerms(profile);
            if (searchTerms.isEmpty()) continue;
            scanned++;
            try {
                String displayName = companyName(profile);
                for (String searchTerm : searchTerms) {
                    saved += crawlGoogleNews(null, profile, searchTerm, displayName);
                }
            } catch (Exception ex) {
                log.warn("News crawl failed for company profile {}", profile.getId(), ex);
            }
        }

        Long currentUserId = getCurrentUserId();
        if (currentUserId != null) {
            auditLogService.log(currentUserId, AuditAction.EXTERNAL_DATA_FETCHED, "CompanyProfile", "ALL",
                    "Crawler refreshed " + scanned + " company profiles");
        }
        return "Scanned " + scanned + " company profile(s); saved " + saved + " new article(s).";
    }

    @Scheduled(fixedDelayString = "${apms.external-data.profile-crawl-delay:PT6H}", initialDelayString = "${apms.external-data.profile-crawl-initial-delay:PT1M}")
    public void refreshApprovedCompanyNews() {
        String result = fetchAllApprovedProfiles();
        log.info("Scheduled company news refresh completed: {}", result);
    }

    private int crawlGoogleNews(Long projectId, ProjectCompanyScope scope) {
        return crawlGoogleNews(projectId, scope.profile(), scope.companyName(), scope.companyName());
    }

    private int crawlGoogleNews(Long projectId, CompanyProfile profile, String companyName) {
        return crawlGoogleNews(projectId, profile, companyName, companyName);
    }

    private int crawlGoogleNews(Long projectId, CompanyProfile profile, String searchTerm, String companyName) {
        String sourceFilter = APPROVED_NEWS_SOURCE_DOMAINS.stream()
                .map(domain -> "site:" + domain)
                .collect(Collectors.joining(" OR ", "(", ")"));
        String query = URLEncoder.encode('"' + searchTerm + '"' + " " + sourceFilter, StandardCharsets.UTF_8);
        URI uri = URI.create("https://news.google.com/rss/search?q=" + query + "&hl=en-US&gl=US&ceid=US:en");
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("User-Agent", "APMS-Company-Intelligence/1.0")
                    .GET().build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ServiceUnavailableException("News source returned HTTP " + response.statusCode());
            }

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document document = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                    response.body().getBytes(StandardCharsets.UTF_8)));
            NodeList nodes = document.getElementsByTagName("item");
            int saved = 0;
            for (int i = 0; i < nodes.getLength() && saved < 50; i++) {
                Node item = nodes.item(i);
                String title = childText(item, "title");
                String url = childText(item, "link");
                String source = childText(item, "source");
                if (!StringUtils.hasText(title) || !StringUtils.hasText(url)
                        || externalDataRepository.existsByCompanyProfileIdAndUrl(profile.getId(), url)) continue;
                if (!isApprovedNewsSource(source, url)) continue;
                String sourceName = StringUtils.hasText(source) ? source.trim() : sourceFromUrl(url);
                ExternalDataItem article = ExternalDataItem.builder()
                        .title(cleanHtml(title))
                        .summary(cleanHtml(childText(item, "description")))
                        .source(cleanHtml(sourceName))
                        .url(url.trim())
                        .publishedAt(parseRssDate(childText(item, "pubDate")))
                        .category(ExternalDataCategory.NEWS)
                        .relatedCompanyName(companyName)
                        .relatedCompanyId(profile.getCompanyId())
                        .projectId(projectId)
                        .companyProfileId(profile.getId())
                        .build();
                enrichArticle(article);
                externalDataRepository.save(article);
                saved++;
            }
            return saved;
        } catch (ServiceUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Google News crawl failed for company profile {}", profile.getId(), ex);
            throw new ServiceUnavailableException("Unable to fetch news for this company right now.");
        }
    }

    private String childText(Node parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (name.equals(child.getNodeName())) return child.getTextContent();
        }
        return null;
    }

    /** Store plain text at ingestion so no API consumer receives RSS presentation markup. */
    private String cleanHtml(String value) {
        if (!StringUtils.hasText(value)) return null;
        String withoutUnsafeMarkup = value
                .replaceAll("(?is)<(script|style|iframe|object|embed|form)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>|</p>|</div>|</li>", " ")
                .replaceAll("(?is)<[^>]+>", " ");
        return HtmlUtils.htmlUnescape(withoutUnsafeMarkup).replaceAll("\\s+", " ").trim();
    }

    private boolean isApprovedNewsSource(String source, String url) {
        String sourceValue = source == null ? "" : source.toLowerCase();
        String urlValue = url == null ? "" : url.toLowerCase();
        return APPROVED_NEWS_SOURCE_DOMAINS.stream()
                .anyMatch(domain -> sourceValue.contains(domain)
                        || sourceValue.contains(domain.replace(".vn", ""))
                        || urlValue.contains(domain));
    }

    private String sourceFromUrl(String value) {
        try {
            return URI.create(value).getHost();
        } catch (IllegalArgumentException ex) {
            return "Approved news source";
        }
    }

    private LocalDateTime parseRssDate(String value) {
        if (!StringUtils.hasText(value)) return LocalDateTime.now();
        try { return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDateTime(); }
        catch (DateTimeParseException ex) { return LocalDateTime.now(); }
    }

    public String analyze(Long projectId) {
        ProjectCompanyScope scope = requireProjectCompanyScope(projectId);
        List<ExternalDataItem> allNews = externalDataRepository.findByProjectId(projectId);

        for (ExternalDataItem item : allNews) {
            enrichArticle(item);
            externalDataRepository.save(item);
        }

        Long currentUserId = getCurrentUserId();
        if (currentUserId != null) {
            auditLogService.log(currentUserId, AuditAction.EXTERNAL_DATA_ANALYZED, "Project", String.valueOf(projectId),
                    "Analyzed external data for " + scope.companyName());
        }

        return "Analyzed " + allNews.size() + " article(s) for " + scope.companyName() + ".";
    }

    private ExternalDataItemResponse toResponse(ExternalDataItem item) {
        return ExternalDataItemResponse.builder()
                .id(item.getId())
                .title(item.getTitle())
                .summary(item.getSummary())
                .source(item.getSource())
                .url(item.getUrl())
                .publishedAt(item.getPublishedAt())
                .category(item.getCategory())
                .sentiment(item.getSentiment())
                .riskLevel(item.getRiskLevel())
                .opportunityLevel(item.getOpportunityLevel())
                .aiSummary(item.getAiSummary())
                .topics(item.getTopics())
                .relatedCompanyName(item.getRelatedCompanyName())
                .relatedCompanyId(item.getRelatedCompanyId())
                .projectId(item.getProjectId())
                .companyProfileId(item.getCompanyProfileId())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .imageUrl(item.getImageUrl())
                .build();
    }

    private ExternalDataItemResponse crawledToResponse(CrawledArticle item) {
        LocalDateTime pubDate = null;
        try {
            if (item.getPublishedDate() != null) {
                if (item.getPublishedDate().contains("T")) {
                    pubDate = LocalDateTime.parse(item.getPublishedDate());
                } else {
                    pubDate = LocalDate.parse(item.getPublishedDate()).atStartOfDay();
                }
            }
        } catch (Exception e) {
            // ignore
        }

        String companyName = null;
        if (item.getMatchedCompanies() != null && !item.getMatchedCompanies().isEmpty()) {
            companyName = item.getMatchedCompanies().get(0).getCompanyName();
        }

        return ExternalDataItemResponse.builder()
                .id(item.getId())
                .title(item.getTitle())
                .summary(item.getSummary())
                .aiSummary(item.getAiSummary())
                .content(item.getContent())
                .source(item.getSourceName())
                .url(item.getUrl())
                .publishedAt(pubDate)
                .category(ExternalDataCategory.NEWS)
                .sentiment(item.getSentiment())
                .riskLevel(item.getPriorityLevel())
                .relatedCompanyName(companyName)
                .imageUrl(item.getThumbnail())
                .createdAt(item.getCrawledAt())
                .build();
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl) {
            return ((UserDetailsImpl) auth.getPrincipal()).getId();
        }
        return null;
    }

    private void enrichArticle(ExternalDataItem item) {
        String text = ((item.getTitle() == null ? "" : item.getTitle()) + " "
                + (item.getSummary() == null ? "" : item.getSummary())).toLowerCase(Locale.ROOT);
        List<String> topics = new ArrayList<>();

        if (containsAny(text, "expand", "expansion", "open office", "new branch", "mở rộng", "thâm nhập thị trường")) {
            topics.add("MARKET_EXPANSION");
            item.setOpportunityLevel("HIGH");
        }
        if (containsAny(text, "hiring", "recruit", "recruitment", "tuyển dụng", "tuyển nhân sự")) {
            topics.add("HIRING");
        }
        if (containsAny(text, "funding", "investment", "partnership", "product launch", "ra mắt", "đầu tư", "hợp tác")) {
            topics.add("STRATEGIC_ACTIVITY");
        }
        if (containsAny(text, "investigation", "risk", "failure", "lawsuit", "fine", "probing", "vi phạm", "xử phạt", "rủi ro")) {
            topics.add("THREAT");
            item.setRiskLevel("HIGH");
            item.setSentiment("NEGATIVE");
        } else if (containsAny(text, "growth", "award", "profit", "expanding", "tăng trưởng", "giải thưởng")) {
            item.setSentiment("POSITIVE");
        } else {
            item.setSentiment("NEUTRAL");
        }

        if (topics.isEmpty()) topics.add("COMPANY_NEWS");
        item.setTopics(topics);
        item.setAiSummary(StringUtils.hasText(item.getSummary()) ? item.getSummary() : item.getTitle());
        // Keep every crawled article in NEWS. Risk and opportunity are signals,
        // so the News Center and competitor timeline share one complete feed.
        item.setCategory(ExternalDataCategory.NEWS);

        newsIntelligenceEnrichmentService.analyze(item.getTitle(), item.getSummary()).ifPresent(analysis -> {
            if (StringUtils.hasText(analysis.summary())) item.setAiSummary(analysis.summary());
            item.setSentiment(analysis.sentiment());
            item.setRiskLevel(analysis.riskLevel());
            item.setOpportunityLevel(analysis.opportunityLevel());
            item.setTopics(analysis.topics());
        });
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    private String companyName(CompanyProfile profile) {
        if (profile.getIdentity() == null) return null;
        if (StringUtils.hasText(profile.getIdentity().getLegalName())) return profile.getIdentity().getLegalName().trim();
        if (StringUtils.hasText(profile.getIdentity().getTradeName())) return profile.getIdentity().getTradeName().trim();
        return null;
    }

    private List<String> companySearchTerms(CompanyProfile profile) {
        if (profile.getIdentity() == null) return List.of();
        Set<String> terms = new LinkedHashSet<>();
        addSearchTerm(terms, profile.getIdentity().getTradeName());
        addSearchTerm(terms, profile.getIdentity().getStockTicker());
        addSearchTerm(terms, profile.getIdentity().getLegalName());
        return List.copyOf(terms);
    }

    private void addSearchTerm(Set<String> terms, String value) {
        if (StringUtils.hasText(value) && value.trim().length() >= 2) {
            terms.add(value.trim());
        }
    }

    private void applyReadableProjectScope(Criteria criteria, Long projectId) {
        if (projectId != null) {
            if (!canReadProject(projectId)) {
                throw new org.springframework.security.access.AccessDeniedException("Project access denied");
            }
            criteria.and("projectId").is(projectId);
            return;
        }

        UserDetailsImpl user = getCurrentUser();
        if (user == null) {
            throw new org.springframework.security.access.AccessDeniedException("Authentication required");
        }
        boolean canReadAll = user.getAuthorities().stream().anyMatch(authority ->
                authority.getAuthority().equals("ROLE_BUSINESS_OWNER")
                        || authority.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
        if (!canReadAll) {
            criteria.and("projectId").in(projectRepository.findIdsByMemberAccountId(user.getId()));
        }
    }

    private ProjectCompanyScope requireProjectCompanyScope(Long projectId) {
        if (projectId == null) {
            throw new BusinessValidationException("projectId is required. Create or select a project before running the crawler.");
        }
        if (!canReadProject(projectId)) {
            throw new org.springframework.security.access.AccessDeniedException("Project access denied");
        }

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        if (!StringUtils.hasText(project.getTargetCompanyProfileId())) {
            throw new BusinessValidationException("Project has no target company profile. Create the company scope before running the crawler.");
        }

        CompanyProfile profile = companyProfileRepository.findById(project.getTargetCompanyProfileId())
                .filter(item -> !Boolean.TRUE.equals(item.getIsDeleted()))
                .orElseThrow(() -> new BusinessValidationException("The target company profile for this project does not exist or was deleted."));
        String companyName = profile.getIdentity() == null ? null : profile.getIdentity().getLegalName();
        if (!StringUtils.hasText(companyName)) {
            companyName = project.getTargetCompanyName();
        }
        if (!StringUtils.hasText(companyName)) {
            throw new BusinessValidationException("The target company has no name to use as a crawler search term.");
        }
        return new ProjectCompanyScope(project, profile, companyName.trim());
    }

    private UserDetailsImpl getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof UserDetailsImpl user ? user : null;
    }

    private boolean canReadProject(Long projectId) {
        UserDetailsImpl user = getCurrentUser();
        boolean isAdmin = user != null && user.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
        return isAdmin || projectSecurity.isProjectReadable(projectId);
    }

    private record ProjectCompanyScope(Project project, CompanyProfile profile, String companyName) {
    }
}
