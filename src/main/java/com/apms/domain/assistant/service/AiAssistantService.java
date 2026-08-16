package com.apms.domain.assistant.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.common.security.ProjectSecurityEvaluator;
import com.apms.domain.assistant.AiChatMessage;
import com.apms.domain.assistant.dto.AiChatRequest;
import com.apms.domain.assistant.dto.AiChatResponse;
import com.apms.domain.assistant.dto.AiNavigationAction;
import com.apms.domain.assistant.dto.AssistantContext;
import com.apms.domain.assistant.repository.mongo.AiChatMessageRepository;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import com.apms.domain.externaldata.repository.mongo.ExternalDataRepository;
import com.apms.domain.externaldata.ExternalDataItem;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.TaskStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates the AI assistant flow:
 *   1. Authenticate & authorise (project membership via ProjectSecurityEvaluator)
 *   2. Build approved context (company profile, graph, score)
 *   3. Call Gemini assistant provider
 *   4. Persist chat message to MongoDB
 *   5. Return AiChatResponse
 *
 * This service NEVER touches raw_documents, ai_extraction_results,
 * or draft/pending/rejected company_candidates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAssistantService {

    private final AssistantContextService contextService;
    private final GeminiAssistantProvider assistantProvider;
    private final AiChatMessageRepository chatMessageRepository;
    private final ProjectSecurityEvaluator projectSecurity;
    private final ProjectRepository projectRepository;
    private final ProjectTaskRepository projectTaskRepository;
    private final ProjectTaskSubmissionRepository projectTaskSubmissionRepository;
    private final ExternalDataRepository externalDataRepository;
    private final CompanyCandidateRepository companyCandidateRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final com.apms.domain.graph.service.GraphService graphService;
    private final com.apms.domain.profile.service.OwnerOrganizationService ownerOrganizationService;

    public AiChatResponse chat(AiChatRequest request) {
        // ── 1. Authentication ─────────────────────────────────────────────────
        UserDetailsImpl currentUser = currentUser();
        if (currentUser == null) {
            throw new BusinessValidationException("User not authenticated.");
        }

        // ── 2. Authorisation — project access ─────────────────────────────────
        if (!projectSecurity.isMemberOrOwner(request.getProjectId())) {
            throw new BusinessValidationException(
                    "Access denied: you do not have access to project " + request.getProjectId());
        }

        // ── 3. Session ID ─────────────────────────────────────────────────────
        String sessionId = StringUtils.hasText(request.getSessionId())
                ? request.getSessionId()
                : UUID.randomUUID().toString();

        // ── 4. Build approved context ─────────────────────────────────────────
        AssistantContext context;
        List<AiNavigationAction> navigationActions = new java.util.ArrayList<>();
        if (isStaff(currentUser) && !isManagerOrOwner(currentUser)) {
            context = buildStaffContext(request, currentUser);
        } else if (isManager(currentUser) && !isOwner(currentUser)) {
            context = buildManagerContext(request, currentUser, navigationActions);
        } else {
            context = contextService.buildContext(
                    request.getProjectId(),
                    request.getCompanyProfileId()
            );
        }

        // ── 5. Generate answer ────────────────────────────────────────────────
        String answer;
        boolean isStaffSimple = isStaff(currentUser) && !isManagerOrOwner(currentUser) &&
            isDeterministicIntent(detectStaffIntent(request.getQuestion()));

        boolean isManagerSimple = isManager(currentUser) && !isOwner(currentUser) &&
            (isDeterministicManagerIntent(detectManagerIntent(request.getQuestion())) ||
             (context != null && context.getContextText().startsWith("DIRECT_ANSWER:")));

        if (isStaffSimple || isManagerSimple) {
            answer = context.getContextText().replaceFirst("^DIRECT_ANSWER:", "").trim();
        } else {
            answer = assistantProvider.answer(request.getQuestion(), context);
        }

        // ── 6. Suggested actions ──────────────────────────────────────────────
        List<String> suggestedActions;
        if (isManager(currentUser) && !isOwner(currentUser)) {
            suggestedActions = buildManagerSuggestedActions(context);
        } else {
            suggestedActions = buildSuggestedActions(context, isStaff(currentUser) && !isManagerOrOwner(currentUser));
        }

        // ── 7. Persist chat message to MongoDB ────────────────────────────────
        List<String> sourceLabels = context.getSources().stream()
                .map(s -> s.getType())
                .distinct()
                .collect(Collectors.toList());

        AiChatMessage message = AiChatMessage.builder()
                .sessionId(sessionId)
                .userId(currentUser.getId())
                .projectId(request.getProjectId())
                .companyProfileId(request.getCompanyProfileId())
                .question(request.getQuestion())
                .answer(answer)
                .sources(sourceLabels)
                .suggestedActions(suggestedActions)
                .navigationActions(navigationActions)
                .createdAt(LocalDateTime.now())
                .build();

        chatMessageRepository.save(message);
        log.info("AI assistant chat saved: sessionId={}, userId={}, projectId={}",
                sessionId, currentUser.getId(), request.getProjectId());

        // ── 8. Return response ─────────────────────────────────────────────────
        return AiChatResponse.builder()
                .sessionId(sessionId)
                .answer(answer)
                .sources(context.getSources())
                .suggestedActions(suggestedActions)
                .navigationActions(navigationActions)
                .build();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private boolean isStaff(UserDetailsImpl user) {
        return user.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_DEVELOPMENT_STAFF"));
    }

    private boolean isManager(UserDetailsImpl user) {
        return user.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_DEVELOPMENT_MANAGER"));
    }

    private boolean isOwner(UserDetailsImpl user) {
        return user.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_OWNER"));
    }

    private boolean isManagerOrOwner(UserDetailsImpl user) {
        return isManager(user) || isOwner(user);
    }

    private enum ManagerIntent {
        MY_PROJECTS, PROJECT_PROGRESS, TASK_OVERVIEW, TEAM_WORKLOAD, OVERDUE_TASKS,
        PENDING_REVIEWS, SUBMISSION_REVIEW, CANDIDATE_REVIEW, RETURNED_WORK,
        NEXT_MANAGEMENT_ACTION, COMPANY_SEARCH, COMPANY_PROFILE, COMPANY_COMPARE,
        COMPANY_RELATIONSHIPS, COMPANY_PUBLIC_NEWS, INTERNAL_NEWS_PROTECTED, OUT_OF_SCOPE
    }

    private boolean isDeterministicManagerIntent(ManagerIntent intent) {
        return intent == ManagerIntent.MY_PROJECTS
            || intent == ManagerIntent.TASK_OVERVIEW
            || intent == ManagerIntent.OVERDUE_TASKS
            || intent == ManagerIntent.PENDING_REVIEWS
            || intent == ManagerIntent.SUBMISSION_REVIEW
            || intent == ManagerIntent.CANDIDATE_REVIEW
            || intent == ManagerIntent.RETURNED_WORK
            || intent == ManagerIntent.INTERNAL_NEWS_PROTECTED
            || intent == ManagerIntent.OUT_OF_SCOPE
            || intent == ManagerIntent.COMPANY_SEARCH;
    }

    private ManagerIntent detectManagerIntent(String question) {
        String lower = question.toLowerCase();

        // 1. INTERNAL_NEWS_PROTECTED MUST BE FIRST
        if (lower.contains("internal news") || lower.contains("internal information")
                || lower.contains("internal report") || lower.contains("internal reports")
                || lower.contains("internal update") || lower.contains("confidential internal")
                || lower.contains("private internal")) {
            return ManagerIntent.INTERNAL_NEWS_PROTECTED;
        }

        // Project/Task Management Intents
        if (lower.contains("projects am i managing") || lower.contains("projects am i responsible")
                || lower.contains("show my projects")) {
            return ManagerIntent.MY_PROJECTS;
        }
        if (lower.contains("project progress") || lower.contains("progress of this project")
                || lower.contains("on track") || lower.contains("work has been completed")) {
            return ManagerIntent.PROJECT_PROGRESS;
        }
        if (lower.contains("tasks in this project") || lower.contains("tasks are currently active")
                || lower.contains("unfinished work")) {
            return ManagerIntent.TASK_OVERVIEW;
        }
        if (lower.contains("team doing") || lower.contains("team's workload") || lower.contains("team workload")
                || lower.contains("most tasks") || lower.contains("have unfinished work")) {
            return ManagerIntent.TEAM_WORKLOAD;
        }
        if (lower.contains("overdue") || lower.contains("close to deadline")
                || lower.contains("deadlines need my attention")) {
            return ManagerIntent.OVERDUE_TASKS;
        }

        // Review Intents
        if (lower.contains("needs my review") || lower.contains("waiting for my review")
                || lower.contains("what should i review")) {
            return ManagerIntent.PENDING_REVIEWS;
        }
        if (lower.contains("submissions are waiting") || lower.contains("pending staff submissions")
                || lower.contains("submissions need my attention") || lower.contains("submissions waiting for review")) {
            return ManagerIntent.SUBMISSION_REVIEW;
        }
        if (lower.contains("candidates are waiting") || lower.contains("candidates i need to review")
                || lower.contains("candidates need attention") || lower.contains("candidates need my review")) {
            return ManagerIntent.CANDIDATE_REVIEW;
        }
        if (lower.contains("returned for revision") || lower.contains("need to be redone")
                || lower.contains("revision requested")) {
            return ManagerIntent.RETURNED_WORK;
        }
        if (lower.contains("focus on next") || lower.contains("review first")
                || lower.contains("attention first") || lower.contains("next management action")) {
            return ManagerIntent.NEXT_MANAGEMENT_ACTION;
        }

        // Company Intelligence Intents
        if (lower.contains("find ") || lower.contains("search for ") || lower.contains("companies operate in")) {
            return ManagerIntent.COMPANY_SEARCH;
        }
        if (lower.contains("compare ") && lower.contains(" and ")) {
            return ManagerIntent.COMPANY_COMPARE;
        }
        if (lower.contains("relationships") || lower.contains("connected to") || lower.contains("connected with")
                || lower.contains("business relationships") || lower.contains("relationship") || lower.contains("related to")
                || lower.contains("partner or competitor") || lower.contains("our partner") || lower.contains("our competitor")) {
            return ManagerIntent.COMPANY_RELATIONSHIPS;
        }
        if (lower.contains("public news") || lower.contains("external updates") || lower.contains("public information")) {
            return ManagerIntent.COMPANY_PUBLIC_NEWS;
        }
        if (lower.contains("what do we know about") || lower.contains("company profile")
                || lower.contains("industry does") || lower.contains("key members") || lower.contains("financial information")) {
            return ManagerIntent.COMPANY_PROFILE;
        }

        return ManagerIntent.OUT_OF_SCOPE;
    }

    private enum StaffIntent {
        MY_PROJECTS, MY_TASKS, TASK_STATUS, TASK_DETAIL, DEADLINE_PRIORITY,
        SUBMISSION_STATUS, RETURNED_WORK, NEXT_ACTION, OUT_OF_SCOPE
    }

    private boolean isDeterministicIntent(StaffIntent intent) {
        return intent != StaffIntent.TASK_DETAIL;
    }

    private String extractRelationshipTargetCompany(String question) {
        String lower = question.toLowerCase().replace("?", "").trim();
        String[] prefixes = {
            "what is the relationship between our company and ",
            "what relationship do we have with ",
            "how are we connected to ",
            "how are we related to ",
            "is ",
            "what relationships does ",
            "which companies are connected to ",
            "show ",
            "our relationship with "
        };

        for (String prefix : prefixes) {
            if (lower.startsWith(prefix)) {
                String target = lower.substring(prefix.length());
                if (prefix.equals("is ")) {
                    return target.replace(" our partner or competitor", "").replace(" our partner", "").replace(" our competitor", "").trim();
                } else if (prefix.equals("what relationships does ")) {
                    return target.replace(" have", "").trim();
                } else if (prefix.equals("show ")) {
                    return target.replace("'s business relationships", "").replace(" business relationships", "").trim();
                }
                return target.trim();
            }
        }

        return lower.replace("relationships", "")
                    .replace("connected to", "")
                    .replace("connected with", "")
                    .replace("related to", "")
                    .replace("relationship with", "")
                    .replace("relationship", "")
                    .replace("our partner", "")
                    .replace("our competitor", "")
                    .replace("partner or competitor", "")
                    .replaceAll("\\b(what|do|we|have|how|are|between|our|company|and|is)\\b", "")
                    .replaceAll("[^a-z0-9\\s]", "")
                    .trim();
    }

    private String extractCompanyKeyword(String question, ManagerIntent intent) {
        if (intent == ManagerIntent.COMPANY_RELATIONSHIPS) {
            return extractRelationshipTargetCompany(question);
        }
        String lower = question.toLowerCase();
        String keyword = lower.replace("find", "").replace("search for", "").replace("what do we know about", "")
                             .replace("company profile", "").replace("compare", "").replace("relationships", "")
                             .replace("connected to", "").replace("public news", "").replace("external updates", "")
                             .replace("show me the", "").replace("summarize the", "").replace("?", "").trim();
        return keyword;
    }

    private StaffIntent detectStaffIntent(String question) {
        String lower = question.toLowerCase();

        if (lower.contains("what should i work on next")
                || lower.contains("what should i work on now")
                || lower.contains("what should i do next")
                || lower.contains("next task")
                || lower.contains("next action")) {
            return StaffIntent.NEXT_ACTION;
        }

        if (lower.contains("returned")
                || lower.contains("rejected")
                || lower.contains("revision")
                || lower.contains("redo")
                || lower.contains("do again")) {
            return StaffIntent.RETURNED_WORK;
        }

        if (lower.contains("submit")
                || lower.contains("submitted")
                || lower.contains("submission")
                || lower.contains("waiting for manager review")
                || lower.contains("pending review")) {
            return StaffIntent.SUBMISSION_STATUS;
        }

        if (lower.contains("deadline")
                || lower.contains("priority")
                || lower.contains("prioritize")
                || lower.contains("closest deadline")) {
            return StaffIntent.DEADLINE_PRIORITY;
        }

        if (lower.contains("task detail")
                || lower.contains("task details")
                || lower.contains("task description")
                || lower.contains("task requirement")
                || lower.contains("task requirements")
                || lower.contains("what does this task ask me to do")
                || lower.contains("what do i need to do for this task")
                || lower.contains("what should i do for this task")
                || lower.contains("what is this task about")) {
            return StaffIntent.TASK_DETAIL;
        }

        if (lower.contains("status")
                || lower.contains("incomplete")
                || lower.contains("todo")
                || lower.contains("in progress")
                || lower.contains("completed")
                || lower.contains("finished")) {
            return StaffIntent.TASK_STATUS;
        }

        if (lower.contains("task")
                || lower.contains("work")) {
            return StaffIntent.MY_TASKS;
        }

        if (lower.contains("project")) {
            return StaffIntent.MY_PROJECTS;
        }

        return StaffIntent.OUT_OF_SCOPE;
    }

    private AssistantContext buildStaffContext(AiChatRequest request, UserDetailsImpl currentUser) {
        StaffIntent intent = detectStaffIntent(request.getQuestion());
        StringBuilder ctxText = new StringBuilder();

        switch (intent) {
            case MY_PROJECTS:
                Page<Project> projects = projectRepository.findByMemberAccountId(currentUser.getId(), PageRequest.of(0, 50));
                if (projects.isEmpty()) {
                    ctxText.append("You are not participating in any active projects.\n");
                } else {
                    ctxText.append("You are currently participating in ").append(projects.getNumberOfElements()).append(" project(s).\n\n");
                    int i = 1;
                    for (Project p : projects) {
                        ctxText.append("Project ").append(i++).append("  \n")
                               .append("Name: ").append(p.getProjectName()).append("  \n")
                               .append("Status: ").append(formatEnum(p.getStatus().name())).append("  \n")
                               .append("Type: ").append(formatEnum(p.getProjectType() != null ? p.getProjectType().name() : null)).append("  \n")
                               .append("Target: ").append(p.getTargetCompanyName() != null ? p.getTargetCompanyName() : "N/A").append("\n\n")
                                .append("Description: ").append(p.getDescription()).append("  \n")
                                .append("Start date: ").append(formatDate(p.getCreatedAt() != null ? p.getCreatedAt().toLocalDate() : null)).append("  \n")
                                .append("Due date: ").append(formatDate(p.getPlannedEndDate() != null ? p.getPlannedEndDate().atStartOfDay().toLocalDate() : null));
                    }
                }
                break;

            case MY_TASKS:
            case TASK_STATUS:
            case TASK_DETAIL:
            case DEADLINE_PRIORITY:
            case NEXT_ACTION:
                Specification<ProjectTask> spec = (root, query, cb) -> cb.equal(root.get("assignedToAccount").get("id"), currentUser.getId());
                List<ProjectTask> tasks = projectTaskRepository.findAll(spec);

                if (intent == StaffIntent.DEADLINE_PRIORITY) {
                    ProjectTask closest = tasks.stream()
                        .filter(t -> t.getDueDate() != null && t.getStatus() != com.apms.common.enums.TaskStatus.DONE && t.getStatus() != com.apms.common.enums.TaskStatus.CANCELLED)
                        .min(java.util.Comparator.comparing(ProjectTask::getDueDate))
                        .orElse(null);
                    if (closest != null) {
                        ctxText.append("Your closest active deadline is:\n\n")
                               .append("Task: ").append(closest.getTitle()).append("  \n")
                               .append("Project: ").append(closest.getProject().getProjectName()).append("  \n")
                               .append("Deadline: ").append(formatDate(closest.getDueDate() != null ? closest.getDueDate().toLocalDate() : null)).append("  \n")
                               .append("Status: ").append(formatEnum(closest.getStatus().name())).append("  \n")
                               .append("Priority: ").append(formatEnum(closest.getPriority() != null ? closest.getPriority().name() : null)).append("\n\n");
                    } else {
                        ctxText.append("You have no active tasks with deadlines.\n");
                    }
                } else if (intent == StaffIntent.NEXT_ACTION) {
                    ProjectTask nextTask = tasks.stream()
                        .filter(t -> t.getStatus() != com.apms.common.enums.TaskStatus.DONE && t.getStatus() != com.apms.common.enums.TaskStatus.CANCELLED && t.getStatus() != com.apms.common.enums.TaskStatus.BLOCKED)
                        .min(java.util.Comparator.comparing(ProjectTask::getPriority)
                            .thenComparing((t1, t2) -> {
                                if (t1.getDueDate() == null && t2.getDueDate() == null) return 0;
                                if (t1.getDueDate() == null) return 1;
                                if (t2.getDueDate() == null) return -1;
                                return t1.getDueDate().compareTo(t2.getDueDate());
                            }))
                        .orElse(null);
                    if (nextTask != null) {
                        ctxText.append("You should work on this task next.\n\n")
                               .append("Task: ").append(nextTask.getTitle()).append("  \n")
                               .append("Project: ").append(nextTask.getProject().getProjectName()).append("  \n")
                               .append("Status: ").append(formatEnum(nextTask.getStatus().name())).append("  \n")
                               .append("Priority: ").append(formatEnum(nextTask.getPriority() != null ? nextTask.getPriority().name() : null)).append("  \n")
                               .append("Deadline: ").append(formatDate(nextTask.getDueDate() != null ? nextTask.getDueDate().toLocalDate() : null)).append("\n\n")
                               .append("Reason: This is your highest-priority executable task with the nearest deadline.\n");
                    } else {
                        ctxText.append("You have no executable tasks found.\n");
                    }
                } else {
                    if (tasks.isEmpty()) {
                        ctxText.append("You have no tasks assigned to you.\n");
                    } else {
                        ctxText.append("You currently have ").append(tasks.size()).append(" assigned task(s).\n\n");
                        int i = 1;
                        for (ProjectTask t : tasks) {
                            if (tasks.size() > 1) ctxText.append("Task ").append(i++).append("  \n");
                            ctxText.append("Task: ").append(t.getTitle()).append("  \n")
                                   .append("Project: ").append(t.getProject().getProjectName()).append("  \n")
                                   .append("Status: ").append(formatEnum(t.getStatus().name())).append("  \n")
                                   .append("Priority: ").append(formatEnum(t.getPriority() != null ? t.getPriority().name() : null)).append("  \n")
                                   .append("Type: ").append(formatEnum(t.getTaskType() != null ? t.getTaskType().name() : null)).append("  \n")
                                   .append("Deadline: ").append(formatDate(t.getDueDate() != null ? t.getDueDate().toLocalDate() : null)).append(intent == StaffIntent.TASK_DETAIL ? "  \n" : "\n\n");
                            if (intent == StaffIntent.TASK_DETAIL && t.getDescription() != null) {
                                ctxText.append("Description: ").append(t.getDescription()).append("\n\n");
                            }
                        }
                    }
                }
                break;

            case SUBMISSION_STATUS:
            case RETURNED_WORK:
                Specification<ProjectTask> specTasks = (root, query, cb) -> cb.equal(root.get("assignedToAccount").get("id"), currentUser.getId());
                List<ProjectTask> userTasks = projectTaskRepository.findAll(specTasks);
                List<Long> taskIds = userTasks.stream().map(ProjectTask::getId).collect(Collectors.toList());

                if (taskIds.isEmpty()) {
                    ctxText.append("You have no tasks, so no submissions.\n");
                } else {
                    Specification<ProjectTaskSubmission> subSpec = (root, query, cb) -> root.get("projectTask").get("id").in(taskIds);
                    List<ProjectTaskSubmission> submissions = projectTaskSubmissionRepository.findAll(subSpec);

                    if (intent == StaffIntent.RETURNED_WORK) {
                        List<ProjectTaskSubmission> returned = submissions.stream()
                            .filter(s -> s.getStatus() == com.apms.common.enums.SubmissionStatus.REVISION_REQUESTED)
                            .collect(Collectors.toList());
                        if (returned.isEmpty()) {
                            ctxText.append("You have no tasks that require revision.\n");
                        } else {
                            ctxText.append("You have ").append(returned.size()).append(" task(s) that require revision.\n\n");
                            for (ProjectTaskSubmission s : returned) {
                                ctxText.append("Task: ").append(s.getProjectTask().getTitle()).append("  \n")
                                       .append("Project: ").append(s.getProjectTask().getProject().getProjectName()).append("  \n")
                                       .append("Status: Returned for Revision\n\n");
                            }
                        }
                    } else {
                        if (submissions.isEmpty()) {
                            ctxText.append("You have no submissions.\n");
                        } else {
                            ctxText.append("Your submission status is:\n\n");
                            for (ProjectTaskSubmission s : submissions) {
                                ctxText.append("Task: ").append(s.getProjectTask().getTitle()).append("  \n")
                                       .append("Status: Submitted  \n")
                                       .append("Review Status: ").append(formatEnum(s.getStatus().name())).append("  \n")
                                       .append("Submitted At: ").append(formatDate(s.getSubmittedAt() != null ? s.getSubmittedAt().toLocalDate() : null)).append("\n\n");
                            }
                        }
                    }
                }
                break;

            case OUT_OF_SCOPE:
            default:
                ctxText.append("I can help you with your APMS projects, assigned tasks, deadlines, submissions, and next actions. The requested information is outside your available Staff workspace scope.\n");
                break;
        }

        return AssistantContext.builder()
                .contextText(ctxText.toString())
                .sources(List.of())
                .build();
    }

    private String formatEnum(String enumName) {
        if (enumName == null) return "None";
        String[] words = enumName.split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            sb.append(word.substring(0, 1).toUpperCase());
            if (word.length() > 1) {
                sb.append(word.substring(1).toLowerCase());
            }
            sb.append(" ");
        }
        return sb.toString().trim();
    }

    private String formatRelationshipType(String type) {
        if (type == null) return "Unknown Relationship";
        switch (type) {
            case "POTENTIAL_PARTNER_OF": return "Potential Partner";
            case "PARTNER_WITH": return "Partner";
            case "COMPETITOR_OF": return "Competitor";
            case "CUSTOMER_OF": return "Customer";
            case "SUPPLIER_OF": return "Supplier";
            default:
                return formatEnum(type);
        }
    }

    private String formatDate(java.time.LocalDate date) {
        if (date == null) return "None";
        return date.format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy", java.util.Locale.ENGLISH));
    }

    private UserDetailsImpl currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserDetailsImpl)) return null;
        return (UserDetailsImpl) auth.getPrincipal();
    }

    private AssistantContext buildManagerContext(AiChatRequest request, UserDetailsImpl currentUser, List<AiNavigationAction> navigationActions) {
        ManagerIntent intent = detectManagerIntent(request.getQuestion());
        StringBuilder ctxText = new StringBuilder();
        List<com.apms.domain.assistant.dto.AiSourceReference> sources = new java.util.ArrayList<>();
        List<CompanyProfile> resolvedProfiles = new java.util.ArrayList<>();

        // Try to resolve approved profile for company intents
        if (intent == ManagerIntent.COMPANY_PROFILE || intent == ManagerIntent.COMPANY_COMPARE ||
            intent == ManagerIntent.COMPANY_RELATIONSHIPS || intent == ManagerIntent.COMPANY_PUBLIC_NEWS ||
            intent == ManagerIntent.COMPANY_SEARCH || intent == ManagerIntent.NEXT_MANAGEMENT_ACTION) {

            if (StringUtils.hasText(request.getCompanyProfileId())) {
                CompanyProfile p = companyProfileRepository.findById(request.getCompanyProfileId()).orElse(null);
                if (p != null && "APPROVED".equals(p.getReviewStatus())) {
                    resolvedProfiles.add(p);
                }
            } else if (intent != ManagerIntent.NEXT_MANAGEMENT_ACTION) {
                String keyword = extractCompanyKeyword(request.getQuestion(), intent);

                if (keyword.length() > 2) {
                    Page<CompanyProfile> profiles = companyProfileRepository.searchByName(keyword, PageRequest.of(0, 10));
                    for (CompanyProfile p : profiles) {
                        if ("APPROVED".equals(p.getReviewStatus())) {
                            resolvedProfiles.add(p);
                            if (intent != ManagerIntent.COMPANY_COMPARE) break;
                            if (resolvedProfiles.size() >= 2) break;
                        }
                    }
                }
            }
        }

        switch (intent) {
            case INTERNAL_NEWS_PROTECTED:
                ctxText.append("Internal News is protected data and is not accessible through the AI Assistant.\nPlease view it directly through the authorized Internal News section.\n");
                break;

            case MY_PROJECTS:
                Page<Project> rawProjects = projectRepository.findByMemberAccountId(currentUser.getId(), PageRequest.of(0, 100));
                List<Project> projects = rawProjects.stream().filter(p -> projectSecurity.isManager(p.getId())).collect(Collectors.toList());
                if (projects.isEmpty()) {
                    ctxText.append("You are not managing any active projects.\n");
                } else {
                    ctxText.append("You are currently managing ").append(projects.size()).append(" project(s).\n\n");
                    int i = 1;
                    for (Project p : projects) {
                        ctxText.append("Project ").append(i++).append("\n")
                               .append("Name: ").append(p.getProjectName()).append("\n")
                               .append("Status: ").append(formatEnum(p.getStatus().name())).append("\n")
                               .append("Type: ").append(formatEnum(p.getProjectType() != null ? p.getProjectType().name() : null)).append("\n")
                               .append("Target: ").append(p.getTargetCompanyName() != null ? p.getTargetCompanyName() : "N/A").append("\n\n");
                    }
                }
                break;

            case PROJECT_PROGRESS:
            case TASK_OVERVIEW:
                List<ProjectTask> managerTasks = new java.util.ArrayList<>();
                if (request.getProjectId() != null) {
                    if (projectSecurity.isManager(request.getProjectId())) {
                        managerTasks = projectTaskRepository.findAll((root, query, cb) -> cb.equal(root.get("project").get("id"), request.getProjectId()));
                    }
                } else {
                    Page<Project> allManagerProjects = projectRepository.findByMemberAccountId(currentUser.getId(), PageRequest.of(0, 100));
                    List<Long> pIds = allManagerProjects.stream().filter(p -> projectSecurity.isManager(p.getId())).map(Project::getId).collect(Collectors.toList());
                    if (!pIds.isEmpty()) {
                        managerTasks = projectTaskRepository.findAll((root, query, cb) -> root.get("project").get("id").in(pIds));
                    }
                }

                if (managerTasks.isEmpty()) {
                    ctxText.append("No tasks found in your managed projects.\n");
                } else {
                    if (intent == ManagerIntent.TASK_OVERVIEW) {
                        ctxText.append("Task Overview:\n\n");
                        for (ProjectTask t : managerTasks) {
                            ctxText.append("Task: ").append(t.getTitle()).append("\n")
                                   .append("Project: ").append(t.getProject().getProjectName()).append("\n")
                                   .append("Status: ").append(formatEnum(t.getStatus().name())).append("\n\n");
                        }
                    } else {
                        long total = managerTasks.size();
                        long done = managerTasks.stream().filter(t -> t.getStatus() == TaskStatus.DONE).count();
                        ctxText.append("Project Progress Facts:\n")
                               .append("Total tasks: ").append(total).append("\n")
                               .append("Completed tasks: ").append(done).append("\n")
                               .append("Progress: ").append(total > 0 ? (done * 100 / total) : 0).append("%\n");
                    }
                }
                break;

            case TEAM_WORKLOAD:
                Page<Project> tmProjects = projectRepository.findByMemberAccountId(currentUser.getId(), PageRequest.of(0, 100));
                List<Long> tmPIds = tmProjects.stream().filter(p -> projectSecurity.isManager(p.getId())).map(Project::getId).collect(Collectors.toList());
                if (tmPIds.isEmpty()) {
                    ctxText.append("No projects found.\n");
                } else {
                    List<ProjectTask> tmTasks = projectTaskRepository.findAll((root, query, cb) -> root.get("project").get("id").in(tmPIds));
                    java.util.Map<String, Long> workload = tmTasks.stream()
                            .filter(t -> t.getAssignedToAccount() != null && t.getStatus() != TaskStatus.DONE && t.getStatus() != TaskStatus.CANCELLED)
                            .collect(Collectors.groupingBy(t -> t.getAssignedToAccount().getEmail(), Collectors.counting()));
                    ctxText.append("Team Workload Facts:\n\n");
                    workload.forEach((name, count) -> ctxText.append(name).append(": ").append(count).append(" active task(s)\n"));
                }
                break;

            case OVERDUE_TASKS:
                Page<Project> odProjects = projectRepository.findByMemberAccountId(currentUser.getId(), PageRequest.of(0, 100));
                List<Long> odPIds = odProjects.stream().filter(p -> projectSecurity.isManager(p.getId())).map(Project::getId).collect(Collectors.toList());
                if (odPIds.isEmpty()) {
                    ctxText.append("No overdue tasks found.\n");
                } else {
                    List<ProjectTask> odTasks = projectTaskRepository.findAll((root, query, cb) -> root.get("project").get("id").in(odPIds));
                    LocalDateTime now = LocalDateTime.now();
                    List<ProjectTask> overdue = odTasks.stream()
                            .filter(t -> t.getDueDate() != null && t.getDueDate().isBefore(now) && t.getStatus() != TaskStatus.DONE && t.getStatus() != TaskStatus.CANCELLED)
                            .collect(Collectors.toList());
                    if (overdue.isEmpty()) {
                        ctxText.append("You have no overdue tasks in your projects.\n");
                    } else {
                        ctxText.append("Overdue Tasks:\n\n");
                        for (ProjectTask t : overdue) {
                            ctxText.append("Task: ").append(t.getTitle()).append("\n")
                                   .append("Project: ").append(t.getProject().getProjectName()).append("\n")
                                   .append("Assigned To: ").append(t.getAssignedToAccount() != null ? t.getAssignedToAccount().getEmail() : "Unassigned").append("\n")
                                   .append("Deadline: ").append(formatDate(t.getDueDate().toLocalDate())).append("\n\n");
                        }
                    }
                }
                break;

            case PENDING_REVIEWS:
            case SUBMISSION_REVIEW:
                Page<Project> prProjects = projectRepository.findByMemberAccountId(currentUser.getId(), PageRequest.of(0, 100));
                List<Long> prPIds = prProjects.stream().filter(p -> projectSecurity.isManager(p.getId())).map(Project::getId).collect(Collectors.toList());
                if (prPIds.isEmpty()) {
                    ctxText.append("You have no pending reviews.\n");
                } else {
                    List<ProjectTaskSubmission> pendingSubs = projectTaskSubmissionRepository.findAll((root, query, cb) -> cb.and(
                            root.get("project").get("id").in(prPIds),
                            cb.equal(root.get("status"), SubmissionStatus.IN_REVIEW)
                    ));
                    if (pendingSubs.isEmpty()) {
                        ctxText.append("You have no submissions waiting for review.\n");
                    } else {
                        ctxText.append("Pending Submissions for Review:\n\n");
                        for (ProjectTaskSubmission s : pendingSubs) {
                            ctxText.append("Task: ").append(s.getProjectTask().getTitle()).append("\n")
                                   .append("Project: ").append(s.getProject().getProjectName()).append("\n")
                                   .append("Submitted By: ").append(s.getSubmittedByAccount() != null ? s.getSubmittedByAccount().getEmail() : "Unknown").append("\n")
                                   .append("Date: ").append(formatDate(s.getSubmittedAt().toLocalDate())).append("\n\n");
                        }
                    }
                }
                break;

            case CANDIDATE_REVIEW:
                Page<Project> crProjects = projectRepository.findByMemberAccountId(currentUser.getId(), PageRequest.of(0, 100));
                List<Long> crPIds = crProjects.stream().filter(p -> projectSecurity.isManager(p.getId())).map(Project::getId).collect(Collectors.toList());
                if (crPIds.isEmpty()) {
                    ctxText.append("You have no candidates to review.\n");
                } else {
                    List<CompanyCandidate> candidates = companyCandidateRepository.findAll().stream()
                            .filter(c -> c.getProjectId() != null && crPIds.contains(Long.parseLong(c.getProjectId())) && "PENDING_REVIEW".equals(c.getStatus()))
                            .collect(Collectors.toList());
                    if (candidates.isEmpty()) {
                        ctxText.append("You have no candidates waiting for review.\n");
                    } else {
                        ctxText.append("Pending Candidates for Review:\n\n");
                        for (CompanyCandidate c : candidates) {
                            ctxText.append("Candidate: ").append(c.getIdentity() != null ? c.getIdentity().getLegalName() : "Unknown").append("\n")
                                   .append("Status: Pending Review\n\n");
                        }
                    }
                }
                break;

            case RETURNED_WORK:
                Page<Project> rwProjects = projectRepository.findByMemberAccountId(currentUser.getId(), PageRequest.of(0, 100));
                List<Long> rwPIds = rwProjects.stream().filter(p -> projectSecurity.isManager(p.getId())).map(Project::getId).collect(Collectors.toList());
                if (rwPIds.isEmpty()) {
                    ctxText.append("No revision requested work found.\n");
                } else {
                    List<ProjectTaskSubmission> returnedSubs = projectTaskSubmissionRepository.findAll((root, query, cb) -> cb.and(
                            root.get("project").get("id").in(rwPIds),
                            cb.equal(root.get("status"), SubmissionStatus.REVISION_REQUESTED)
                    ));
                    if (returnedSubs.isEmpty()) {
                        ctxText.append("No work is currently returned for revision.\n");
                    } else {
                        ctxText.append("Revision Requested Work:\n\n");
                        for (ProjectTaskSubmission s : returnedSubs) {
                            ctxText.append("Task: ").append(s.getProjectTask().getTitle()).append("\n")
                                   .append("Project: ").append(s.getProject().getProjectName()).append("\n")
                                   .append("Assigned To: ").append(s.getProjectTask().getAssignedToAccount() != null ? s.getProjectTask().getAssignedToAccount().getEmail() : "Unassigned").append("\n\n");
                        }
                    }
                }
                break;

            case NEXT_MANAGEMENT_ACTION:
                Page<Project> nxProjects = projectRepository.findByMemberAccountId(currentUser.getId(), PageRequest.of(0, 100));
                List<Long> nxPIds = nxProjects.stream().filter(p -> projectSecurity.isManager(p.getId())).map(Project::getId).collect(Collectors.toList());
                if (nxPIds.isEmpty()) {
                    ctxText.append("You have no active projects requiring attention.\n");
                } else {
                    List<ProjectTask> nxTasks = projectTaskRepository.findAll((root, query, cb) -> root.get("project").get("id").in(nxPIds));
                    long overdueCount = nxTasks.stream().filter(t -> t.getDueDate() != null && t.getDueDate().isBefore(LocalDateTime.now()) && t.getStatus() != TaskStatus.DONE && t.getStatus() != TaskStatus.CANCELLED).count();
                    long pendingSubCount = projectTaskSubmissionRepository.findAll((root, query, cb) -> cb.and(root.get("project").get("id").in(nxPIds), cb.equal(root.get("status"), SubmissionStatus.IN_REVIEW))).size();
                    long pendingCandCount = companyCandidateRepository.findAll().stream().filter(c -> c.getProjectId() != null && nxPIds.contains(Long.parseLong(c.getProjectId())) && "PENDING_REVIEW".equals(c.getStatus())).count();

                    ctxText.append("Management Action Facts:\n")
                           .append("Overdue Tasks: ").append(overdueCount).append("\n")
                           .append("Pending Submissions: ").append(pendingSubCount).append("\n")
                           .append("Pending Candidates: ").append(pendingCandCount).append("\n");
                }
                break;

            case COMPANY_SEARCH:
                String q = extractCompanyKeyword(request.getQuestion(), intent);
                Page<CompanyProfile> searchResults = companyProfileRepository.searchByName(q, PageRequest.of(0, 5));
                List<CompanyProfile> approvedResults = searchResults.stream().filter(p -> "APPROVED".equals(p.getReviewStatus())).collect(Collectors.toList());
                if (approvedResults.isEmpty()) {
                    ctxText.append("No approved companies found matching your search.\n");
                } else {
                    ctxText.append("Approved Companies Found:\n\n");
                    for (CompanyProfile p : approvedResults) {
                        ctxText.append("Company: ").append(p.getIdentity().getLegalName()).append("\n")
                               .append("Industry: ").append(p.getBusiness() != null ? p.getBusiness().getIndustries() : "N/A").append("\n\n");
                        navigationActions.add(com.apms.domain.assistant.dto.AiNavigationAction.builder()
                            .type("COMPANY_PROFILE")
                            .label("View " + (p.getIdentity().getTradeName() != null ? p.getIdentity().getTradeName() : p.getIdentity().getLegalName()) + " Company Profile")
                            .companyProfileId(p.getId())
                            .companyId(p.getCompanyId())
                            .companyName(p.getIdentity().getLegalName())
                            .build());
                    }
                }
                break;

            case COMPANY_PROFILE:
                if (resolvedProfiles.isEmpty()) {
                    ctxText.append("The APMS system does not have enough approved information to answer this question.\n");
                } else {
                    CompanyProfile p = resolvedProfiles.get(0);
                    ctxText.append("Approved Company Profile Facts for ").append(p.getIdentity().getLegalName()).append(":\n")
                           .append("Industries: ").append(p.getBusiness() != null ? p.getBusiness().getIndustries() : "N/A").append("\n")
                           .append("Business Model: ").append(p.getBusiness() != null ? p.getBusiness().getBusinessModel() : "N/A").append("\n");
                    if (p.getFinancial() != null) {
                        ctxText.append("Revenue: ").append(p.getFinancial().getRevenue()).append("\n");
                    }
                    navigationActions.add(com.apms.domain.assistant.dto.AiNavigationAction.builder()
                        .type("COMPANY_PROFILE")
                        .label("View " + (p.getIdentity().getTradeName() != null ? p.getIdentity().getTradeName() : p.getIdentity().getLegalName()) + " Company Profile")
                        .companyProfileId(p.getId())
                        .companyId(p.getCompanyId())
                        .companyName(p.getIdentity().getLegalName())
                        .build());
                }
                break;

            case COMPANY_COMPARE:
                ctxText.append("Company Comparison Facts:\n");
                if (!resolvedProfiles.isEmpty()) {
                    for (CompanyProfile p : resolvedProfiles) {
                        ctxText.append("Company: ").append(p.getIdentity().getLegalName()).append("\n");
                        navigationActions.add(com.apms.domain.assistant.dto.AiNavigationAction.builder()
                            .type("COMPANY_PROFILE")
                            .label("View " + (p.getIdentity().getTradeName() != null ? p.getIdentity().getTradeName() : p.getIdentity().getLegalName()) + " Company Profile")
                            .companyProfileId(p.getId())
                            .companyId(p.getCompanyId())
                            .companyName(p.getIdentity().getLegalName())
                            .build());
                    }
                } else {
                    ctxText.append("Could not resolve approved companies for comparison.\n");
                }
                break;

            case COMPANY_RELATIONSHIPS:
                if (resolvedProfiles.isEmpty()) {
                    ctxText.append("The APMS system does not have enough approved information to answer this question.\n");
                } else {
                    CompanyProfile targetProfile = resolvedProfiles.get(0);
                    navigationActions.add(com.apms.domain.assistant.dto.AiNavigationAction.builder()
                            .type("COMPANY_PROFILE")
                            .label("View " + (targetProfile.getIdentity().getTradeName() != null ? targetProfile.getIdentity().getTradeName() : targetProfile.getIdentity().getLegalName()) + " Company Profile")
                            .companyProfileId(targetProfile.getId())
                            .companyId(targetProfile.getCompanyId())
                            .companyName(targetProfile.getIdentity().getLegalName())
                            .build());

                    String lowerQ = request.getQuestion().toLowerCase();
                    boolean isPairQuery = lowerQ.contains("our company") || lowerQ.contains("we") || lowerQ.contains("us")
                            || lowerQ.contains("our partner") || lowerQ.contains("our competitor");

                    if (isPairQuery) {
                        CompanyProfile ownerProfile = ownerOrganizationService.resolveApprovedOwnerProfile();
                        String referenceCompanyId = ownerProfile.getCompanyId();
                        String targetCompanyId = targetProfile.getCompanyId();

                        List<com.apms.domain.graph.dto.CompanyRelationshipDto> pairRelationships = graphService.getPairRelationships(referenceCompanyId, targetCompanyId);
                        if (pairRelationships.isEmpty()) {
                            ctxText.setLength(0); // Clear context to avoid appending to it
                            ctxText.append("DIRECT_ANSWER:No approved relationship between your company and ").append(targetProfile.getIdentity().getLegalName()).append(" is currently recorded in APMS.");
                        } else {
                            ctxText.setLength(0); // Clear context
                            String targetName = targetProfile.getIdentity().getTradeName() != null ? targetProfile.getIdentity().getTradeName() : targetProfile.getIdentity().getLegalName();
                            String relType = pairRelationships.get(0).getRelationshipType();
                            String relStr = formatRelationshipType(relType);
                            ctxText.append("DIRECT_ANSWER:")
                                   .append(targetName).append(" is currently recorded as a ").append(relStr).append(" of your company in APMS.\n\n")
                                   .append("Relationship: ").append(relStr);
                        }
                    } else {
                        ctxText.append("Approved Relationship Facts for ").append(targetProfile.getIdentity().getLegalName()).append(":\n\n");
                        com.apms.domain.graph.dto.GraphCompanyDto graphData = graphService.getCompanyNodeWithRelationships(targetProfile.getCompanyId());
                        if (graphData != null && graphData.getRelationships() != null && !graphData.getRelationships().isEmpty()) {
                            for (com.apms.domain.graph.dto.CompanyRelationshipDto rel : graphData.getRelationships()) {
                                ctxText.append("- ").append(rel.getRelationshipType()).append(" -> ").append(rel.getTargetCompanyId()).append("\n");
                            }
                        } else {
                            ctxText.append("No relationships found.\n");
                        }
                    }
                }
                break;

            case COMPANY_PUBLIC_NEWS:
                if (!resolvedProfiles.isEmpty()) {
                    CompanyProfile p = resolvedProfiles.get(0);
                    List<ExternalDataItem> news = externalDataRepository.findTop5ByRelatedCompanyIdInOrderByPublishedAtDesc(List.of(p.getId()));
                    if (news.isEmpty()) {
                        ctxText.append("No public news found for ").append(p.getIdentity().getLegalName()).append(".\n");
                    } else {
                        ctxText.append("Public News for ").append(p.getIdentity().getLegalName()).append(":\n\n");
                        for (ExternalDataItem item : news) {
                            ctxText.append("Title: ").append(item.getTitle()).append("\n")
                                   .append("Summary: ").append(item.getSummary()).append("\n")
                                   .append("Source: ").append(item.getSource()).append("\n\n");
                        }
                    }
                    navigationActions.add(com.apms.domain.assistant.dto.AiNavigationAction.builder()
                        .type("COMPANY_PROFILE")
                        .label("View " + (p.getIdentity().getTradeName() != null ? p.getIdentity().getTradeName() : p.getIdentity().getLegalName()) + " Company Profile")
                        .companyProfileId(p.getId())
                        .companyId(p.getCompanyId())
                        .companyName(p.getIdentity().getLegalName())
                        .build());
                } else {
                    ctxText.append("Could not resolve approved company to find public news.\n");
                }
                break;

            case OUT_OF_SCOPE:
            default:
                ctxText.append("I can help you with your managed projects, team workload, reviews, deadlines, approved company profiles, company relationships, and public company information. The requested information is outside your available Manager workspace scope.\n");
                break;
        }

        return AssistantContext.builder()
                .contextText(ctxText.toString())
                .sources(sources)
                .companyProfile(resolvedProfiles.isEmpty() ? null : resolvedProfiles.get(0))
                .build();
    }

    private List<String> buildManagerSuggestedActions(AssistantContext context) {
        if (context.getCompanyProfile() != null) {
            return List.of(
            );
        }
        return List.of(
            "What needs my review?",
            "Which tasks are overdue?",
            "How is my team progressing?",
            "What should I focus on next?"
        );
    }

    private List<String> buildSuggestedActions(AssistantContext context, boolean isStaffOnly) {
        if (isStaffOnly) {
            return List.of(
                "What tasks am I assigned to?",
                "What should I work on next?",
                "Which task has the closest deadline?",
                "What projects am I participating in?"
            );
        }

        if (context.getCompanyProfile() == null) {
            return List.of("Select a company to get detailed insights.");
        }

        List<String> actions = new java.util.ArrayList<>();

        if (context.getFormattedRelationships() != null && !context.getFormattedRelationships().isEmpty()) {
            actions.add("Explore company relationship graph");
        }

        actions.add("View approved company profile details");
        return actions;
    }
}
