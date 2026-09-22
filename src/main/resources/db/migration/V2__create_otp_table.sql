CREATE TABLE otp
(
    id         UUID                        NOT NULL,
    email      VARCHAR(255)                NOT NULL,
    type       VARCHAR(255),
    code       VARCHAR(255)                NOT NULL,
    expired_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT pk_otp PRIMARY KEY (id)
);

CREATE INDEX idx_otp_email ON otp (email);
CREATE INDEX idx_otp_code ON otp (code);
CREATE INDEX idx_otp_email_type_created_at ON otp (email, type, created_at);
