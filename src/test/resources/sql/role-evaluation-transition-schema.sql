IF OBJECT_ID('accounts', 'U') IS NULL
CREATE TABLE accounts (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    is_active BIT DEFAULT 1,
    created_at DATETIME2,
    updated_at DATETIME2,
    phone_number NVARCHAR(20) NULL,
    phone_verified_at DATETIME2 NULL
);

IF OBJECT_ID('projects', 'U') IS NULL
CREATE TABLE projects (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    project_name VARCHAR(255) NOT NULL,
    project_type VARCHAR(255) NOT NULL,
    target_company_profile_id VARCHAR(255),
    target_company_name VARCHAR(255) NOT NULL,
    target_relationship_type VARCHAR(50),
    description NVARCHAR(MAX),
    status VARCHAR(255) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME2,
    updated_at DATETIME2,
    CONSTRAINT fk_projects_created_by FOREIGN KEY (created_by) REFERENCES accounts(id)
);

IF OBJECT_ID('project_tasks', 'U') IS NULL
CREATE TABLE project_tasks (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    project_id BIGINT NOT NULL,
    assigned_to_account_id BIGINT,
    title VARCHAR(255) NOT NULL,
    description NVARCHAR(MAX),
    status VARCHAR(255) NOT NULL,
    target_company_profile_id VARCHAR(255),
    task_type VARCHAR(50),
    priority VARCHAR(255),
    due_date DATETIME2,
    created_by_account_id BIGINT NOT NULL,
    created_at DATETIME2,
    updated_at DATETIME2,
    completed_at DATETIME2,
    CONSTRAINT fk_tasks_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_tasks_assigned_to FOREIGN KEY (assigned_to_account_id) REFERENCES accounts(id),
    CONSTRAINT fk_tasks_created_by FOREIGN KEY (created_by_account_id) REFERENCES accounts(id)
);

IF OBJECT_ID('project_task_submissions', 'U') IS NULL
CREATE TABLE project_task_submissions (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    project_task_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    submitted_by_account_id BIGINT NOT NULL,
    submission_type VARCHAR(255) NOT NULL,
    target_entity_type VARCHAR(255),
    target_entity_id VARCHAR(255),
    status VARCHAR(255) NOT NULL,
    note NVARCHAR(MAX),
    submitted_at DATETIME2 NOT NULL,
    reviewed_by_account_id BIGINT,
    reviewed_at DATETIME2,
    review_comment NVARCHAR(MAX),
    created_at DATETIME2,
    updated_at DATETIME2,
    CONSTRAINT fk_submissions_task FOREIGN KEY (project_task_id) REFERENCES project_tasks(id),
    CONSTRAINT fk_submissions_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_submissions_submitted_by FOREIGN KEY (submitted_by_account_id) REFERENCES accounts(id),
    CONSTRAINT fk_submissions_reviewed_by FOREIGN KEY (reviewed_by_account_id) REFERENCES accounts(id)
);

IF OBJECT_ID('audit_logs', 'U') IS NULL
CREATE TABLE audit_logs (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    actor_account_id BIGINT NOT NULL,
    project_id BIGINT,
    action VARCHAR(255) NOT NULL,
    entity_type VARCHAR(255) NOT NULL,
    entity_id VARCHAR(255),
    detail NVARCHAR(MAX),
    timestamp DATETIME2,
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_account_id) REFERENCES accounts(id),
    CONSTRAINT fk_audit_project FOREIGN KEY (project_id) REFERENCES projects(id)
);

IF OBJECT_ID('account_roles', 'U') IS NULL
CREATE TABLE account_roles (
    account_id BIGINT NOT NULL,
    role VARCHAR(255) NOT NULL,
    CONSTRAINT fk_account_roles_account FOREIGN KEY (account_id) REFERENCES accounts(id)
);

IF OBJECT_ID('project_members', 'U') IS NULL
CREATE TABLE project_members (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    project_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    member_role VARCHAR(255) NOT NULL,
    joined_at DATETIME2,
    CONSTRAINT fk_members_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_members_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT uk_members_project_account UNIQUE (project_id, account_id)
);

IF OBJECT_ID('users', 'U') IS NULL
CREATE TABLE users (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    account_id BIGINT NOT NULL UNIQUE,
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    phone VARCHAR(255),
    department VARCHAR(255),
    position VARCHAR(255),
    avatar_url VARCHAR(255),
    created_at DATETIME2,
    updated_at DATETIME2,
    CONSTRAINT fk_users_account FOREIGN KEY (account_id) REFERENCES accounts(id)
);

IF OBJECT_ID('otp_challenges', 'U') IS NULL
CREATE TABLE otp_challenges (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    account_id BIGINT NOT NULL,
    purpose NVARCHAR(50) NOT NULL,
    otp_hash NVARCHAR(255) NOT NULL,
    expires_at DATETIME2 NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    used_at DATETIME2 NULL,
    invalidated_at DATETIME2 NULL,
    invalidation_reason NVARCHAR(255) NULL,
    created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    request_ip NVARCHAR(45) NULL,
    CONSTRAINT fk_otp_challenge_account FOREIGN KEY (account_id) REFERENCES accounts(id)
);
