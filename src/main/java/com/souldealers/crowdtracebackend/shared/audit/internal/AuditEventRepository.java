package com.souldealers.crowdtracebackend.shared.audit.internal;

import org.springframework.data.repository.Repository;

/** Deliberately exposes only the insert operation. */
interface AuditEventRepository extends Repository<AuditEvent, Long>, AuditEventAppender { }
