# Company Member Research & Real-Time Project Chat

## 1. Company Member Research

### Overview
This feature allows Business Development Staff to draft a list of key company members (e.g. leadership) from research, which is then reviewed by a Business Development Manager. Upon approval, the draft members are merged into the target `CompanyProfile`.

### Workflow
1. **Drafting (Staff)**
   - `POST /api/v1/projects/{projectId}/tasks/{taskId}/company-members/draft` to create/update the draft.
   - Saves to `company_member_research_drafts` MongoDB collection.
2. **Submission (Staff)**
   - `POST /api/v1/projects/{projectId}/tasks/{taskId}/company-members/submit` to submit for review.
   - Creates a `ProjectTaskSubmission` using the existing submission flow. Task state moves to `IN_REVIEW`.
3. **Review (Manager)**
   - Manager reviews the submission via the existing `ProjectTaskSubmissionController` (`POST /api/v1/projects/{projectId}/tasks/{taskId}/submissions/{submissionId}/review`).
   - If approved, the draft members are merged into `CompanyProfile.companyMembers` (duplicates skipped based on normalized fullName + position). A new `CompanyProfileVersion` is created.
   - If rejected, the draft becomes editable again.

### Target Collections
- `company_member_research_drafts`
- `company_profiles` (added `companyMembers` field)
- `company_profile_versions`

---

## 2. Real-Time Project Group Chat

### Overview
Provides real-time text-based chat for project members via Spring WebSocket (STOMP). Includes REST endpoints for fetching history, editing messages, and soft-deleting messages.

### Authentication & Authorization
- **WebSocket Endpoint**: `/ws` (Native STOMP, no SockJS).
- **CONNECT**: Clients pass their JWT in the `Authorization: Bearer <token>` STOMP header. The JWT is validated, and the user principal is bound to the session.
- **SUBSCRIBE**: Clients subscribe to `/topic/projects/{projectId}`. The interceptor verifies the user is a member of that project.
- **SEND**: Handled via `@MessageMapping("/projects/{projectId}/chat.send")`. The `ProjectChatService` validates project membership before saving and broadcasting the message.

### Topics
- Send to: `/app/projects/{projectId}/chat.send`
- Subscribe to: `/topic/projects/{projectId}`

### REST Endpoints
- `GET /api/v1/projects/{projectId}/chat/messages`
- `PATCH /api/v1/projects/{projectId}/chat/messages/{messageId}`
- `DELETE /api/v1/projects/{projectId}/chat/messages/{messageId}`

### Target Collections
- `project_chat_messages`
  - Uses soft-delete (`isDeleted=true`), which automatically blanks out `content` for privacy.
