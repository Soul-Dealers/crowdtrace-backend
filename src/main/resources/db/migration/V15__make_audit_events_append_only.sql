-- Audit history is append-only. A correction must be represented by another event.
-- Table owners can disable or drop triggers, so CT-038/039 must use separate runtime and
-- migration roles and grant the runtime role only INSERT and SELECT on audit_events.
CREATE FUNCTION reject_audit_event_mutation()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only; append a new event instead'
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER audit_events_reject_update_or_delete
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW
    EXECUTE FUNCTION reject_audit_event_mutation();

CREATE TRIGGER audit_events_reject_truncate
    BEFORE TRUNCATE ON audit_events
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_audit_event_mutation();
