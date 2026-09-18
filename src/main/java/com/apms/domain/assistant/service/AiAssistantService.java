package com.apms.domain.assistant.service;

import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.TaskPriority;
import com.apms.common.enums.TaskStatus;
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

        boolean staffOnly = isStaff(currentUser) && !isManagerOrOwner(currentUser);
        boolean managerOnly = isManager(currentUser) && !isOwner(currentUser);

        // ── 2. Authorisation — project access ─────────────────────────────────
        if (staffOnly) {
            if (!projectSecurity.isMemberOrOwner(request.getProjectId())) {
                throw new BusinessValidationException(
                        "Access denied: you do not have access to project " + request.getProjectId());
            }
        }

        if (managerOnly) {
            ManagerIntent managerIntent = detectManagerIntent(request.getQuestion());
            boolean projectSpecific = managerIntent == ManagerIntent.TASK_OVERVIEW;

            if (projectSpecific && request.getProjectId() != null) {
                if (!projectSecurity.isManager(request.getProjectId())) {
                    throw new BusinessValidationException(
                            "Access denied: you do not manage project " + request.getProjectId());
                }
            }
        }

        // ── 3. Session ID ─────────────────────────────────────────────────────
        String sessionId = StringUtils.hasText(request.getSessionId())
                ? request.getSessionId()
                : UUID.randomUUID().toString();

        // ── 4. Build approved context ─────────────────────────────────────────
        AssistantContext context;
        List<AiNavigationAction> navigationActions = new java.util.ArrayList<>();
        if (staffOnly) {
            context = buildStaffContext(request, currentUser);
        } else if (managerOnly) {
            context = buildManagerContext(request, currentUser, navigationActions);
        } else {
            // OWNER AI (uses its own independent authorization inside contextService)
            context = contextService.buildContext(
                    request.getProjectId(),
                    request.getCompanyProfileId()
            );
        }

        // ── 5. Generate answer ────────────────────────────────────────────────
        String answer;
        boolean isStaffSimple = staffOnly &&
            isDeterministicIntent(detectStaffIntent(request.getQuestion()));

        boolean isManagerSimple = managerOnly &&
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
        GREETING, THANK_YOU, CAPABILITIES,
        MY_PROJECTS, PROJECT_PROGRESS, TASK_OVERVIEW, TEAM_WORKLOAD, OVERDUE_TASKS,
        PENDING_REVIEWS, SUBMISSION_REVIEW, CANDIDATE_REVIEW, RETURNED_WORK,
        NEXT_MANAGEMENT_ACTION, COMPANY_SEARCH, COMPANY_PROFILE, COMPANY_COMPARE,
        COMPANY_RELATIONSHIPS, COMPANY_PUBLIC_NEWS, INTERNAL_NEWS_PROTECTED, OUT_OF_SCOPE
    }

    private boolean isDeterministicManagerIntent(ManagerIntent intent) {
        return intent == ManagerIntent.GREETING
            || intent == ManagerIntent.THANK_YOU
            || intent == ManagerIntent.CAPABILITIES
            || intent == ManagerIntent.MY_PROJECTS
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
        ConversationalIntentDetector.ConversationalIntent conv = ConversationalIntentDetector.detect(question);
        if (conv == ConversationalIntentDetector.ConversationalIntent.GREETING) {
            return ManagerIntent.GREETING;
        }
        if (conv == ConversationalIntentDetector.ConversationalIntent.THANK_YOU) {
            return ManagerIntent.THANK_YOU;
        }
        if (conv == ConversationalIntentDetector.ConversationalIntent.CAPABILITIES) {
            return ManagerIntent.CAPABILITIES;
        }

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
                || lower.contains("progress of my projects") || lower.contains("projects progressing")
                || lower.contains("team progressing")
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
        GREETING, THANK_YOU, CAPABILITIES,
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
        ConversationalIntentDetector.ConversationalIntent conv = ConversationalIntentDetector.detect(question);
        if (conv == ConversationalIntentDetector.ConversationalIntent.GREETING) {
            return StaffIntent.GREETING;
        }
        if (conv == ConversationalIntentDetector.ConversationalIntent.THANK_YOU) {
            return StaffIntent.THANK_YOU;
        }
        if (conv == ConversationalIntentDetector.ConversationalIntent.CAPABILITIES) {
            return StaffIntent.CAPABILITIES;
        }

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

        if (lower.contains("what tasks are status")
                || lower.contains("task detail")
                || lower.contains("task details")
                || lower.contains("details of my task")
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
                || lower.contains("unfinished")
                || lower.contains("todo")
                || lower.contains("not started")
                || lower.contains("in progress")
                || lower.contains("in review")
                || lower.contains("waiting for review")
                || lower.contains("blocked")
                || lower.contains("completed")
                || lower.contains("finished")
                || lower.contains("done")
                || lower.contains("cancelled")
                || lower.contains("canceled")) {

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
        boolean isVn = isVietnamese(request.getQuestion());

        switch (intent) {
            case GREETING:
                ctxText.append(isVn ? "Xin chào! Tôi có thể giúp gì cho bạn hôm nay? Bạn có thể hỏi tôi về các dự án, nhiệm vụ được giao, hạn chót hoặc hành động tiếp theo.\n"
                        : "Hi! How can I help you today? You can ask me about your projects, assigned tasks, deadlines, or next actions.\n");
                break;

            case THANK_YOU:
                ctxText.append(isVn ? "Không có chi! Hãy cho tôi biết nếu bạn cần giúp đỡ về các dự án, nhiệm vụ, hạn chót hoặc hành động tiếp theo nhé.\n"
                        : "You're welcome! Let me know if you'd like help with your projects, tasks, deadlines, or next actions.\n");
                break;

            case CAPABILITIES:
                if (isVn) {
                    ctxText.append("Tôi có thể giúp bạn về:\n")
                           .append("- Các nhiệm vụ được giao và chi tiết nhiệm vụ\n")
                           .append("- Các dự án bạn đang tham gia\n")
                           .append("- Các hạn chót sắp tới\n")
                           .append("- Ưu tiên nhiệm vụ và hành động nên làm tiếp theo\n")
                           .append("- Trạng thái nộp bài và xét duyệt\n")
                           .append("- Thông tin công ty trong phạm vi không gian làm việc của bạn\n");
                } else {
                    ctxText.append("I can help you with:\n")
                           .append("- your assigned tasks\n")
                           .append("- projects you participate in\n")
                           .append("- upcoming deadlines\n")
                           .append("- task priorities / what to work on next\n")
                           .append("- submission/review status\n")
                           .append("- company information available within your workspace\n");
                }
                break;

            case MY_PROJECTS:
                Page<Project> projects = projectRepository.findByMemberAccountId(currentUser.getId(), PageRequest.of(0, 50));
                if (projects.isEmpty()) {
                    ctxText.append(isVn ? "Bạn hiện không tham gia vào dự án nào.\n" : "You are not participating in any active projects.\n");
                } else {
                    ctxText.append(isVn ? "Hiện tại bạn đang tham gia vào **" : "You are currently participating in **")
                           .append(projects.getNumberOfElements())
                           .append(isVn ? " dự án**:\n\n" : " project(s)**:\n\n");
                    int projNum = 1;
                    for (Project p : projects) {
                        List<ProjectTask> pTasks = p.getId() != null ? projectTaskRepository.findByProject_Id(p.getId()) : List.of();
                        long totalT = pTasks != null ? pTasks.size() : 0;
                        long completedT = pTasks != null ? pTasks.stream().filter(t -> t.getStatus() == TaskStatus.DONE).count() : 0;
                        long prog = totalT == 0 ? 0 : (completedT * 100 / totalT);

                        long userOpen = pTasks != null ? pTasks.stream().filter(t -> t.getAssignedToAccount() != null
                                && currentUser.getId().equals(t.getAssignedToAccount().getId())
                                && t.getStatus() != TaskStatus.DONE
                                && t.getStatus() != TaskStatus.CANCELLED).count() : 0;

                        ctxText.append(projNum++).append(". **").append(p.getProjectName()).append("**\n")
                               .append("   - ").append(isVn ? "Trạng thái: " : "Status: ").append(formatEnum(p.getStatus().name())).append("\n");

                        if (totalT > 0) {
                            ctxText.append("   - ").append(isVn ? "Tiến độ: " : "Progress: ").append(prog).append("%\n")
                                   .append("   - ").append(isVn ? "Nhiệm vụ mở của bạn: " : "Your open tasks: ").append(userOpen).append("\n");
                        } else if (p.getTargetCompanyName() != null && !p.getTargetCompanyName().isEmpty()) {
                            ctxText.append("   - ").append(isVn ? "Mục tiêu: " : "Target: ").append(p.getTargetCompanyName()).append("\n");
                        }
                        if (p.getPlannedEndDate() != null) {
                            ctxText.append("   - ").append(isVn ? "Hạn: " : "Due: ").append(formatShortDate(p.getPlannedEndDate().atStartOfDay())).append("\n");
                        }
                        ctxText.append("\n");
                    }
                }
                break;

            case MY_TASKS: {
                List<ProjectTask> tasks =
                        projectTaskRepository.findByAssignedToAccount_Id(currentUser.getId());

                if (tasks.isEmpty()) {
                    ctxText.append(isVn ? "Bạn hiện không có nhiệm vụ nào được giao.\n" : "You have no tasks assigned to you.\n");
                    break;
                }

                List<ProjectTask> sortedTasks = tasks.stream()
                        .sorted(taskPriorityComparator())
                        .toList();

                boolean showAll = isAllRequested(request.getQuestion());
                int displayLimit = showAll ? sortedTasks.size() : Math.min(sortedTasks.size(), 6);

                ctxText.append(isVn ? "Hiện tại bạn có **" : "You currently have **")
                       .append(tasks.size())
                       .append(isVn ? " nhiệm vụ được giao**" : " assigned task(s)**");

                if (tasks.size() > 3) {
                    long inProgress = tasks.stream().filter(t -> t.getStatus() == TaskStatus.IN_PROGRESS).count();
                    long todo = tasks.stream().filter(t -> t.getStatus() == TaskStatus.TODO).count();
                    long inReview = tasks.stream().filter(t -> t.getStatus() == TaskStatus.IN_REVIEW).count();
                    long blocked = tasks.stream().filter(t -> t.getStatus() == TaskStatus.BLOCKED).count();
                    long done = tasks.stream().filter(t -> t.getStatus() == TaskStatus.DONE).count();

                    ctxText.append(":\n\n");
                    if (inProgress > 0) ctxText.append("- ").append(isVn ? "Đang thực hiện: " : "In Progress: ").append(inProgress).append("\n");
                    if (todo > 0) ctxText.append("- ").append(isVn ? "Cần làm: " : "To Do: ").append(todo).append("\n");
                    if (inReview > 0) ctxText.append("- ").append(isVn ? "Đang chờ duyệt: " : "In Review: ").append(inReview).append("\n");
                    if (blocked > 0) ctxText.append("- ").append(isVn ? "Bị chặn: " : "Blocked: ").append(blocked).append("\n");
                    if (done > 0) ctxText.append("- ").append(isVn ? "Hoàn thành: " : "Done: ").append(done).append("\n");
                    ctxText.append("\n");
                    ctxText.append(isVn ? "### Nhiệm vụ ưu tiên\n\n" : "### Priority tasks\n\n");
                } else {
                    ctxText.append(":\n\n");
                }

                int num = 1;
                for (int idx = 0; idx < displayLimit; idx++) {
                    ProjectTask task = sortedTasks.get(idx);
                    ctxText.append(num++).append(". **").append(task.getTitle()).append("**\n")
                           .append("   - ").append(isVn ? "Dự án: " : "Project: ").append(task.getProject() != null ? task.getProject().getProjectName() : "N/A").append("\n")
                           .append("   - ").append(isVn ? "Trạng thái: " : "Status: ").append(formatEnum(task.getStatus().name())).append("\n");

                    if (task.getPriority() != null) {
                        ctxText.append("   - ").append(isVn ? "Mức ưu tiên: " : "Priority: ").append(formatEnum(task.getPriority().name())).append("\n");
                    }
                    if (task.getDueDate() != null) {
                        ctxText.append("   - ").append(isVn ? "Hạn: " : "Due: ").append(formatShortDate(task.getDueDate())).append("\n");
                    }
                    ctxText.append("\n");
                }

                if (!showAll && sortedTasks.size() > displayLimit) {
                    int remaining = sortedTasks.size() - displayLimit;
                    if (isVn) {
                        ctxText.append("*Còn ").append(remaining).append(" nhiệm vụ nữa chưa hiển thị ở đây. Hãy hỏi \"hiển thị tất cả nhiệm vụ\" nếu bạn muốn xem toàn bộ danh sách.*\n");
                    } else {
                        ctxText.append("*").append(remaining).append(" more assigned task(s) are not shown here. Ask me to show all assigned tasks if you want the full list.*\n");
                    }
                }

                break;
            }
            case TASK_STATUS: {
                List<ProjectTask> tasks =
                        projectTaskRepository.findByAssignedToAccount_Id(currentUser.getId());

                if (tasks.isEmpty()) {
                    ctxText.append(isVn ? "Bạn hiện không có nhiệm vụ nào được giao.\n" : "You have no tasks assigned to you.\n");
                    break;
                }

                String question = request.getQuestion().toLowerCase();
                TaskStatus requestedStatus = null;

                if (question.contains("in progress") || question.contains("đang làm") || question.contains("đang thực hiện")) {
                    requestedStatus = TaskStatus.IN_PROGRESS;
                } else if (question.contains("todo") || question.contains("to do") || question.contains("not started") || question.contains("chưa bắt đầu")) {
                    requestedStatus = TaskStatus.TODO;
                } else if (question.contains("in review") || question.contains("waiting for review") || question.contains("waiting for manager review") || question.contains("chờ duyệt")) {
                    requestedStatus = TaskStatus.IN_REVIEW;
                } else if (question.contains("blocked") || question.contains("bị chặn")) {
                    requestedStatus = TaskStatus.BLOCKED;
                } else if (question.contains("completed") || question.contains("finished") || question.contains("done") || question.contains("hoàn thành")) {
                    requestedStatus = TaskStatus.DONE;
                } else if (question.contains("cancelled") || question.contains("canceled") || question.contains("đã hủy")) {
                    requestedStatus = TaskStatus.CANCELLED;
                }

                if (requestedStatus != null) {
                    final TaskStatus statusToFind = requestedStatus;
                    List<ProjectTask> filteredTasks = tasks.stream()
                            .filter(t -> t.getStatus() == statusToFind)
                            .sorted(taskPriorityComparator())
                            .toList();

                    if (filteredTasks.isEmpty()) {
                        ctxText.append(isVn ? "Bạn không có nhiệm vụ nào ở trạng thái " : "You have no tasks with status ")
                                .append(formatEnum(requestedStatus.name()))
                                .append(".\n");
                        break;
                    }

                    boolean showAll = isAllRequested(request.getQuestion());
                    int displayLimit = showAll ? filteredTasks.size() : Math.min(filteredTasks.size(), 6);

                    ctxText.append(isVn ? "Hiện tại bạn có **" : "You currently have **")
                            .append(filteredTasks.size())
                            .append(isVn ? " nhiệm vụ** với trạng thái **" : " task(s)** with status **")
                            .append(formatEnum(requestedStatus.name()))
                            .append("**:\n\n");

                    int i = 1;
                    for (int idx = 0; idx < displayLimit; idx++) {
                        ProjectTask task = filteredTasks.get(idx);
                        ctxText.append(i++).append(". **").append(task.getTitle()).append("**\n")
                               .append("   - ").append(isVn ? "Dự án: " : "Project: ").append(task.getProject() != null ? task.getProject().getProjectName() : "N/A").append("\n")
                               .append("   - ").append(isVn ? "Trạng thái: " : "Status: ").append(formatEnum(task.getStatus().name())).append("\n");
                        if (task.getPriority() != null) {
                            ctxText.append("   - ").append(isVn ? "Mức ưu tiên: " : "Priority: ").append(formatEnum(task.getPriority().name())).append("\n");
                        }
                        if (task.getDueDate() != null) {
                            ctxText.append("   - ").append(isVn ? "Hạn: " : "Due: ").append(formatShortDate(task.getDueDate())).append("\n");
                        }
                        ctxText.append("\n");
                    }

                    if (!showAll && filteredTasks.size() > displayLimit) {
                        int remaining = filteredTasks.size() - displayLimit;
                        ctxText.append("*").append(remaining).append(" more task(s) with this status are not shown.*\n");
                    }

                } else if (question.contains("incomplete") || question.contains("unfinished") || question.contains("chưa xong")) {
                    List<ProjectTask> incompleteTasks = tasks.stream()
                            .filter(t -> t.getStatus() != TaskStatus.DONE && t.getStatus() != TaskStatus.CANCELLED)
                            .sorted(taskPriorityComparator())
                            .toList();

                    if (incompleteTasks.isEmpty()) {
                        ctxText.append(isVn ? "Bạn không có nhiệm vụ chưa hoàn thành nào.\n" : "You have no incomplete tasks.\n");
                        break;
                    }

                    boolean showAll = isAllRequested(request.getQuestion());
                    int displayLimit = showAll ? incompleteTasks.size() : Math.min(incompleteTasks.size(), 6);

                    ctxText.append(isVn ? "Hiện tại bạn có **" : "You currently have **")
                            .append(incompleteTasks.size())
                            .append(isVn ? " nhiệm vụ chưa hoàn thành**:\n\n" : " incomplete task(s)**:\n\n");

                    int i = 1;
                    for (int idx = 0; idx < displayLimit; idx++) {
                        ProjectTask task = incompleteTasks.get(idx);
                        ctxText.append(i++).append(". **").append(task.getTitle()).append("**\n")
                               .append("   - ").append(isVn ? "Dự án: " : "Project: ").append(task.getProject() != null ? task.getProject().getProjectName() : "N/A").append("\n")
                               .append("   - ").append(isVn ? "Trạng thái: " : "Status: ").append(formatEnum(task.getStatus().name())).append("\n");
                        if (task.getPriority() != null) {
                            ctxText.append("   - ").append(isVn ? "Mức ưu tiên: " : "Priority: ").append(formatEnum(task.getPriority().name())).append("\n");
                        }
                        if (task.getDueDate() != null) {
                            ctxText.append("   - ").append(isVn ? "Hạn: " : "Due: ").append(formatShortDate(task.getDueDate())).append("\n");
                        }
                        ctxText.append("\n");
                    }

                    if (!showAll && incompleteTasks.size() > displayLimit) {
                        int remaining = incompleteTasks.size() - displayLimit;
                        ctxText.append("*").append(remaining).append(" more incomplete task(s) are not shown.*\n");
                    }

                } else {
                    long todoCount = tasks.stream().filter(t -> t.getStatus() == TaskStatus.TODO).count();
                    long inProgressCount = tasks.stream().filter(t -> t.getStatus() == TaskStatus.IN_PROGRESS).count();
                    long inReviewCount = tasks.stream().filter(t -> t.getStatus() == TaskStatus.IN_REVIEW).count();
                    long blockedCount = tasks.stream().filter(t -> t.getStatus() == TaskStatus.BLOCKED).count();
                    long doneCount = tasks.stream().filter(t -> t.getStatus() == TaskStatus.DONE).count();
                    long cancelledCount = tasks.stream().filter(t -> t.getStatus() == TaskStatus.CANCELLED).count();

                    ctxText.append(isVn ? "Dưới đây là tổng hợp trạng thái các nhiệm vụ của bạn:\n\n" : "Here is the current status summary of your tasks:\n\n")
                            .append("- ").append(isVn ? "Tổng số nhiệm vụ: " : "Total Tasks: ").append(tasks.size()).append("\n")
                            .append("- ").append(isVn ? "Đang thực hiện: " : "In Progress: ").append(inProgressCount).append("\n")
                            .append("- ").append(isVn ? "Cần làm: " : "To Do: ").append(todoCount).append("\n")
                            .append("- ").append(isVn ? "Đang chờ duyệt: " : "In Review: ").append(inReviewCount).append("\n")
                            .append("- ").append(isVn ? "Bị chặn: " : "Blocked: ").append(blockedCount).append("\n")
                            .append("- ").append(isVn ? "Hoàn thành: " : "Done: ").append(doneCount).append("\n")
                            .append("- ").append(isVn ? "Đã hủy: " : "Cancelled: ").append(cancelledCount).append("\n");
                }

                break;
            }
            case TASK_DETAIL:
            case DEADLINE_PRIORITY:
            case NEXT_ACTION:
                List<ProjectTask> tasks = projectTaskRepository.findByAssignedToAccount_Id(currentUser.getId());

                if (intent == StaffIntent.DEADLINE_PRIORITY) {
                    ProjectTask closest = tasks.stream()
                        .filter(t -> t.getDueDate() != null && t.getStatus() != TaskStatus.DONE && t.getStatus() != TaskStatus.CANCELLED && t.getStatus() != TaskStatus.BLOCKED)
                        .min(java.util.Comparator.comparing(ProjectTask::getDueDate))
                        .orElse(null);
                    if (closest != null) {
                        ctxText.append(isVn ? "Nhiệm vụ có thời hạn gần nhất của bạn là:\n\n" : "Your closest active deadline is:\n\n")
                               .append("### **").append(closest.getTitle()).append("**\n")
                               .append("- ").append(isVn ? "Dự án: " : "Project: ").append(closest.getProject().getProjectName()).append("\n")
                               .append("- ").append(isVn ? "Hạn chót: " : "Due: ").append(formatShortDate(closest.getDueDate() != null ? closest.getDueDate().toLocalDate() : null)).append("\n")
                               .append("- ").append(isVn ? "Trạng thái: " : "Status: ").append(formatEnum(closest.getStatus().name())).append("\n")
                               .append("- ").append(isVn ? "Độ ưu tiên: " : "Priority: ").append(formatEnum(closest.getPriority() != null ? closest.getPriority().name() : null)).append("\n\n");
                    } else {
                        ctxText.append(isVn ? "Bạn không có nhiệm vụ nào đang thực hiện có thời hạn.\n" : "You have no active tasks with deadlines.\n");
                    }
                } else if (intent == StaffIntent.NEXT_ACTION) {
                    List<ProjectTask> activeTasks = tasks.stream()
                            .filter(t -> t.getStatus() == TaskStatus.TODO || t.getStatus() == TaskStatus.IN_PROGRESS)
                            .sorted(taskPriorityComparator())
                            .collect(Collectors.toList());

                    if (!activeTasks.isEmpty()) {
                        ProjectTask nextTask = activeTasks.get(0);
                        ctxText.append(isVn ? "Bạn nên tập trung vào nhiệm vụ này tiếp theo:\n\n" : "You should work on this task next.\n\n")
                               .append("### **").append(nextTask.getTitle()).append("**\n")
                               .append("- ").append(isVn ? "Dự án: " : "Project: ").append(nextTask.getProject().getProjectName()).append("\n")
                               .append("- ").append(isVn ? "Độ ưu tiên: " : "Priority: ").append(formatEnum(nextTask.getPriority() != null ? nextTask.getPriority().name() : null)).append("\n")
                               .append("- ").append(isVn ? "Hạn chót: " : "Due: ").append(formatShortDate(nextTask.getDueDate() != null ? nextTask.getDueDate().toLocalDate() : null)).append("\n")
                               .append("- ").append(isVn ? "Trạng thái: " : "Status: ").append(formatEnum(nextTask.getStatus().name())).append("\n\n");

                        String whyReason;
                        LocalDateTime now = LocalDateTime.now();
                        if (nextTask.getDueDate() != null && nextTask.getDueDate().isBefore(now)) {
                            whyReason = isVn ? "Nhiệm vụ này đã quá hạn và cần được xử lý ngay lập tức." : "This task is overdue and requires immediate attention.";
                        } else if (nextTask.getPriority() == TaskPriority.HIGH) {
                            whyReason = isVn ? "Đây là nhiệm vụ có mức độ ưu tiên cao nhất với thời hạn gần nhất." : "This is your highest-priority executable task with the nearest deadline.";
                        } else if (nextTask.getDueDate() != null) {
                            whyReason = isVn ? "Nhiệm vụ này có thời hạn gần nhất trong số các nhiệm vụ đang xử lý." : "It has the nearest deadline among your active tasks.";
                        } else {
                            whyReason = isVn ? "Đây là nhiệm vụ khả thi tiếp theo trong danh sách của bạn." : "This is your highest-priority executable task.";
                        }

                        ctxText.append("**").append(isVn ? "Lý do:" : "Why:").append("**\n")
                               .append(whyReason).append("\n\n");

                        if (activeTasks.size() > 1) {
                            ctxText.append("**").append(isVn ? "Các nhiệm vụ tiếp theo sau đó:" : "Next after that:").append("**\n");
                            int limit = Math.min(activeTasks.size(), 3);
                            for (int i = 1; i < limit; i++) {
                                ProjectTask t = activeTasks.get(i);
                                ctxText.append(i).append(". **").append(t.getTitle()).append("** (")
                                       .append(t.getProject().getProjectName())
                                       .append(t.getDueDate() != null ? " • " + (isVn ? "Hạn: " : "Due: ") + formatShortDate(t.getDueDate().toLocalDate()) : "")
                                       .append(")\n");
                            }
                            ctxText.append("\n");
                        }
                    } else {
                        ctxText.append(isVn ? "Bạn không có nhiệm vụ nào cần thực hiện lúc này.\n" : "You have no executable tasks found.\n");
                    }
                } else {
                    if (tasks.isEmpty()) {
                        ctxText.append(isVn ? "Bạn hiện không có nhiệm vụ nào được giao.\n" : "You have no tasks assigned to you.\n");
                    } else {
                        boolean showAll = isAllRequested(request.getQuestion());
                        List<ProjectTask> sorted = tasks.stream()
                                .sorted(taskPriorityComparator())
                                .collect(Collectors.toList());

                        int maxToShow = showAll ? sorted.size() : Math.min(6, sorted.size());

                        ctxText.append(isVn ? "Chi tiết nhiệm vụ được giao cho bạn (" : "Detailed task information (")
                               .append(tasks.size()).append(isVn ? " nhiệm vụ):\n\n" : " task(s)):\n\n");

                        for (int i = 0; i < maxToShow; i++) {
                            ProjectTask t = sorted.get(i);
                            ctxText.append(i + 1).append(". **").append(t.getTitle()).append("**\n")
                                   .append("   - ").append(isVn ? "Dự án: " : "Project: ").append(t.getProject().getProjectName()).append("\n")
                                   .append("   - ").append(isVn ? "Trạng thái: " : "Status: ").append(formatEnum(t.getStatus().name())).append("\n")
                                   .append("   - ").append(isVn ? "Độ ưu tiên: " : "Priority: ").append(formatEnum(t.getPriority() != null ? t.getPriority().name() : null)).append("\n")
                                   .append("   - ").append(isVn ? "Hạn chót: " : "Due: ").append(formatShortDate(t.getDueDate() != null ? t.getDueDate().toLocalDate() : null)).append("\n");
                            if (t.getDescription() != null && !t.getDescription().isBlank()) {
                                ctxText.append("   - ").append(isVn ? "Mô tả: " : "Description: ").append(t.getDescription().trim()).append("\n");
                            }
                            ctxText.append("\n");
                        }

                        if (!showAll && sorted.size() > maxToShow) {
                            int remaining = sorted.size() - maxToShow;
                            ctxText.append(isVn ? "Còn " : "")
                                   .append(remaining)
                                   .append(isVn ? " nhiệm vụ nữa chưa được hiển thị. Hãy nhắn 'xem tất cả nhiệm vụ' nếu bạn muốn xem đầy đủ danh sách.\n"
                                           : " more assigned task(s) are not shown here. Ask me to show all assigned tasks if you want the full list.\n");
                        }
                    }
                }
                break;

            case SUBMISSION_STATUS:
            case RETURNED_WORK:
                List<ProjectTask> userTasks = projectTaskRepository.findByAssignedToAccount_Id(currentUser.getId());
                List<Long> taskIds = userTasks.stream().map(ProjectTask::getId).collect(Collectors.toList());

                if (taskIds.isEmpty()) {
                    ctxText.append(isVn ? "Bạn không có nhiệm vụ nào, nên không có bài nộp nào.\n" : "You have no tasks, so no submissions.\n");
                } else {
                    List<ProjectTaskSubmission> submissions = projectTaskSubmissionRepository.findByProjectTask_IdIn(taskIds);

                    if (intent == StaffIntent.RETURNED_WORK) {
                        List<ProjectTaskSubmission> returned = submissions.stream()
                            .filter(s -> s.getStatus() == SubmissionStatus.REVISION_REQUESTED)
                            .collect(Collectors.toList());
                        if (returned.isEmpty()) {
                            ctxText.append(isVn ? "Bạn không có nhiệm vụ nào cần chỉnh sửa lại.\n" : "You have no tasks that require revision.\n");
                        } else {
                            ctxText.append(isVn ? "Bạn có **" : "You have **")
                                   .append(returned.size())
                                   .append(isVn ? " nhiệm vụ** cần chỉnh sửa lại:\n\n" : " task(s)** that require revision:\n\n");
                            int idx = 1;
                            for (ProjectTaskSubmission s : returned) {
                                ctxText.append(idx++).append(". **").append(s.getProjectTask().getTitle()).append("**\n")
                                       .append("   - ").append(isVn ? "Dự án: " : "Project: ").append(s.getProjectTask().getProject().getProjectName()).append("\n")
                                       .append("   - ").append(isVn ? "Trạng thái: " : "Status: ").append("Returned for Revision\n\n");
                            }
                        }
                    } else {
                        if (submissions.isEmpty()) {
                            ctxText.append(isVn ? "Bạn chưa có bài nộp nào.\n" : "You have no submissions.\n");
                        } else {
                            ctxText.append(isVn ? "Tình trạng bài nộp của bạn:\n\n" : "Your submission status is:\n\n");
                            int idx = 1;
                            for (ProjectTaskSubmission s : submissions) {
                                ctxText.append(idx++).append(". **Task: ").append(s.getProjectTask().getTitle()).append("**\n")
                                       .append("   - Status: Submitted\n")
                                       .append("   - ").append(isVn ? "Đánh giá: " : "Review Status: ").append(formatEnum(s.getStatus().name())).append("\n")
                                       .append("   - ").append(isVn ? "Ngày nộp: " : "Submitted At: ").append(formatShortDate(s.getSubmittedAt() != null ? s.getSubmittedAt().toLocalDate() : null)).append("\n\n");
                            }
                        }
                    }
                }
                break;

            case OUT_OF_SCOPE:
            default:
                ctxText.append(isVn ? "Tôi có thể hỗ trợ bạn về các dự án APMS, nhiệm vụ được giao, thời hạn, bài nộp và công việc cần làm tiếp theo. Thông tin yêu cầu nằm ngoài phạm vi không gian làm việc Nhân viên của bạn.\n"
                        : "I can help you with your APMS projects, assigned tasks, deadlines, submissions, and next actions. The requested information is outside your available Staff workspace scope.\n");
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

    private String formatShortDate(java.time.LocalDateTime dateTime) {
        if (dateTime == null) return "No deadline";
        return dateTime.toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.ENGLISH));
    }

    private String formatShortDate(java.time.LocalDate date) {
        if (date == null) return "No deadline";
        return date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.ENGLISH));
    }

    private boolean isAllRequested(String question) {
        if (question == null) return false;
        String lower = question.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("all") || lower.contains("tất cả") || lower.contains("toàn bộ") || lower.contains("everything");
    }

    private boolean isVietnamese(String question) {
        if (question == null) return false;
        String lower = question.toLowerCase(java.util.Locale.ROOT);
        return lower.matches(".*[àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ].*")
                || lower.contains("của tôi") || lower.contains("cong viec") || lower.contains("nhiem vu")
                || lower.contains("du an") || lower.contains("xin chao") || lower.contains("chao ban");
    }

    private java.util.Comparator<ProjectTask> taskPriorityComparator() {
        return (t1, t2) -> {
            java.time.LocalDate now = java.time.LocalDate.now();
            boolean t1Overdue = t1.getDueDate() != null && t1.getDueDate().toLocalDate().isBefore(now)
                    && t1.getStatus() != TaskStatus.DONE && t1.getStatus() != TaskStatus.CANCELLED;
            boolean t2Overdue = t2.getDueDate() != null && t2.getDueDate().toLocalDate().isBefore(now)
                    && t2.getStatus() != TaskStatus.DONE && t2.getStatus() != TaskStatus.CANCELLED;
            if (t1Overdue != t2Overdue) return t1Overdue ? -1 : 1;

            boolean t1Active = t1.getStatus() != TaskStatus.DONE && t1.getStatus() != TaskStatus.CANCELLED;
            boolean t2Active = t2.getStatus() != TaskStatus.DONE && t2.getStatus() != TaskStatus.CANCELLED;
            if (t1Active != t2Active) return t1Active ? -1 : 1;

            if (t1.getDueDate() != null && t2.getDueDate() != null) {
                int dueCmp = t1.getDueDate().compareTo(t2.getDueDate());
                if (dueCmp != 0) return dueCmp;
            } else if (t1.getDueDate() != null) {
                return -1;
            } else if (t2.getDueDate() != null) {
                return 1;
            }

            int p1 = priorityRank(t1.getPriority());
            int p2 = priorityRank(t2.getPriority());
            if (p1 != p2) return Integer.compare(p1, p2);

            int s1 = statusRank(t1.getStatus());
            int s2 = statusRank(t2.getStatus());
            if (s1 != s2) return Integer.compare(s1, s2);

            return Long.compare(t1.getId() != null ? t1.getId() : 0, t2.getId() != null ? t2.getId() : 0);
        };
    }

    private int priorityRank(com.apms.common.enums.TaskPriority priority) {
        if (priority == null) return 3;
        return switch (priority) {
            case HIGH -> 0;
            case MEDIUM -> 1;
            case LOW -> 2;
            default -> 3;
        };
    }

    private int statusRank(TaskStatus status) {
        if (status == null) return 7;
        return switch (status) {
            case IN_PROGRESS -> 0;
            case TODO -> 1;
            case AVAILABLE -> 2;
            case IN_REVIEW -> 3;
            case BLOCKED -> 4;
            case DONE -> 5;
            case CANCELLED -> 6;
            default -> 7;
        };
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
                CompanyProfile p = companyProfileRepository.findById(request.getCompanyProfileId())
                        .filter(profile -> "APPROVED".equals(profile.getReviewStatus()) && !Boolean.TRUE.equals(profile.getIsHidden()))
                        .orElse(null);
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
            case GREETING:
                ctxText.append("Hi! How can I help you today? You can ask me about your managed projects, team workload, pending reviews, deadlines, or company profiles.\n");
                break;

            case THANK_YOU:
                ctxText.append("You're welcome! Let me know if you'd like help with your managed projects, team workload, reviews, or company profiles.\n");
                break;

            case CAPABILITIES:
                ctxText.append("I can help you with:\n")
                       .append("- your managed projects and team workload\n")
                       .append("- project progress and task overviews\n")
                       .append("- pending reviews and candidate evaluations\n")
                       .append("- upcoming deadlines and overdue tasks\n")
                       .append("- next management actions and priorities\n")
                       .append("- approved company profiles, relationships, and public news\n");
                break;

            case INTERNAL_NEWS_PROTECTED:
                ctxText.append("Internal News is protected data and is not accessible through the AI Assistant.\nPlease view it directly through the authorized Internal News section.\n");
                break;

            case MY_PROJECTS:
                Page<Project> rawProjects = projectRepository.findByMemberAccountId(currentUser.getId(), PageRequest.of(0, 100));
                List<Project> projects = rawProjects.stream().filter(p -> projectSecurity.isManager(p.getId())).collect(Collectors.toList());
                if (projects.isEmpty()) {
                    ctxText.append("You are not managing any active projects.\n");
                } else {
                    ctxText.append("You are currently managing **").append(projects.size()).append(" project(s)**.\n\n");
                    int i = 1;
                    for (Project p : projects) {
                        ctxText.append(i++).append(". **").append(p.getProjectName()).append("**\n")
                               .append("   - Status: ").append(formatEnum(p.getStatus().name())).append("\n")
                               .append("   - Type: ").append(formatEnum(p.getProjectType() != null ? p.getProjectType().name() : null)).append("\n")
                               .append("   - Target: ").append(p.getTargetCompanyName() != null ? p.getTargetCompanyName() : "N/A").append("\n\n");
                    }
                }
                break;

            case PROJECT_PROGRESS:
                Page<Project> ppProjectsRaw = projectRepository.findByMemberAccountId(currentUser.getId(), PageRequest.of(0, 100));
                List<Project> ppProjects = ppProjectsRaw.stream()
                        .filter(p -> projectSecurity.isManager(p.getId()))
                        .collect(Collectors.toList());

                if (ppProjects.isEmpty()) {
                    ctxText.append("DIRECT_ANSWER: You are not currently managing any projects.\n");
                } else {
                    ctxText.append("DIRECT_ANSWER: You are currently managing **").append(ppProjects.size()).append(" project(s)**.\n\n");
                    int projIndex = 1;
                    for (Project p : ppProjects) {
                        List<ProjectTask> pTasks = projectTaskRepository.findByProject_Id(p.getId());
                        long totalT = pTasks.size();
                        long completedT = pTasks.stream().filter(t -> t.getStatus() == TaskStatus.DONE).count();
                        long prog = totalT == 0 ? 0 : (completedT * 100 / totalT);

                        ctxText.append(projIndex++).append(". **").append(p.getProjectName()).append("**\n")
                               .append("   - Progress: ").append(prog).append("%\n")
                               .append("   - Completed Tasks: ").append(completedT).append("/").append(totalT).append("\n\n");
                    }
                }
                break;

            case TASK_OVERVIEW:
                List<ProjectTask> managerTasks = new java.util.ArrayList<>();
                if (request.getProjectId() != null) {
                    if (projectSecurity.isManager(request.getProjectId())) {
                        managerTasks = projectTaskRepository.findByProject_Id(request.getProjectId());
                    }
                } else {
                    Page<Project> allManagerProjects = projectRepository.findByMemberAccountId(currentUser.getId(), PageRequest.of(0, 100));
                    List<Long> pIds = allManagerProjects.stream().filter(p -> projectSecurity.isManager(p.getId())).map(Project::getId).collect(Collectors.toList());
                    if (!pIds.isEmpty()) {
                        managerTasks = projectTaskRepository.findByProject_IdIn(pIds);
                    }
                }

                if (managerTasks.isEmpty()) {
                    ctxText.append("No tasks found in your managed projects.\n");
                } else {
                    boolean showAll = isAllRequested(request.getQuestion());
                    List<ProjectTask> sortedMTasks = managerTasks.stream()
                            .sorted(taskPriorityComparator())
                            .collect(Collectors.toList());
                    int maxToShow = showAll ? sortedMTasks.size() : Math.min(6, sortedMTasks.size());

                    ctxText.append("Task Overview (").append(managerTasks.size()).append(" task(s)):\n\n");
                    for (int idx = 0; idx < maxToShow; idx++) {
                        ProjectTask t = sortedMTasks.get(idx);
                        ctxText.append(idx + 1).append(". **").append(t.getTitle()).append("**\n")
                               .append("   - Project: ").append(t.getProject().getProjectName()).append("\n")
                               .append("   - Status: ").append(formatEnum(t.getStatus().name())).append("\n")
                               .append("   - Priority: ").append(formatEnum(t.getPriority() != null ? t.getPriority().name() : null)).append("\n")
                               .append("   - Due: ").append(formatShortDate(t.getDueDate() != null ? t.getDueDate().toLocalDate() : null)).append("\n\n");
                    }

                    if (!showAll && sortedMTasks.size() > maxToShow) {
                        int remaining = sortedMTasks.size() - maxToShow;
                        ctxText.append(remaining).append(" more task(s) are not shown here. Ask me to show all tasks if you want the full list.\n");
                    }
                }
                break;

            case TEAM_WORKLOAD:
                Page<Project> tmProjects = projectRepository.findByMemberAccountId(currentUser.getId(), PageRequest.of(0, 100));
                List<Long> tmPIds = tmProjects.stream().filter(p -> projectSecurity.isManager(p.getId())).map(Project::getId).collect(Collectors.toList());
                if (tmPIds.isEmpty()) {
                    ctxText.append("No projects found.\n");
                } else {
                    List<ProjectTask> tmTasks = projectTaskRepository.findByProject_IdIn(tmPIds);
                    java.util.Map<String, Long> workload = tmTasks.stream()
                            .filter(t -> t.getAssignedToAccount() != null && t.getStatus() != TaskStatus.DONE && t.getStatus() != TaskStatus.CANCELLED)
                            .collect(Collectors.groupingBy(t -> t.getAssignedToAccount().getEmail(), Collectors.counting()));
                    ctxText.append("Team Workload Facts:\n\n");
                    workload.forEach((name, count) -> ctxText.append("- **").append(name).append("**: ").append(count).append(" active task(s)\n"));
                }
                break;

            case OVERDUE_TASKS:
                Page<Project> odProjects = projectRepository.findByMemberAccountId(currentUser.getId(), PageRequest.of(0, 100));
                List<Long> odPIds = odProjects.stream().filter(p -> projectSecurity.isManager(p.getId())).map(Project::getId).collect(Collectors.toList());
                if (odPIds.isEmpty()) {
                    ctxText.append("No overdue tasks found.\n");
                } else {
                    List<ProjectTask> odTasks = projectTaskRepository.findByProject_IdIn(odPIds);
                    LocalDateTime now = LocalDateTime.now();
                    List<ProjectTask> overdue = odTasks.stream()
                            .filter(t -> t.getDueDate() != null && t.getDueDate().isBefore(now) && t.getStatus() != TaskStatus.DONE && t.getStatus() != TaskStatus.CANCELLED  && t.getStatus() != TaskStatus.BLOCKED)
                            .sorted(taskPriorityComparator())
                            .collect(Collectors.toList());
                    if (overdue.isEmpty()) {
                        ctxText.append("You have no overdue tasks in your projects.\n");
                    } else {
                        boolean showAll = isAllRequested(request.getQuestion());
                        int maxToShow = showAll ? overdue.size() : Math.min(6, overdue.size());

                        ctxText.append("Overdue Tasks (").append(overdue.size()).append(" task(s)):\n\n");
                        for (int idx = 0; idx < maxToShow; idx++) {
                            ProjectTask t = overdue.get(idx);
                            ctxText.append(idx + 1).append(". **").append(t.getTitle()).append("**\n")
                                   .append("   - Project: ").append(t.getProject().getProjectName()).append("\n")
                                   .append("   - Assigned To: ").append(t.getAssignedToAccount() != null ? t.getAssignedToAccount().getEmail() : "Unassigned").append("\n")
                                   .append("   - Deadline: ").append(formatShortDate(t.getDueDate().toLocalDate())).append("\n\n");
                        }

                        if (!showAll && overdue.size() > maxToShow) {
                            int remaining = overdue.size() - maxToShow;
                            ctxText.append(remaining).append(" more overdue task(s) are not shown here. Ask me to show all overdue tasks if you want the full list.\n");
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
                    List<ProjectTaskSubmission> pendingSubs = projectTaskSubmissionRepository.findByProject_IdInAndStatus(prPIds, SubmissionStatus.IN_REVIEW);
                    if (pendingSubs.isEmpty()) {
                        ctxText.append("You have no submissions waiting for review.\n");
                    } else {
                        ctxText.append("Pending Submissions for Review (**").append(pendingSubs.size()).append("**):\n\n");
                        int idx = 1;
                        for (ProjectTaskSubmission s : pendingSubs) {
                            ctxText.append(idx++).append(". **").append(s.getProjectTask().getTitle()).append("**\n")
                                   .append("   - Project: ").append(s.getProject().getProjectName()).append("\n")
                                   .append("   - Submitted By: ").append(s.getSubmittedByAccount() != null ? s.getSubmittedByAccount().getEmail() : "Unknown").append("\n")
                                   .append("   - Date: ").append(formatShortDate(s.getSubmittedAt() != null ? s.getSubmittedAt().toLocalDate() : null)).append("\n\n");
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
                        ctxText.append("Pending Candidates for Review (**").append(candidates.size()).append("**):\n\n");
                        int idx = 1;
                        for (CompanyCandidate c : candidates) {
                            ctxText.append(idx++).append(". **").append(c.getIdentity() != null ? c.getIdentity().getLegalName() : "Unknown").append("**\n")
                                   .append("   - Status: Pending Review\n\n");
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
                    List<ProjectTaskSubmission> returnedSubs = projectTaskSubmissionRepository.findByProject_IdInAndStatus(rwPIds, SubmissionStatus.REVISION_REQUESTED);
                    if (returnedSubs.isEmpty()) {
                        ctxText.append("No work is currently returned for revision.\n");
                    } else {
                        ctxText.append("Revision Requested Work (**").append(returnedSubs.size()).append("**):\n\n");
                        int idx = 1;
                        for (ProjectTaskSubmission s : returnedSubs) {
                            ctxText.append(idx++).append(". **").append(s.getProjectTask().getTitle()).append("**\n")
                                   .append("   - Project: ").append(s.getProject().getProjectName()).append("\n")
                                   .append("   - Assigned To: ").append(s.getProjectTask().getAssignedToAccount() != null ? s.getProjectTask().getAssignedToAccount().getEmail() : "Unassigned").append("\n\n");
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
                    List<ProjectTask> nxTasks = projectTaskRepository.findByProject_IdIn(nxPIds);
                    long overdueCount = nxTasks.stream().filter(t -> t.getDueDate() != null && t.getDueDate().isBefore(LocalDateTime.now()) && t.getStatus() != TaskStatus.DONE && t.getStatus() != TaskStatus.CANCELLED).count();
                    long pendingSubCount = projectTaskSubmissionRepository.findByProject_IdInAndStatus(nxPIds, SubmissionStatus.IN_REVIEW).size();
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
            "How are my projects progressing?",
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
