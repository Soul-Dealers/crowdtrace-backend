ALTER TABLE cases
    ADD COLUMN takedown_reason VARCHAR(2000),
    ADD COLUMN taken_down_by BIGINT REFERENCES users(id),
    ADD COLUMN taken_down_at TIMESTAMP WITHOUT TIME ZONE;

ALTER TABLE cases
    ADD CONSTRAINT cases_takedown_columns_check CHECK (
        (takedown_reason IS NULL) = (taken_down_by IS NULL)
        AND (taken_down_by IS NULL) = (taken_down_at IS NULL)
    ),
    ADD CONSTRAINT cases_takedown_reason_nonblank_check CHECK (
        takedown_reason IS NULL OR takedown_reason ~ '\S'
    ),
    ADD CONSTRAINT cases_taken_down_state_check CHECK (
        review_status <> 'TAKEN_DOWN' OR (
            case_status IS NOT NULL
            AND takedown_reason IS NOT NULL
            AND taken_down_by IS NOT NULL
            AND taken_down_at IS NOT NULL
        )
    ),
    ADD CONSTRAINT cases_resolved_at_status_check CHECK (
        (resolved_at IS NOT NULL) = COALESCE(
            case_status IN ('FOUND_SAFE', 'FOUND_DECEASED', 'CLOSED'), false
        )
    ),
    ADD CONSTRAINT cases_closed_at_status_check CHECK (
        (closed_at IS NOT NULL) = COALESCE(case_status = 'CLOSED', false)
    ),
    ADD CONSTRAINT cases_closing_statement_check CHECK (
        closing_statement IS NULL OR (
            case_status IS NOT NULL
            AND case_status <> 'MISSING'
            AND closing_statement ~ '\S'
            AND char_length(closing_statement) <= 2000
        )
    );
