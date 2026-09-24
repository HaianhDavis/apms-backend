# APMS Backend — Architecture Plan (Revision 2)
**AI Partner & Competitor Analysis System**
*Spring Boot 3 · Java 21 · SQL Server · MongoDB · Neo4j · Spring AI (OpenAI)*

> [!IMPORTANT]
> **Revision 2 changes:** ApprovalTask removed entirely. CompanyProfile has no classification field. Classification lives exclusively in Neo4j relationship types. AI Assistant moved to Phase 2. Domain Design, Entity Roadmap, Database Design, API Roadmap, and Neo4j model fully regenerated.

---

## 0. Project Inspection Summary

| Attribute | Observed Value |
|---|---|
| Root group ID | `com.apms` |
| Artifact ID | `apms-backend` |
| Spring Boot version | `3.5.15` |
| Java version | `21` |
| Entry point | `com.apms.ApmsBackendApplication` |
| Existing packages | None — blank Spring Initializr skeleton |
| `application.properties` | Only `spring.application.name=apms-backend` |
| Dependencies declared | `spring-boot-starter-data-jpa`, `spring-boot-starter-data-mongodb`, `spring-boot-starter-data-neo4j`, `spring-boot-starter-security`, `spring-boot-starter-validation`, `spring-boot-starter-web`, `mssql-jdbc`, `lombok`, DevTools |
| Missing dependencies | `jjwt` (JWT), `mapstruct`, `springdoc-openapi`, `spring-ai-openai-spring-boot-starter`, `testcontainers` — all must be added before Phase 1 |

> **Conclusion:** The project is a clean scaffold. All packages, entities, configurations, and business logic must be built from scratch.

---

## 1. System Architecture

### 1.1 High-Level Overview

```
┌────────────────────────────────────────────────────────────┐
│                        CLIENT LAYER                        │
│        (Web SPA / Mobile / Postman / External API)         │
└───────────────────────────┬────────────────────────────────┘
                            │ HTTPS / REST
┌───────────────────────────▼────────────────────────────────┐
│                    APMS BACKEND (Spring Boot 3)             │
│                                                            │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │  Controller  │  │   Service    │  │   Domain Layer   │  │
│  │  (REST API)  │→ │  (Use Cases) │→ │ (Aggregates,DDD) │  │
│  └──────────────┘  └──────────────┘  └──────────────────┘  │
│                                                            │
│  ┌──────────────────────────────────────────────────────┐  │
│  │                  Repository Layer                    │  │
│  │  JPA Repos (SQL Server) │ Mongo Repos │ Neo4j Repos  │  │
│  └─────────────┬──────────────────┬────────────┬────────┘  │
└────────────────┼──────────────────┼────────────┼───────────┘
                 │                  │            │
       ┌─────────▼──────┐  ┌────────▼──────┐  ┌─▼────────────┐
       │   SQL Server   │  │   MongoDB     │  │    Neo4j     │
       │ (Structured:   │  │ (Flexible AI  │  │ (Company     │
       │  Users, Auth,  │  │  Company      │  │  Graph &     │
       │  Projects,     │  │  Candidates & │  │  Classification│
       │  Scores)       │  │  Profiles)    │  │  via Rels)   │
       └────────────────┘  └───────────────┘  └──────────────┘
                                 │
                          ┌──────▼──────────────┐
                          │  Spring AI Layer     │
                          │  (OpenAI GPT-4o via  │
                          │  Spring AI BOM)      │
                          └─────────────────────┘
                                 │
                   ┌─────────────▼─────────────┐
                   │  Local Filesystem          │
                   │  (Phase 1 file storage)    │
                   │  uploads/ directory        │
                   └───────────────────────────┘
```

### 1.2 Architectural Style

**Layered Monolith with Domain-Driven Design (DDD) tactics.**

- A **monolith** is chosen — appropriate for a Capstone project, avoids distributed systems complexity.
- Internal structure follows **DDD** (aggregates, bounded contexts, domain events) to allow future microservice decomposition.
- **Polyglot Persistence** across three databases — each database serves a distinct role.

> [!IMPORTANT]
> **Neo4j is the single source of truth for company classification.** A company's relationship to the business (PARTNER, COMPETITOR, SUPPLIER, CUSTOMER, POTENTIAL_PARTNER) is determined solely by the relationship type in Neo4j. `CompanyProfile` in MongoDB stores factual company data only — it has **no classification field**.

### 1.3 Why Not Microservices?

Microservices introduce network latency, distributed transactions, service discovery, and operational overhead. A well-structured monolith delivers faster iteration, easier debugging, and clearer code ownership, while preserving the internal domain structure that makes a future split straightforward.

---

## 2. Package Structure

Base package: `com.apms`

```
com.apms
│
├── ApmsBackendApplication.java          ← Entry point (EXISTING)
│
├── config/                              ← All Spring configuration classes
│   ├── security/                        ← SecurityFilterChain, JWT filter
│   ├── database/                        ← DataSource configs (SQL, Mongo, Neo4j)
│   └── web/                             ← CORS, Jackson, OpenAPI config
│
├── common/                              ← Shared utilities (no business logic)
│   ├── exception/                       ← Global exception handler, custom exceptions
│   ├── response/                        ← ApiResponse<T> wrapper, PageResponse<T>
│   ├── enums/                           ← All shared enums (ProjectType, SystemRole, etc.)
│   ├── util/                            ← Date utils, string helpers, UUID generator
│   └── audit/                           ← AuditLogService, AuditAction enum
│
├── domain/                              ← Pure domain objects (no Spring annotations)
│   ├── project/                         ← Project aggregate root
│   ├── company/                         ← CompanyCandidate & CompanyProfile aggregates
│   ├── user/                            ← User & role domain
│   ├── document/                        ← ImportJob & RawDocument domain
│   └── score/                           ← ScoreRule & ScoreSnapshot domain
│
├── module/                              ← Feature modules (one sub-package per bounded context)
│   │
│   ├── auth/                            ← Authentication & Authorization BC
│   │   ├── controller/
│   │   ├── service/
│   │   ├── dto/
│   │   └── repository/                  ← RefreshToken JPA repository
│   │
│   ├── user/                            ← User Management BC
│   │   ├── controller/
│   │   ├── service/
│   │   ├── dto/
│   │   ├── entity/                      ← SQL Server JPA entities: User, Role, Permission, etc.
│   │   └── repository/
│   │
│   ├── project/                         ← Project Lifecycle BC
│   │   ├── controller/
│   │   ├── service/
│   │   ├── dto/
│   │   ├── entity/                      ← SQL Server: Project, ProjectMember
│   │   └── repository/
│   │
│   ├── document/                        ← Document Import & Manual Input BC
│   │   ├── controller/
│   │   ├── service/
│   │   ├── dto/
│   │   ├── entity/                      ← SQL Server: ImportJob
│   │   ├── document/                    ← MongoDB: RawDocument
│   │   └── repository/
│   │
│   ├── candidate/                       ← Company Candidate BC
│   │   ├── controller/
│   │   ├── service/
│   │   ├── dto/
│   │   ├── document/                    ← MongoDB: CompanyCandidate
│   │   └── repository/
│   │
│   ├── profile/                         ← Official Company Profile BC
│   │   ├── controller/
│   │   ├── service/
│   │   ├── dto/
│   │   ├── document/                    ← MongoDB: CompanyProfile (no classification field)
│   │   └── repository/
│   │
│   ├── score/                           ← Scoring BC
│   │   ├── controller/
│   │   ├── service/
│   │   ├── dto/
│   │   ├── entity/                      ← SQL Server: ScoreRule, ScoreSnapshot
│   │   └── repository/
│   │
│   ├── graph/                           ← Relationship Graph BC
│   │   ├── controller/
│   │   ├── service/
│   │   ├── dto/
│   │   ├── node/                        ← Neo4j: CompanyNode, relationship entities
│   │   └── repository/
│   │
│   ├── ai/                              ← AI Integration BC
│   │   ├── controller/
│   │   ├── service/                     ← ChatClient wrapper, prompt templates
│   │   └── dto/
│   │
│   └── dashboard/                       ← Dashboard & Reporting BC (read-only aggregation)
│       ├── controller/
│       ├── service/
│       └── dto/
│
└── infrastructure/                      ← Technical adapters
    ├── storage/                         ← StorageService interface
    │   └── local/                       ← LocalFileStorageService → uploads/ directory
    ├── ai/                              ← Spring AI ChatClient config, prompt templates
    │   └── openai/                      ← OpenAI model config (GPT-4o, temperature, etc.)
    └── event/                           ← Internal domain event bus (ApplicationEvent)
```

### Why This Structure?

- **`config/`** — Separates Spring wiring from business logic.
- **`common/`** — Shared response wrappers, exceptions, and enums used across all modules.
- **`domain/`** — Pure domain model (no framework coupling). Enables unit testing without Spring context.
- **`module/`** — Each sub-package is a **Bounded Context** (Package by Feature strategy).
- **No `approval/` module** — The review workflow is embedded directly in the `candidate/` module via `CandidateStatus` state machine. No separate approval table or service needed.
- **`infrastructure/`** — Isolates external dependencies (Spring AI, local filesystem, events).

---

## 3. Layer Architecture

```
┌─────────────────────────────────────────────────────────┐
│  PRESENTATION LAYER  (module/*/controller)              │
│  - REST Controllers                                     │
│  - Input validation (@Valid, @RequestBody)              │
│  - Authentication context (@AuthenticationPrincipal)    │
│  - Maps request DTOs → calls Service layer              │
└────────────────────────┬────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────┐
│  APPLICATION / SERVICE LAYER  (module/*/service)        │
│  - Orchestrates use cases (business workflows)          │
│  - Calls domain objects for business rules              │
│  - Manages transactions (@Transactional)                │
│  - Maps between domain ↔ DTO (via MapStruct)            │
│  - Publishes domain events                              │
└────────────────────────┬────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────┐
│  DOMAIN LAYER  (domain/*)                               │
│  - Aggregate roots, entities, value objects             │
│  - Domain rules (pure Java, no Spring)                  │
│  - Domain events                                        │
└────────────────────────┬────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────┐
│  PERSISTENCE LAYER  (module/*/repository)               │
│  - JPA Repositories → SQL Server                        │
│  - MongoRepository → MongoDB                            │
│  - Neo4jRepository → Neo4j                              │
│  - No business logic — data access only                 │
└─────────────────────────────────────────────────────────┘
```

### Strict Dependency Rule

```
Controller → Service → Domain ← Repository
```

Controllers never call Repositories directly. Domain objects never import Spring or persistence annotations.

---

## 4. Module Breakdown

| Module | Bounded Context | Primary DB | Key Responsibility |
|---|---|---|---|
| `auth` | Authentication | SQL Server | Login, short-lived JWT (15 min) + refresh token (SQL), logout |
| `user` | User Management | SQL Server | CRUD users, assign roles, permissions |
| `project` | Project Lifecycle | SQL Server | Create projects (with projectType/targetCompanyName/targetCompanyProfileId), manage members, assign tasks |
| `document` | Document Import & Manual Input | SQL Server + MongoDB | File upload (local FS) OR manual input → AI extraction → RawDocument |
| `candidate` | Company Candidate | MongoDB | Store/validate company data; **BD Manager reviews via CandidateStatus (approve/reject)** |
| `profile` | Company Profile | MongoDB | Official company data profiles — **no classification field** |
| `score` | Scoring | SQL Server | Score rules, compute and snapshot scores (`factors_json`) |
| `graph` | Relationship Graph | Neo4j | **Source of truth for classification** — relationship type = company classification |
| `ai` | AI Integration | Spring AI → OpenAI | Extract info via GPT-4o, suggest relationship type, AI Assistant (Phase 2) |
| `dashboard` | Reporting | All 3 DBs | Aggregate KPIs, relationship views, competitor/partner reports |

---

## 5. Domain Design (Revised)

### 5.1 Core Aggregates

An **Aggregate** is a cluster of domain objects treated as a single unit for data changes.

---

#### Aggregate 1: `Project` (SQL Server)

**Aggregate Root:** `Project`

**Key Fields (explicit):**
- `projectType` — one of: `UPDATE_EXISTING_COMPANY`, `RESEARCH_NEW_COMPANY`
- `targetCompanyProfileId` — String (nullable) — MongoDB `companyId` UUID of the target company when `projectType = UPDATE_EXISTING_COMPANY`
- `targetCompanyName` — String — company name (for `RESEARCH_NEW_COMPANY`) ; copied from selected profile when `UPDATE_EXISTING_COMPANY`

**Business Invariants:**
- If `projectType == UPDATE_EXISTING_COMPANY` → `targetCompanyProfileId` must not be null; `targetCompanyName` is copied from the selected `CompanyProfile`.
- If `projectType == RESEARCH_NEW_COMPANY` → `targetCompanyProfileId` must be null; `targetCompanyName` is a single company name.
- If `projectType == RESEARCH_MULTIPLE_COMPANIES` → `targetCompanyProfileId` must be null; `targetCompanyName` is a research scope/query string.
- A project must always have at least one member with `BUSINESS_DEVELOPMENT_MANAGER` role.

**Contained entities:**
- `ProjectMember` — links a `User` to a project with a project-scoped role.

**Database:** SQL Server only.

---

#### Aggregate 2: `ImportJob` (SQL Server) + `RawDocument` (MongoDB)

**Aggregate Root:** `ImportJob` (SQL Server — status/audit tracking)
**Associated document:** `RawDocument` (MongoDB — raw file content + AI extraction output)

**Key Fields:**
- `inputType` — `FILE_UPLOAD` or `MANUAL_INPUT`
- `localFilePath` — path to saved file on local filesystem (nullable, only for `FILE_UPLOAD`)
- `status` — `PENDING → PROCESSING → COMPLETED | FAILED`

**Invariants:**
- An `ImportJob` is always linked to exactly one `Project`.
- Status transitions are strictly enforced: only `PENDING → PROCESSING`, then `PROCESSING → COMPLETED | FAILED`.
- `RawDocument` is created in MongoDB only after a successful import (either file parsed by AI, or manual input captured).
- For `MANUAL_INPUT`, `RawDocument` contains the manually entered text; `aiRawOutput` is null unless staff explicitly triggers `/ai/classify`.

**Split rationale:** `ImportJob` state machine is transactional → SQL Server. Raw content and AI output are schema-less and large → MongoDB.

---

#### Aggregate 3: `CompanyCandidate` (MongoDB)

**Aggregate Root:** `CompanyCandidate`

**This aggregate owns the entire candidate review workflow.** There is no separate ApprovalTask aggregate or table.

**CandidateStatus state machine:**
```
DRAFT
  │
  └─ (STAFF submits) ──► PENDING_REVIEW
                               │
              ┌────────────────┴────────────────┐
              │                                 │
   (BD_MGR approves)                  (BD_MGR rejects)
              │                                 │
              ▼                                 ▼
          APPROVED                          REJECTED
              │                                 │
  (triggers profile/graph/score)    (STAFF corrects & resubmits)
                                               │
                                               ▼
                                          CORRECTED
                                               │
                                    (auto → PENDING_REVIEW)
```

**Review & Versioning fields embedded directly:**
- `revisionNumber` — Integer, starts at 1, increments on CORRECTED → PENDING_REVIEW to track reject/correct cycles
- `reviewedBy` — userId of BD Manager who acted
- `reviewedAt` — timestamp of review action
- `rejectionReason` — String, set by BD Manager on rejection

**Key company data fields:**
- `companyName`, `industry`, `foundedYear`, `description`, `headquarters`, `website`
- `financials {}` — revenue, employees, funding rounds
- `contacts []` — key contacts
- `products []` — products/services
- `relationships []` — AI-extracted inter-company relationships
- `missingFields []` — detected gaps
- `duplicateWarnings []` — potential duplicates detected
- `suggestedRelationshipType` — AI-suggested relationship type (maps to a Neo4j RelationshipType)
- `aiConfidenceScore` — nullable for manual input

**Invariants:**
- A candidate belongs to exactly one `Project`.
- Only staff can submit; only BD Manager can approve or reject.
- `suggestedRelationshipType` is a suggestion only — BD Manager may override it via the approve endpoint before triggering the graph write.
- **Profile Creation Strategy on Approval:**
  - If `projectType` = `RESEARCH_NEW_COMPANY` or `RESEARCH_MULTIPLE_COMPANIES`: Create a *new* `CompanyProfile` and generate a *new* `companyId` UUID.
  - If `projectType` = `UPDATE_EXISTING_COMPANY`: Update/enrich the *existing* `CompanyProfile`. Reuse `targetCompanyProfileId` as the `companyId`. Do NOT create a duplicate profile.

---

#### Aggregate 4: `CompanyProfile` (MongoDB)

**Aggregate Root:** `CompanyProfile`

**This document is a neutral factual record of a company. It has NO classification field.**

**Rationale:** A single company may hold multiple relationship types with the business simultaneously (e.g., a firm can be both a `SUPPLIER_OF` and a `PARTNER_WITH`). Classification via a single field cannot represent this. The Neo4j relationship graph is the sole source of truth for how a company relates to the business.

**Key Fields:**
- `companyId` — UUID (String), generated at creation — **the universal cross-database identity key**
- `companyName`, `industry`, `officialWebsite`, `headquarters`
- `financials {}` — structured financial data
- `keyContacts []` — key people
- `products []` — product/service catalog
- `tags []` — free-form metadata tags
- `sourceCandidateIds []` — audit trail of which candidates contributed to this profile

**Invariants:**
- `companyId` UUID is generated once at first profile creation and never changes.
- A profile can be enriched multiple times via `UPDATE_EXISTING_COMPANY` projects.
- **No `classification` field exists on this document.**

---

#### Aggregate 5: `ScoreSnapshot` (SQL Server)

**Aggregate Root:** `ScoreSnapshot`

**Invariants:**
- A snapshot is created each time a `CompanyCandidate` is approved (new or update).
- Computed from active `ScoreRule` definitions at the time of approval.
- `factors_json` stores the per-rule breakdown as `NVARCHAR(MAX)` JSON.
- Historical snapshots are immutable — never modified after creation.
- References `companyId` UUID from `CompanyProfile` (cross-database soft reference).

---

#### Aggregate 6: `CompanyNode` + `CompanyRelationship` (Neo4j)

**Aggregate Roots:** `CompanyNode` (Neo4j node) and `CompanyRelationship` (domain concept mapped to Neo4j edge)

**This is the sole source of truth for company relationships.**

**Design principle:** The **relationship type** between two `Company` nodes determines their connection. There is no separate "classification" property on the node or on `CompanyProfile`.

**Key Node Properties (`CompanyNode`):**
- `companyId` — UUID, same as `CompanyProfile.companyId` — the universal cross-DB identity
- `name` — company name (denormalized for graph query display)
- `industry` — industry sector

**Domain Concept: `CompanyRelationship`**
Represents the directed or bidirectional edge between two companies in the graph.
- `sourceCompanyId` — UUID of the origin company
- `targetCompanyId` — UUID of the destination company
- `relationshipType` — One of: `PARTNER_WITH`, `COMPETITOR_OF`, `SUPPLIER_OF`, `CUSTOMER_OF`, `POTENTIAL_PARTNER_OF`
- `confidenceScore` — AI confidence score (especially for `POTENTIAL_PARTNER_OF`)
- `confirmedBy` — userId of the BD Manager who confirmed
- `confirmedAt` — timestamp
- `notes` — optional freetext notes

**Invariants:**
- A `CompanyNode` and `CompanyRelationship` are created or updated only when a `CompanyCandidate` is approved.
- The relationship type is determined by `suggestedRelationshipType` on the approved candidate (BD Manager may override).
- A company can have **multiple relationship types simultaneously** (e.g., the same company node can be linked by both `SUPPLIER_OF` and `PARTNER_WITH`).

---

### 5.2 Main Bounded Contexts

```
┌──────────────────────────────────────────────────────────┐
│  IDENTITY & ACCESS CONTEXT                               │
│  Users, Roles, Permissions, JWT Auth, Refresh Tokens     │
│  → SQL Server                                            │
└──────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│  PROJECT MANAGEMENT CONTEXT                              │
│  Projects (projectType / targetCompanyProfileId /        │
│  targetCompanyName), Members, Research Tasks             │
│  → SQL Server                                            │
└──────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│  DOCUMENT INGESTION CONTEXT                              │
│  File Upload OR Manual Input, ImportJob (FILE_UPLOAD /   │
│  MANUAL_INPUT), Spring AI Extraction, RawDocument        │
│  → SQL Server (ImportJob) + MongoDB (RawDocument)        │
│  + Local Filesystem (file bytes)                         │
└──────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│  COMPANY INTELLIGENCE CONTEXT                            │
│  CompanyCandidate (with embedded review workflow:        │
│  DRAFT → PENDING_REVIEW → APPROVED | REJECTED →         │
│  CORRECTED), CompanyProfile (neutral factual data)       │
│  → MongoDB                                               │
└──────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│  SCORING CONTEXT                                         │
│  ScoreRule, ScoreSnapshot (with factors_json)            │
│  → SQL Server                                            │
└──────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│  RELATIONSHIP GRAPH CONTEXT                              │
│  CompanyNode, CompanyRelationship                        │
│  (PARTNER_WITH / COMPETITOR_OF / SUPPLIER_OF /           │
│  CUSTOMER_OF / POTENTIAL_PARTNER_OF)                     │
│  → Neo4j                                                 │
└──────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│  AI INTEGRATION CONTEXT                                  │
│  Prompt Construction, Spring AI ChatClient,              │
│  Structured Output Binding, AI Assistant Chat            │
│  → Spring AI → OpenAI GPT-4o                            │
└──────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│  DASHBOARD & REPORTING CONTEXT (Read-only)               │
│  Aggregated KPIs for Business Owner                      │
│  → All 3 DBs (read-only queries)                         │
└──────────────────────────────────────────────────────────┘
```

---

## 6. Database Responsibility Design (Revised)

### 6.1 SQL Server — Structured, Transactional, Auditable

**Why SQL Server here?** ACID compliance, relational integrity, role-based access control, audit trails, and structured workflow state all require a relational database.

| Table | Purpose | Key Relationships |
|---|---|---|
| `users` | System user accounts | → `user_roles` |
| `roles` | Role definitions | → `role_permissions` |
| `permissions` | Fine-grained action codes | ← `role_permissions` |
| `user_roles` | M:N users ↔ roles | |
| `role_permissions` | M:N roles ↔ permissions | |
| `refresh_tokens` | Hashed refresh tokens with expiry and revocation flag | → `users` |
| `projects` | Project header with `projectType`, `targetCompanyProfileId`, `targetCompanyName` | → `project_members`, `import_jobs` |
| `project_members` | Users participating in a project | → `users`, `projects` |
| `import_jobs` | Tracks both FILE_UPLOAD and MANUAL_INPUT jobs with status lifecycle | → `projects` |
| `score_rules` | Configurable scoring criteria (weight, category) | |
| `score_snapshots` | Historical score per company per approval event; `factors_json` stores breakdown | → `companyId` (UUID — cross-DB ref to MongoDB + Neo4j) |
| `audit_logs` | Immutable system event log (action, entity, timestamp) | → `users` |

> **No `approval_tasks` table.** The review workflow (approve/reject/correct) is managed entirely through `CandidateStatus` on the `CompanyCandidate` MongoDB document.

### 6.2 MongoDB — Flexible, Schema-Less, AI-Friendly

**Why MongoDB here?** AI-extracted company data is semi-structured and evolves as AI models improve. Embedded arrays (contacts, products, funding rounds) avoid costly schema migrations.

| Collection | Purpose | Key Fields / References |
|---|---|---|
| `raw_documents` | Uploaded file content or manually entered text + AI extraction output | `importJobId` (SQL ref, String), `projectId` (SQL ref, String), `inputType` |
| `company_candidates` | Company data under review — **embeds the review workflow** (status, rejectionReason, reviewedBy, reviewedAt) | `projectId` (SQL ref), `importJobId` (SQL ref, nullable), `suggestedClassification` |
| `company_profiles` | Official, approved company data — **no classification field** | `companyId` (UUID — universal cross-DB key) |

> **Classification does not exist in MongoDB.** `CompanyProfile` contains only factual company data. Classification is determined by Neo4j relationship type.

### 6.3 Neo4j — Graph, Traversal & Relationship Authority

**Why Neo4j here?** Graph traversal queries ("find all second-degree potential partners through shared suppliers") are native to Neo4j Cypher but would require complex recursive SQL JOINs. Most critically, **Neo4j is the single source of truth for company relationships**.

**Node:**

| Label | Key Properties | Description |
|---|---|---|
| `Company` | `companyId` (UUID, indexed), `name`, `industry` | Represents any company in the system |

**Relationships (`CompanyRelationship` Domain Concept):**

| Relationship Type | Direction | Key Properties | Business Meaning |
|---|---|---|---|
| `PARTNER_WITH` | `(A)-[:PARTNER_WITH]-(B)` | `since`, `contractType`, `confirmedBy`, `confirmedAt` | Strategic partnership |
| `COMPETITOR_OF` | `(A)-[:COMPETITOR_OF]->(B)` | `since`, `marketOverlap`, `confirmedBy`, `confirmedAt` | Competing in same market |
| `SUPPLIER_OF` | `(A)-[:SUPPLIER_OF]->(B)` | `since`, `category`, `confirmedBy`, `confirmedAt` | A supplies B |
| `CUSTOMER_OF` | `(A)-[:CUSTOMER_OF]->(B)` | `since`, `tier`, `confirmedBy`, `confirmedAt` | A is a customer of B |
| `POTENTIAL_PARTNER_OF` | `(A)-[:POTENTIAL_PARTNER_OF]->(B)` | `confidenceScore`, `confirmedBy`, `confirmedAt`, `notes` | AI-suggested future partner |

**Key Design Rules:**
- A `CompanyRelationship` (edge) is created **only** when a `CompanyCandidate` is approved.
- `Company.companyId` = `CompanyProfile.companyId` = `ScoreSnapshot.companyId` — the universal UUID.
- A company can hold **multiple relationship types simultaneously** — the graph naturally supports this.
- When reading a company's relationship to the business, query Neo4j for all relationship types connected to that `companyId`.

**Example Cypher Queries:**
```cypher
// Get all companies and their relationship to the business
MATCH (a:Company {companyId: $ourCompanyId})-[r]-(b:Company)
RETURN b.name, type(r) AS relationshipType

// Find second-degree potential partners
MATCH (a:Company)-[:PARTNER_WITH]-(mid:Company)-[:POTENTIAL_PARTNER_OF]->(c:Company)
WHERE a.companyId = $ourCompanyId
RETURN DISTINCT c.name, c.companyId

// Get all competitor nodes
MATCH (a:Company {companyId: $ourCompanyId})-[:COMPETITOR_OF]->(comp:Company)
RETURN comp.name, comp.industry
```

---

## 7. Entity Roadmap (Revised)

### 7.1 SQL Server JPA Entities

| Entity Class | Table | Key Fields |
|---|---|---|
| `User` | `users` | id (Long), username, email, passwordHash, isActive, createdAt |
| `Role` | `roles` | id (Long), name (`SystemRole` enum) |
| `Permission` | `permissions` | id (Long), code, description |
| `UserRole` | `user_roles` | userId (FK), roleId (FK) — composite PK |
| `RolePermission` | `role_permissions` | roleId (FK), permissionId (FK) — composite PK |
| `RefreshToken` | `refresh_tokens` | id (Long), tokenHash (String), userId (FK), expiresAt, isRevoked (Boolean), createdAt |
| `Project` | `projects` | id (Long), name, **projectType** (`ProjectType` enum), status (`ProjectStatus` enum), **targetCompanyName** (String), **targetCompanyProfileId** (String nullable — UUID ref to MongoDB CompanyProfile), createdBy (userId FK), createdAt |
| `ProjectMember` | `project_members` | id (Long), projectId (FK), userId (FK), memberRole (`MemberRole` enum) |
| `ImportJob` | `import_jobs` | id (Long), projectId (FK), **inputType** (`InputType` enum: FILE_UPLOAD \| MANUAL_INPUT), fileName (nullable), localFilePath (nullable), status (`ImportJobStatus` enum), rawDocumentId (String nullable — MongoDB ObjectId ref), createdAt |
| `ScoreRule` | `score_rules` | id (Long), ruleName, weight (Double), category, isActive (Boolean) |
| `ScoreSnapshot` | `score_snapshots` | id (Long), **companyId** (String — UUID, cross-DB ref to MongoDB CompanyProfile + Neo4j Company node), totalScore (Double), **factors_json** (NVARCHAR(MAX) — per-rule breakdown as JSON), snapshotDate, projectId (Long — which project triggered this) |
| `AuditLog` | `audit_logs` | id (Long), userId (FK), action (`AuditAction` enum), entityType (String), entityId (String), detail (NVARCHAR(MAX) JSON), timestamp |

> **No `ApprovalTask` entity.** Review workflow is managed via `CandidateStatus` on the MongoDB `CompanyCandidate` document.

### 7.2 MongoDB Documents (Revised)

#### `RawDocument` → collection: `raw_documents`

| Field | Type | Notes |
|---|---|---|
| `id` | ObjectId | MongoDB auto-generated |
| `importJobId` | String | SQL Server `import_jobs.id` (cross-DB soft ref) |
| `projectId` | String | SQL Server `projects.id` (cross-DB soft ref) |
| `inputType` | String (enum) | `FILE_UPLOAD` or `MANUAL_INPUT` |
| `originalFileName` | String | Nullable — only for FILE_UPLOAD |
| `localFilePath` | String | Nullable — relative path in uploads/ directory |
| `contentType` | String | Nullable — MIME type for uploaded files |
| `extractedText` | String | Full text content (from file parse or manual entry) |
| `aiRawOutput` | String | Raw JSON response from Spring AI — nullable for manual input |
| `extractedAt` | DateTime | |

#### `CompanyCandidate` → collection: `company_candidates`

| Field | Type | Notes |
|---|---|---|
| `id` | ObjectId | MongoDB auto-generated |
| `projectId` | String | SQL Server project ID (cross-DB soft ref) |
| `importJobId` | String | SQL Server import job ID — nullable (manual input may not have a job) |
| `inputType` | String (enum) | `FILE_UPLOAD` or `MANUAL_INPUT` |
| `status` | String (enum) | `CandidateStatus`: DRAFT, PENDING_REVIEW, APPROVED, REJECTED, CORRECTED |
| `revisionNumber` | Integer | Tracks reject → correct → resubmit iterations (starts at 1) |
| `suggestedRelationshipType` | String (enum) | Maps to `RelationshipType` — nullable; AI suggestion or BD Manager override |
| `reviewedBy` | String | userId of BD Manager who approved or rejected — nullable |
| `reviewedAt` | DateTime | Nullable |
| `rejectionReason` | String | Nullable — set by BD Manager on rejection |
| `companyName` | String | |
| `industry` | String | |
| `foundedYear` | Integer | Nullable |
| `description` | String | |
| `headquarters` | Object | `{address, city, country}` |
| `website` | String | |
| `financials` | Object | `{revenue, employees, fundingRounds []}` |
| `contacts` | Array | `[{name, title, email, phone}]` |
| `products` | Array | `[{name, category, description}]` |
| `relationships` | Array | AI-extracted inter-company relationships `[{targetCompanyName, relationshipType, description}]` |
| `missingFields` | Array | Fields detected as missing by AI |
| `duplicateWarnings` | Array | Potential duplicate company names detected |
| `aiConfidenceScore` | Double | Nullable for manual input |
| `createdAt` | DateTime | |
| `updatedAt` | DateTime | |

#### `CompanyProfile` → collection: `company_profiles`

> [!IMPORTANT]
> **No `classification` field on this document.** Relationship types are determined exclusively by Neo4j edges.

| Field | Type | Notes |
|---|---|---|
| `id` | ObjectId | MongoDB auto-generated |
| `companyId` | String (UUID) | **Universal cross-DB identity key** — used in Neo4j node + SQL ScoreSnapshot |
| `companyName` | String | |
| `industry` | String | |
| `officialWebsite` | String | |
| `headquarters` | Object | `{address, city, country}` |
| `financials` | Object | `{revenue, employees, fundingRounds []}` |
| `keyContacts` | Array | `[{name, title, email}]` |
| `products` | Array | `[{name, category, description}]` |
| `tags` | Array | Free-form metadata tags |
| `sourceCandidateIds` | Array | MongoDB ObjectId refs of all contributing candidates (audit trail) |
| `createdAt` | DateTime | Set once at creation |
| `updatedAt` | DateTime | Updated on each enrichment |

### 7.3 Neo4j Nodes and Relationships (Revised)

#### Node: `Company`

| Property | Type | Notes |
|---|---|---|
| `companyId` | String (UUID) | **Indexed** — universal cross-DB key matching `CompanyProfile.companyId` |
| `name` | String | Denormalized from CompanyProfile for display |
| `industry` | String | Denormalized for graph filtering |

#### Relationships (`CompanyRelationship`)

| Relationship | From → To | Properties | Created When |
|---|---|---|---|
| `PARTNER_WITH` | Company — Company (bidirectional) | `since`, `contractType`, `confirmedBy`, `confirmedAt`, `notes` | Candidate approved with `suggestedRelationshipType = PARTNER_WITH` |
| `COMPETITOR_OF` | Company → Company | `since`, `marketOverlap`, `confirmedBy`, `confirmedAt`, `notes` | Candidate approved with `suggestedRelationshipType = COMPETITOR_OF` |
| `SUPPLIER_OF` | Company → Company | `since`, `category`, `confirmedBy`, `confirmedAt`, `notes` | Candidate approved with `suggestedRelationshipType = SUPPLIER_OF` |
| `CUSTOMER_OF` | Company → Company | `since`, `tier`, `confirmedBy`, `confirmedAt`, `notes` | Candidate approved with `suggestedRelationshipType = CUSTOMER_OF` |
| `POTENTIAL_PARTNER_OF` | Company → Company | `confidenceScore`, `confirmedBy`, `confirmedAt`, `notes` | Candidate approved with `suggestedRelationshipType = POTENTIAL_PARTNER_OF` |

#### Neo4j Index Strategy

```cypher
CREATE INDEX company_companyId FOR (c:Company) ON (c.companyId);
CREATE INDEX company_name FOR (c:Company) ON (c.name);
CREATE INDEX company_industry FOR (c:Company) ON (c.industry);
```

---

## 8. Important Enums (Revised)

| Enum | Values | Location | Used By |
|---|---|---|---|
| `ProjectType` | `UPDATE_EXISTING_COMPANY`, `RESEARCH_NEW_COMPANY`, `RESEARCH_MULTIPLE_COMPANIES` | `common/enums` | Project entity |
| `ProjectStatus` | `DRAFT`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED` | `common/enums` | Project entity |
| `SystemRole` | `BUSINESS_OWNER`, `BUSINESS_DEVELOPMENT_MANAGER`, `RESEARCH_STAFF` | `common/enums` | User/Role entities |
| `RelationshipType` | `PARTNER_WITH`, `COMPETITOR_OF`, `SUPPLIER_OF`, `CUSTOMER_OF`, `POTENTIAL_PARTNER_OF` | `common/enums` | `CompanyCandidate.suggestedRelationshipType`, `CompanyRelationship`, Neo4j edge labels |
| `CandidateStatus` | `DRAFT`, `PENDING_REVIEW`, `APPROVED`, `REJECTED`, `CORRECTED` | `common/enums` | CompanyCandidate document — **drives the review workflow** |
| `InputType` | `FILE_UPLOAD`, `MANUAL_INPUT` | `common/enums` | ImportJob entity, RawDocument, CompanyCandidate |
| `ImportJobStatus` | `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED` | `common/enums` | ImportJob entity |
| `MemberRole` | `MANAGER`, `STAFF` | `common/enums` | ProjectMember entity (project-scoped role) |
| `AuditAction` | `CREATE_PROJECT`, `UPLOAD_DOCUMENT`, `MANUAL_INPUT`, `SUBMIT_CANDIDATE`, `APPROVE_CANDIDATE`, `REJECT_CANDIDATE`, `CORRECT_CANDIDATE`, `CREATE_PROFILE`, `UPDATE_PROFILE`, `CREATE_GRAPH_NODE`, `CREATE_RELATIONSHIP`, `LOGIN`, `LOGOUT`, `REFRESH_TOKEN` | `common/enums` | AuditLog entity |

> **No `ApprovalStatus` enum.** Review state is represented by `CandidateStatus`.

---

## 9. Cross-Database ID Mapping Strategy

### 9.1 The Universal Company Identity: `companyId` UUID

```
┌─────────────────────────────────────────────────────────────────┐
│                    companyId  (UUID String)                     │
│                                                                 │
│  MongoDB: CompanyProfile.companyId  ←──────────────────────┐   │
│                                                             │   │
│  Neo4j:   Company node { companyId }  ←────────────────────┤   │
│                                                             │   │
│  SQL:     score_snapshots.companyId  ←─────────────────────┘   │
│                                                                 │
│  Generated ONCE at CompanyProfile creation. Never changes.      │
└─────────────────────────────────────────────────────────────────┘
```

### 9.2 ID Reference Table

| From | To | Reference Type | Field |
|---|---|---|---|
| MongoDB `company_candidates` | SQL `projects` | Cross-DB soft ref | `projectId: String` (SQL Long as String) |
| MongoDB `company_candidates` | SQL `import_jobs` | Cross-DB soft ref | `importJobId: String` (nullable) |
| MongoDB `raw_documents` | SQL `import_jobs` | Cross-DB soft ref | `importJobId: String` |
| SQL `import_jobs` | MongoDB `raw_documents` | Cross-DB soft ref | `rawDocumentId: String` (MongoDB ObjectId) |
| SQL `projects` | MongoDB `company_profiles` | Cross-DB soft ref | `targetCompanyProfileId: String` (UUID, nullable) |
| SQL `score_snapshots` | MongoDB `company_profiles` + Neo4j | Cross-DB identity | `companyId: String` (UUID) |
| Neo4j `Company` node | MongoDB `company_profiles` | Cross-DB identity | `companyId: String` (UUID) |

### 9.3 Reference Integrity Enforcement

> **No database-level foreign key enforcement across databases.** All cross-database reference integrity is enforced at the **application service layer**:
- Before writing to Neo4j: validate `companyId` exists in MongoDB `company_profiles`.
- Before creating a project with `UPDATE_EXISTING_COMPANY`: validate `targetCompanyProfileId` exists in MongoDB.
- Before creating a `ScoreSnapshot`: validate `companyId` exists in MongoDB.

### 9.4 Review Workflow — No Cross-DB Approval Reference

The old design had SQL `approval_tasks` referencing MongoDB `company_candidates` via a soft foreign key. This is eliminated. The review flow is now entirely within the MongoDB `company_candidate` document:

```
BD Manager calls: POST /candidates/{id}/approve
→ CandidateService reads CompanyCandidate from MongoDB
→ Validates status == PENDING_REVIEW
→ Updates status = APPROVED, reviewedBy, reviewedAt in MongoDB (single document write)
→ Publishes CandidateApprovedEvent (Spring ApplicationEvent, AFTER_COMMIT)
  → ProfileService: create/update CompanyProfile in MongoDB
  → GraphService: upsert Company node + create relationship in Neo4j
  → ScoreService: compute and save ScoreSnapshot in SQL Server
```

---

## 10. Transaction Boundaries

### 10.1 SQL Server Transactions (`@Transactional`)

All SQL writes are ACID-wrapped:
- Creating a `Project` + inserting creator as `ProjectMember` — one transaction.
- Saving a `ScoreSnapshot` + writing `AuditLog` — one transaction.
- Refresh token operations (create, rotate, revoke) — one transaction.

### 10.2 MongoDB Operations

Single-document writes are atomic in MongoDB by default. Strategy:
- Prefer single-document writes (embed review state directly on `CompanyCandidate`).
- For the `candidate approve` flow: update `CompanyCandidate` (single doc write) → publish domain event → remaining writes handled by listeners.

### 10.3 Neo4j Transactions

Neo4j graph writes (upsert node + create relationship) are wrapped in a single Neo4j transaction via Spring Data Neo4j.

### 10.4 Cross-Database Approval Consistency

**Scenario: BD Manager approves a `CompanyCandidate`**

```
Step 1 (Sync — MongoDB):
  CandidateService.approve()
    → Update CompanyCandidate.status = APPROVED (single Mongo doc write)
    → Write AuditLog to SQL Server (@Transactional on SQL datasource)

Step 2 (Async — via @TransactionalEventListener(AFTER_COMMIT)):
  CandidateApprovedEvent published after MongoDB write completes
    → ProfileService: upsert CompanyProfile in MongoDB
        (create new if RESEARCH_NEW/MULTIPLE; update/enrich if UPDATE_EXISTING)
    → GraphService: MERGE Company node in Neo4j, CREATE relationship
    → ScoreService: compute score from ScoreRules, insert ScoreSnapshot in SQL

Step 3 (Compensation on failure):
  If ProfileService fails → log error, mark candidate with PROFILE_CREATION_FAILED flag
  If GraphService fails → retry idempotently (MERGE is safe); alert if repeated failures
  If ScoreService fails → retry; ScoreSnapshot creation is independent of profile/graph
```

---

## 11. Data Consistency Strategy (Revised)

| Concern | Strategy |
|---|---|
| Cross-DB write on approval | `@TransactionalEventListener(AFTER_COMMIT)` — Neo4j + SQL score write only after MongoDB candidate status committed |
| Eventual consistency | Accepted for Neo4j and SQL score writes — idempotent retries |
| Candidate review atomicity | Single MongoDB document update — no cross-DB transaction needed |
| No `approval_tasks` table | Review state is entirely on `CompanyCandidate.status` — no synchronization needed |
| Profile has no classification | Classification queried from Neo4j at read time; never stored on profile |
| companyId UUID uniqueness | Generated via `UUID.randomUUID()` once at profile creation; validated on every cross-DB write |
| Score consistency | `factors_json` snapshot created in SQL within same Spring transaction as `AuditLog` |
| Refresh token security | Stored hashed (`BCrypt`); rotated on each use; revoked on logout; all on SQL Server |
| Access token expiry | 15-minute access token — no blacklist needed; expiry enforces invalidation |
| File storage integrity | `ImportJob.localFilePath` updated only after successful file write; file deleted on job failure |
| Manual input consistency | `ImportJob` (MANUAL_INPUT) + `CompanyCandidate` created atomically within the same Mongo session |
| Idempotency for AI extraction | Check `ImportJob.status` before retrying; Spring AI retry configured per call |
| Duplicate detection | Before candidate creation, Spring AI checks `company_profiles` for similar company names |

---

## 12. API Roadmap (Revised)

### Base URL: `/api/v1`

#### 12.1 Auth Module

| Method | Path | Role | Description |
|---|---|---|---|
| POST | `/auth/login` | Public | Authenticate; returns access token (15 min) + refresh token (7 days) |
| POST | `/auth/refresh` | Public (with refresh token body) | Exchange valid refresh token → new access + rotated refresh token |
| POST | `/auth/logout` | Authenticated | Revoke current refresh token (`refresh_tokens.is_revoked = true`) |

#### 12.2 User Module

| Method | Path | Role | Description |
|---|---|---|---|
| GET | `/users` | OWNER, BD_MGR | List all users (paginated) |
| POST | `/users` | OWNER | Create user account |
| GET | `/users/{id}` | All | Get user by ID |
| PUT | `/users/{id}` | OWNER | Update user details |
| DELETE | `/users/{id}` | OWNER | Deactivate user |
| POST | `/users/{id}/roles` | OWNER | Assign role to user |
| DELETE | `/users/{id}/roles/{roleId}` | OWNER | Remove role from user |

#### 12.3 Project Module

| Method | Path | Role | Description |
|---|---|---|---|
| POST | `/projects` | BD_MGR | Create project — body must include `projectType`, `targetCompanyName`, and optionally `targetCompanyProfileId` (validated per business rules) |
| GET | `/projects` | OWNER, BD_MGR | List projects (paginated, filterable by status/type) |
| GET | `/projects/{id}` | All | Get project detail |
| PUT | `/projects/{id}` | BD_MGR | Update project metadata |
| POST | `/projects/{id}/members` | BD_MGR | Add staff member to project |
| DELETE | `/projects/{id}/members/{userId}` | BD_MGR | Remove member from project |
| GET | `/projects/{id}/members` | BD_MGR, STAFF | List project members |

#### 12.4 Document & Input Module

| Method | Path | Role | Description |
|---|---|---|---|
| POST | `/projects/{id}/documents/upload` | STAFF | Upload document file (multipart); saved to local `uploads/`; async Spring AI extraction |
| POST | `/projects/{id}/documents/manual` | STAFF | Submit manual company data (JSON body); creates `ImportJob` (MANUAL_INPUT) + `CompanyCandidate` directly |
| GET | `/projects/{id}/documents` | BD_MGR, STAFF | List all import jobs for a project (both FILE_UPLOAD and MANUAL_INPUT) |
| GET | `/import-jobs/{jobId}` | BD_MGR, STAFF | Poll import job status — PENDING / PROCESSING / COMPLETED / FAILED |

#### 12.5 Candidate Module (Revised — review workflow embedded)

| Method | Path | Role | Description |
|---|---|---|---|
| GET | `/projects/{id}/candidates` | BD_MGR, STAFF | List all candidates for a project (filterable by `status`) |
| GET | `/projects/{id}/candidates?status=PENDING_REVIEW` | BD_MGR | Filtered view of candidates awaiting review |
| GET | `/candidates/{id}` | BD_MGR, STAFF | Get full candidate detail |
| PUT | `/candidates/{id}` | STAFF | Update/supplement candidate company data (only when status = DRAFT or CORRECTED); increments `revisionNumber` if transitioning from REJECTED |
| POST | `/candidates/{id}/submit` | STAFF | Submit candidate for review: `DRAFT → PENDING_REVIEW` |
| POST | `/candidates/{id}/approve` | BD_MGR | Approve candidate: `PENDING_REVIEW → APPROVED`; body may include `relationshipTypeOverride` to override AI suggestion; triggers profile/graph/score creation |
| POST | `/candidates/{id}/reject` | BD_MGR | Reject candidate: `PENDING_REVIEW → REJECTED`; body must include `rejectionReason` |
| POST | `/candidates/{id}/correct` | STAFF | Mark as corrected after rejection: `REJECTED → CORRECTED → PENDING_REVIEW` |
| POST | `/candidates/{id}/ai-classify` | STAFF | Trigger Spring AI re-classification on a candidate (updates `suggestedRelationshipType`) |

#### 12.6 Profile Module

| Method | Path | Role | Description |
|---|---|---|---|
| GET | `/profiles` | OWNER, BD_MGR | List all official company profiles (paginated) |
| GET | `/profiles/{id}` | All | Get company profile detail (no relationship field — query `/graph/companies/{companyId}` for relationship) |
| GET | `/profiles/search?name={q}` | BD_MGR, STAFF | Search profiles by company name (used when selecting for `UPDATE_EXISTING_COMPANY` projects) |

> **Note:** There is no relationship field in the profile response. To get a company's relationship(s), query the Graph module with the `companyId`.

#### 12.7 Score Module

| Method | Path | Role | Description |
|---|---|---|---|
| GET | `/profiles/{companyId}/scores` | OWNER, BD_MGR | Get full score history for a company (by `companyId` UUID) |
| GET | `/score-rules` | OWNER, BD_MGR | List active score rules |
| POST | `/score-rules` | OWNER | Create a new score rule |
| PUT | `/score-rules/{id}` | OWNER | Update a score rule |
| DELETE | `/score-rules/{id}` | OWNER | Deactivate a score rule |

#### 12.8 Graph Module

| Method | Path | Role | Description |
|---|---|---|---|
| GET | `/graph/companies/{companyId}` | OWNER, BD_MGR | Get company node with all its relationships |
| GET | `/graph/network` | OWNER | Full relationship network (all companies and their relationships) |
| GET | `/graph/competitors` | OWNER | All companies with `COMPETITOR_OF` relationship |
| GET | `/graph/partners` | OWNER | All companies with `PARTNER_WITH` relationship |
| GET | `/graph/suppliers` | OWNER | All companies with `SUPPLIER_OF` relationship |
| GET | `/graph/customers` | OWNER | All companies with `CUSTOMER_OF` relationship |
| GET | `/graph/potential-partners` | OWNER, BD_MGR | All companies with `POTENTIAL_PARTNER_OF` relationship |
| GET | `/graph/traverse/{companyId}?depth={n}` | OWNER | Traverse relationship graph from a company node up to depth N |

#### 12.9 AI Module

| Method | Path | Role | Description |
|---|---|---|---|
| POST | `/ai/extract/{importJobId}` | STAFF | Re-run Spring AI extraction on an existing import job |
| POST | `/ai/recommend-partners` | OWNER, BD_MGR | AI-recommended potential partners based on company graph |
| POST | `/ai/assistant` | OWNER | **AI Assistant chat endpoint (Phase 5 only)** — conversational interface for business intelligence queries |

#### 12.10 Dashboard Module

| Method | Path | Role | Description |
|---|---|---|---|
| GET | `/dashboard/summary` | OWNER | KPIs: total companies, by-type counts, recent activity |
| GET | `/dashboard/competitors` | OWNER | Competitor overview (pulls from Neo4j + MongoDB) |
| GET | `/dashboard/partners` | OWNER | Partner overview |
| GET | `/dashboard/suppliers` | OWNER | Supplier overview |
| GET | `/dashboard/potential-partners` | OWNER, BD_MGR | Potential partner pipeline |
| GET | `/dashboard/reports` | OWNER, BD_MGR | Analytics report data (score trends, project stats) |

---

## 13. Development Roadmap (Revised)

### Phase 1 — Foundation (Sprint 1–2)
**Goal:** Application boots with all three databases connected, security configured, and base auth working.

| Task | Description |
|---|---|
| Add all missing `pom.xml` dependencies | `jjwt-api/impl/jackson`, `mapstruct` + annotation processor, `springdoc-openapi-starter-webmvc-ui`, Spring AI BOM + `spring-ai-openai-spring-boot-starter`, testcontainers BOM |
| Configure multi-datasource | Explicit `@Primary` JPA (`SqlServerConfig`), `MongoConfig`, `Neo4jConfig` — separate `@Configuration` classes; `@EnableJpaRepositories(basePackages)`, `@EnableMongoRepositories(basePackages)` |
| Configure Spring Security | Stateless `SecurityFilterChain`, JWT filter (15 min expiry), `UserDetailsService` backed by SQL Server |
| Implement `refresh_tokens` table + `RefreshToken` entity | Hash with BCrypt; rotation-on-use; `isRevoked` flag |
| Implement `common/` layer | `ApiResponse<T>`, `PageResponse<T>`, global `@ControllerAdvice`, all enums in `common/enums` |
| Implement `auth` module | Login (access + refresh tokens), `/auth/refresh` (token rotation), `/auth/logout` (revocation) |
| Implement `user` module | User CRUD, role assignment, `UserDetailsService` |
| Set up property profiles | `application-dev.properties` (local DBs), `application-prod.properties` |
| Configure `StorageService` | `StorageService` interface + `LocalFileStorageService` → writes to `uploads/` directory |
| Set up `AuditLogService` | Writes to SQL Server `audit_logs` inside same `@Transactional` scope |

**Verification:** App boots; JWT login returns access + refresh tokens; token rotation works; logout revokes token; user CRUD works; all three DB connections healthy.

---

### Phase 2 — Project, Document, Manual Input & AI Extraction (Sprint 3–4)
**Goal:** BD Manager creates projects; Staff uploads documents or enters data manually; Spring AI extracts company info and suggests relationship types.

| Task | Description |
|---|---|
| Implement `project` module | Project CRUD with `projectType` invariant validation; member management; `UPDATE_EXISTING_COMPANY` validation against MongoDB profile search |
| Implement file upload path | `POST /projects/{id}/documents/upload` → `LocalFileStorageService` → `ImportJob` (FILE_UPLOAD) → async Spring AI extraction → `RawDocument` + `CompanyCandidate` in MongoDB |
| Implement Spring AI extraction | `ChatClient` with structured output binding; prompt template extracts all company fields; `suggestedRelationshipType` included in AI response |
| Implement manual input path | `POST /projects/{id}/documents/manual` → `ImportJob` (MANUAL_INPUT) + `CompanyCandidate` created directly from staff JSON; no AI extraction unless staff triggers `/ai/classify` |
| Import job polling | `GET /import-jobs/{jobId}` returns current `ImportJobStatus` |
| Duplicate detection | Before candidate creation, Spring AI (or string similarity) queries `company_profiles` for near-duplicate names |

**Verification:** (1) Upload PDF → job COMPLETED → `CompanyCandidate` with AI-extracted fields + `suggestedRelationshipType`. (2) Manual form → candidate created.

---

### Phase 3 — Candidate Review Workflow, Profile & Graph (Sprint 5–6)
**Goal:** Full review cycle — staff submits, BD Manager approves/rejects, profile and Neo4j graph created on approval.

| Task | Description |
|---|---|
| Implement candidate state machine | `CandidateStatus` transitions enforced in `CandidateService`; submit, approve, reject, correct endpoints. Increments `revisionNumber` on correct. |
| Implement approval domain event | `CandidateApprovedEvent` published via `ApplicationEventPublisher` after MongoDB status update |
| Implement `profile` module | `@TransactionalEventListener(AFTER_COMMIT)` → `RESEARCH_NEW`/`MULTIPLE` = create new `CompanyProfile` & new `companyId`. `UPDATE_EXISTING` = update existing profile & reuse UUID. |
| Implement `graph` module | `@TransactionalEventListener(AFTER_COMMIT)` → `MERGE Company {companyId}` in Neo4j + `CREATE CompanyRelationship` edge based on confirmed relationship type |
| Connect companyId across DBs | After profile creation/update: UUID written to Neo4j node and SQL `score_snapshots.companyId` |
| BD Manager relationship override | `POST /candidates/{id}/approve` body accepts `relationshipTypeOverride` — overrides `suggestedRelationshipType` before Neo4j relationship creation |

**Verification:** Full end-to-end: upload → AI extract → candidate → submit → BD Manager reviews → approve (with/without override) → `CompanyProfile` created/updated correctly per project type → `CompanyNode` and `CompanyRelationship` created in Neo4j → `GET /graph/companies/{companyId}` shows correct relationships.

---

### Phase 4 — Scoring & Graph API (Sprint 7–8)
**Goal:** Score computation works; graph relationship traversal queryable.

| Task | Description |
|---|---|
| Implement `score` module | Score rule management; `ScoreEngine` computes `totalScore` + `factors_json` from active `ScoreRule` records |
| Score snapshot on approval | `@TransactionalEventListener(AFTER_COMMIT)` → `ScoreSnapshot` created in SQL Server with `companyId` UUID |
| Implement full graph REST API | All graph endpoints (competitors, partners, suppliers, customers, potential partners, traversal) |
| AI partner recommendation | `POST /ai/recommend-partners` → Spring AI queries graph context and recommends potential partners |

**Verification:** Approved company has score snapshot queryable via `/profiles/{companyId}/scores`; graph endpoints return correct relationship data from Neo4j.

---

### Phase 5 — Dashboard, AI Assistant & Hardening (Sprint 9–10)
**Goal:** Business Owner has full analytical view; AI Assistant chat operational; system is production-hardened.

| Task | Description |
|---|---|
| Implement `dashboard` module | Aggregate KPIs from all 3 DBs; competitor/partner/supplier/potential-partner summaries |
| Implement AI Assistant | `POST /ai/assistant` — Spring AI ChatClient with conversational memory and system prompt contextualizing the business; answers business intelligence queries |
| Report generation | Analytics report endpoint aggregating score trends and project statistics |
| Performance optimization | Compound indexes on MongoDB (`projectId + status`, `companyId`); Neo4j indexes on `companyId` and `name`; SQL Server indexes on `companyId` in `score_snapshots` |
| Unit tests | Service layer, domain invariant rules |
| Integration tests | MockMvc controller tests; TestContainers for SQL/Mongo/Neo4j |
| Security audit | Confirm role-based access on every endpoint; validate JWT filter covers all paths |
| API documentation | SpringDoc OpenAPI / Swagger UI at `/swagger-ui.html` |
| Environment profiles | `application-dev.properties`, `application-prod.properties` |
| Logging & monitoring | SLF4J structured logging; Spring Boot Actuator health endpoints |

---

## 14. Risks and Architectural Trade-offs (Revised)

| Risk | Impact | Mitigation |
|---|---|---|
| Cross-DB consistency on approval (Mongo + Neo4j + SQL) | High | `@TransactionalEventListener(AFTER_COMMIT)` + idempotent retries + compensating actions |
| Spring AI BOM version conflict with Spring Boot 3.5.x | High (immediate) | Pin `spring-ai.version` in `pom.xml`; test on first dependency addition |
| `suggestedClassification` mismatch → wrong Neo4j relationship | Medium | BD Manager can override at approve time; review step is a safety gate |
| Querying company classification requires Neo4j (no fallback) | Medium | Dashboard/reports that need classification must always query Neo4j; acceptable for Capstone scale |
| MongoDB schema drift as AI models improve | Medium | `schemaVersion` field on `CompanyCandidate` and `CompanyProfile`; document migration scripts |
| Soft cross-DB references → orphaned data | Medium | Application-layer existence validation before every cross-DB write; periodic cleanup job |
| Local filesystem file loss on crash | Medium (Phase 1 only) | Acceptable for dev; document need to migrate to S3/MinIO before production deployment |
| Refresh token theft | Medium | Tokens stored BCrypt-hashed; rotation on every use; all tokens revoked on password change |
| Neo4j traversal performance on large graphs | Low-Medium | Limit Cypher traversal depth; index `companyId` and `name`; cache frequent queries |
| Multi-datasource `@Primary` misconfiguration | Medium | Explicit `basePackages` on `@EnableJpaRepositories` and `@EnableMongoRepositories` to prevent bean conflicts |
| `factors_json` query limitations in SQL Server | Low | `NVARCHAR(MAX)` with `JSON_VALUE()` for lookups; acceptable for Capstone; add reporting layer for complex queries |

---

## 15. Required Dependencies to Add to `pom.xml`

All of the following must be added before Phase 1 implementation begins:

| Dependency | Group ID / Artifact ID | Notes |
|---|---|---|
| JWT (access tokens) | `io.jsonwebtoken / jjwt-api`, `jjwt-impl`, `jjwt-jackson` | Version `0.12.x` for Spring Boot 3 compatibility |
| MapStruct | `org.mapstruct / mapstruct` + annotation processor path in `maven-compiler-plugin` | Version `1.5.5.Final` or latest |
| SpringDoc OpenAPI | `org.springdoc / springdoc-openapi-starter-webmvc-ui` | Swagger UI at `/swagger-ui.html` |
| Spring AI BOM | `org.springframework.ai / spring-ai-bom` in `<dependencyManagement>` | Pin version compatible with Spring Boot 3.5 |
| Spring AI OpenAI | `org.springframework.ai / spring-ai-openai-spring-boot-starter` | Auto-configures `ChatClient` for GPT-4o |
| Testcontainers BOM | `org.testcontainers / testcontainers-bom` in `<dependencyManagement>` | |
| Testcontainers SQL Server | `org.testcontainers / mssqlserver` | Integration tests |
| Testcontainers MongoDB | `org.testcontainers / mongodb` | Integration tests |
| Testcontainers Neo4j | `org.testcontainers / neo4j` | Integration tests |

---

## 16. Resolved Architectural Decisions

> [!NOTE]
> All architectural decisions are now resolved and incorporated throughout this document.

| Decision | Resolution | Impact on Design |
|---|---|---|
| **Q1 — AI Provider** | Spring AI abstraction → OpenAI GPT-4o | `spring-ai-openai-spring-boot-starter`; `ChatClient` with structured output binding; provider-agnostic |
| **Q2 — File Storage** | Local filesystem for Phase 1 (`uploads/` directory) | `StorageService` interface + `LocalFileStorageService`; `ImportJob.localFilePath` stores relative path |
| **Q3 — JWT Strategy** | Short-lived access token (15 min) + refresh token persisted in SQL Server | `refresh_tokens` table; BCrypt-hashed; rotation-on-use; revocation on logout |
| **Q4 — Manual Input** | Required in Phase 1 | `POST /projects/{id}/documents/manual`; `InputType.MANUAL_INPUT`; candidate created without AI call |
| **Q5 — Score Breakdown** | `factors_json` (`NVARCHAR(MAX)`) column in `score_snapshots` | Per-rule breakdown stored as JSON; `totalScore` is separate numeric column |
| **R1 — ApprovalTask Removed** | No `ApprovalTask` aggregate, module, table, or API | Review workflow is `CandidateStatus` state machine on `CompanyCandidate`; BD Manager calls `POST /candidates/{id}/approve\|reject` directly |
| **R2 — CompanyProfile Classification** | `CompanyProfile` has **no** `classification` field | Profile is a neutral factual record; to get classification, query Neo4j graph by `companyId` |
| **R3 — Classification Source of Truth** | Neo4j relationship type = company classification | `PARTNER_WITH`, `COMPETITOR_OF`, `SUPPLIER_OF`, `CUSTOMER_OF`, `POTENTIAL_PARTNER_OF` are the only classification mechanism |
| **R4 — Universal Company Identity** | `companyId` UUID spans MongoDB `CompanyProfile` + Neo4j `Company` node + SQL `score_snapshots` | Generated once at `CompanyProfile` creation; never changes; validated on every cross-DB write |
| **R5 — Project Aggregate Fields** | `projectType`, `targetCompanyProfileId`, `targetCompanyName` are explicit required fields | Business invariants enforced in `ProjectService` per `projectType` |
| **R6 — AI Assistant Timing** | AI Assistant (`POST /ai/assistant`) is Phase 2 only | Implemented alongside Spring AI extraction in Phase 2; not deferred to Phase 5 |
