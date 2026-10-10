package com.souldealers.crowdtracebackend.shared.audit.internal;

import com.souldealers.crowdtracebackend.shared.audit.AuditedOperation;
import com.souldealers.crowdtracebackend.shared.audit.AuditMissingException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class AuditedOperationAspect {

    @Around("@annotation(operation)")
    Object enforce(ProceedingJoinPoint invocation, AuditedOperation operation) throws Throwable {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Audited operation requires an active transaction");
        }
        var frame = AuditObligations.open(operation.value());
        boolean returnedNormally = false;
        try {
            Object result = invocation.proceed();
            returnedNormally = true;
            return result;
        } finally {
            var missing = AuditObligations.close(frame, returnedNormally);
            if (returnedNormally && !missing.isEmpty()) {
                throw new AuditMissingException(missing);
            }
        }
    }
}
