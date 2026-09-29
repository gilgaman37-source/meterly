CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(80) NOT NULL,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('USER','ANALYST','ADMIN')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE UNIQUE INDEX uk_users_username_ci ON users (lower(username));
CREATE UNIQUE INDEX uk_users_email_ci ON users (lower(email));

CREATE TABLE meters (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    serial_number VARCHAR(100) NOT NULL,
    meter_type VARCHAR(30) NOT NULL CHECK (meter_type IN ('COLD_WATER','HOT_WATER','GAS')),
    description VARCHAR(300),
    region VARCHAR(120),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_meter_user_serial UNIQUE (user_id, serial_number)
);
CREATE INDEX idx_meters_user_id ON meters(user_id);
CREATE INDEX idx_meters_type ON meters(meter_type);

CREATE TABLE meter_readings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    meter_id UUID NOT NULL REFERENCES meters(id),
    value NUMERIC(19,6) NOT NULL CHECK (value >= 0),
    submitted_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(20) NOT NULL CHECK (source IN ('MANUAL','IMAGE_OCR')),
    processing_status VARCHAR(30) NOT NULL CHECK (processing_status IN ('QUEUED','PROCESSING','COMPLETED','FAILED','REQUIRES_CONFIRMATION')),
    ocr_confidence NUMERIC(5,4),
    reset_event BOOLEAN NOT NULL DEFAULT FALSE,
    reset_reason VARCHAR(300),
    corrected_at TIMESTAMPTZ,
    original_value NUMERIC(19,6),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_readings_user_id ON meter_readings(user_id);
CREATE INDEX idx_readings_meter_submitted ON meter_readings(meter_id, submitted_at DESC);
CREATE INDEX idx_readings_status ON meter_readings(processing_status);
CREATE UNIQUE INDEX uk_reading_exact_duplicate ON meter_readings(meter_id, value, submitted_at);

CREATE TABLE ocr_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    meter_id UUID NOT NULL REFERENCES meters(id),
    processing_status VARCHAR(30) NOT NULL CHECK (processing_status IN ('QUEUED','PROCESSING','COMPLETED','FAILED','REQUIRES_CONFIRMATION')),
    storage_key VARCHAR(255) NOT NULL UNIQUE,
    original_filename VARCHAR(255),
    content_type VARCHAR(100) NOT NULL,
    recognized_value NUMERIC(19,6),
    ocr_confidence NUMERIC(5,4),
    validation_errors TEXT,
    reading_id UUID REFERENCES meter_readings(id),
    submitted_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    image_deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ocr_user_id ON ocr_requests(user_id);
CREATE INDEX idx_ocr_status_created ON ocr_requests(processing_status, created_at);
CREATE INDEX idx_ocr_meter_id ON ocr_requests(meter_id);

CREATE TABLE tariffs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    meter_type VARCHAR(30) NOT NULL CHECK (meter_type IN ('COLD_WATER','HOT_WATER','GAS')),
    price_per_unit NUMERIC(19,6) NOT NULL CHECK (price_per_unit >= 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'RUB',
    valid_from DATE NOT NULL,
    valid_to DATE,
    region VARCHAR(120) NOT NULL,
    source_url VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
CREATE INDEX idx_tariff_lookup ON tariffs(meter_type, region, valid_from, valid_to);
