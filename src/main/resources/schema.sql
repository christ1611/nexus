CREATE TABLE IF NOT EXISTS dealers (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    phone       VARCHAR(32)  NOT NULL UNIQUE,
    name        VARCHAR(120) NOT NULL,
    dealer_code VARCHAR(32)  NOT NULL UNIQUE,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS conversations (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    dealer_id       BIGINT       NOT NULL UNIQUE REFERENCES dealers (id),
    state           VARCHAR(40)  NOT NULL,
    context         VARCHAR(255),
    last_message_id VARCHAR(128),
    updated_at      TIMESTAMP    NOT NULL
);

CREATE SEQUENCE IF NOT EXISTS application_number_seq START WITH 1001;

CREATE TABLE IF NOT EXISTS applications (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    application_number  VARCHAR(32)  NOT NULL UNIQUE,
    dealer_id           BIGINT       NOT NULL REFERENCES dealers (id),
    status              VARCHAR(40)  NOT NULL,
    link_token_hash     VARCHAR(64)  UNIQUE,
    link_expires_at     TIMESTAMP,
    link_opened_at      TIMESTAMP,
    resubmission_reason VARCHAR(500),
    decision_note       VARCHAR(500),
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL
);

-- Step 3: customer entry, documents and verification
ALTER TABLE applications ADD COLUMN IF NOT EXISTS customer_phone VARCHAR(32);
ALTER TABLE applications ADD COLUMN IF NOT EXISTS customer_name VARCHAR(120);
ALTER TABLE applications ADD COLUMN IF NOT EXISTS extracted_name VARCHAR(120);
ALTER TABLE applications ADD COLUMN IF NOT EXISTS extracted_nik VARCHAR(32);
ALTER TABLE applications ADD COLUMN IF NOT EXISTS extracted_dob VARCHAR(32);
ALTER TABLE applications ADD COLUMN IF NOT EXISTS extracted_address VARCHAR(255);
ALTER TABLE applications ADD COLUMN IF NOT EXISTS id_verification_status VARCHAR(20);
ALTER TABLE applications ADD COLUMN IF NOT EXISTS id_verification_detail VARCHAR(255);
ALTER TABLE applications ADD COLUMN IF NOT EXISTS credit_check_status VARCHAR(20);
ALTER TABLE applications ADD COLUMN IF NOT EXISTS credit_check_detail VARCHAR(255);
ALTER TABLE applications ADD COLUMN IF NOT EXISTS credit_score INT;
ALTER TABLE applications ADD COLUMN IF NOT EXISTS verified_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS customer_sessions (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    phone          VARCHAR(32)  NOT NULL,
    customer_name  VARCHAR(120),
    application_id BIGINT       NOT NULL REFERENCES applications (id),
    state          VARCHAR(40)  NOT NULL,
    consent_at     TIMESTAMP,
    declined_at    TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_customer_sessions_phone ON customer_sessions (phone);

CREATE TABLE IF NOT EXISTS documents (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    application_id BIGINT       NOT NULL REFERENCES applications (id),
    type           VARCHAR(40)  NOT NULL,
    media_id       VARCHAR(128) NOT NULL,
    file_path      VARCHAR(500) NOT NULL,
    mime_type      VARCHAR(80),
    caption        VARCHAR(500),
    check_passed   BOOLEAN,
    check_reason   VARCHAR(255),
    created_at     TIMESTAMP    NOT NULL
);

-- Step 3b: Claude document analysis and review agent
ALTER TABLE documents ADD COLUMN IF NOT EXISTS detected_type VARCHAR(40);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS needs_officer_review BOOLEAN;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS analysis_json VARCHAR(65536);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS superseded BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE applications ADD COLUMN IF NOT EXISTS review_decision VARCHAR(40);
ALTER TABLE applications ADD COLUMN IF NOT EXISTS review_summary VARCHAR(16000);
ALTER TABLE applications ADD COLUMN IF NOT EXISTS review_summary_ko VARCHAR(16000);
ALTER TABLE applications ADD COLUMN IF NOT EXISTS officer_review_required BOOLEAN;
ALTER TABLE applications ADD COLUMN IF NOT EXISTS review_rounds INT DEFAULT 0 NOT NULL;
ALTER TABLE applications ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP;

-- Step 5: countries, dealer tier, risk flags / AI risk grade and customer badges for the dashboard
ALTER TABLE dealers ADD COLUMN IF NOT EXISTS country VARCHAR(2) DEFAULT 'ID' NOT NULL;
ALTER TABLE dealers ADD COLUMN IF NOT EXISTS tier VARCHAR(20) DEFAULT 'STANDARD' NOT NULL;
ALTER TABLE applications ADD COLUMN IF NOT EXISTS country VARCHAR(2) DEFAULT 'ID' NOT NULL;
ALTER TABLE applications ADD COLUMN IF NOT EXISTS risk_grade VARCHAR(2);
ALTER TABLE applications ADD COLUMN IF NOT EXISTS risk_score INT;
ALTER TABLE applications ADD COLUMN IF NOT EXISTS risk_flags_json VARCHAR(16000);
ALTER TABLE applications ADD COLUMN IF NOT EXISTS risk_method VARCHAR(40);
ALTER TABLE applications ADD COLUMN IF NOT EXISTS customer_badges VARCHAR(255);
ALTER TABLE applications ADD COLUMN IF NOT EXISTS risk_assessed_at TIMESTAMP;

-- Step 4: reviewer back office (decision metadata and the audit trail shown on the review page)
ALTER TABLE applications ADD COLUMN IF NOT EXISTS decided_by VARCHAR(80);
ALTER TABLE applications ADD COLUMN IF NOT EXISTS decided_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS review_events (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    application_id BIGINT       NOT NULL REFERENCES applications (id),
    kind           VARCHAR(40)  NOT NULL,
    actor          VARCHAR(80),
    detail         VARCHAR(1000),
    created_at     TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_review_events_application ON review_events (application_id);

-- Demo data marker: rows created by the demo seeder (removable with DELETE /admin/demo/seed)
ALTER TABLE applications ADD COLUMN IF NOT EXISTS demo_seed BOOLEAN DEFAULT FALSE NOT NULL;

-- Step 4b: agent trace (what the pipeline and the review agent did, shown live on the review page)
CREATE TABLE IF NOT EXISTS agent_steps (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    application_id BIGINT       NOT NULL REFERENCES applications (id),
    kind           VARCHAR(40)  NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    actor          VARCHAR(80)  NOT NULL,
    title          VARCHAR(200) NOT NULL,
    detail         VARCHAR(1000),
    data_json      VARCHAR(8000),
    duration_ms    BIGINT,
    round          INT          DEFAULT 0 NOT NULL,
    created_at     TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_agent_steps_application ON agent_steps (application_id);
