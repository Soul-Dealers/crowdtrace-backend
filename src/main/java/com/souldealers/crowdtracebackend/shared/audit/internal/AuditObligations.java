package com.souldealers.crowdtracebackend.shared.audit.internal;

import com.souldealers.crowdtracebackend.shared.audit.AuditAction;
import com.souldealers.crowdtracebackend.shared.audit.AuditMissingException;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecordingException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Transaction-bound stack of audit obligations, isolated across suspended transactions.
 *
 * <p>Any rollback to a Spring-managed savepoint poisons the whole transaction. This conservative
 * policy prevents an event flushed inside the savepoint from discharging an obligation or
 * logging as committed after that event was rolled back. Raw JDBC savepoints bypass Spring's
 * synchronization callbacks and are unsupported.
 */
final class AuditObligations {

    private static final Object RESOURCE_KEY = AuditObligations.class;

    private AuditObligations() { }

    /** Ensures savepoint rollbacks are observed even when no audited operation is open. */
    static void trackTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new AuditRecordingException("Audit recording requires an active transaction");
        }
        holder(true);
    }

    static Frame open(AuditAction[] actions) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Audited operation requires an active transaction");
        }
        if (actions == null || actions.length == 0
                || Arrays.stream(actions).anyMatch(action -> action == null)) {
            holder(true).violation = true;
            throw new IllegalStateException("Audited operation must declare at least one action");
        }
        Holder holder = holder(true);
        Frame frame = new Frame(new LinkedHashSet<>(Arrays.asList(actions)));
        holder.frames.push(frame);
        return frame;
    }

    static void discharge(AuditAction action) {
        Holder holder = holder(false);
        if (holder == null || holder.frames.isEmpty()) {
            return;
        }
        Frame frame = holder.frames.peek();
        if (frame.required.contains(action)) {
            frame.recorded.add(action);
        }
    }

    static Set<AuditAction> close(Frame frame, boolean normalReturn) {
        Holder holder = holder(false);
        if (holder == null || holder.frames.isEmpty() || holder.frames.peek() != frame) {
            throw new IllegalStateException("Audit obligation stack is inconsistent");
        }
        holder.frames.pop();
        Set<AuditAction> missing = new LinkedHashSet<>(frame.required);
        missing.removeAll(frame.recorded);
        if (!missing.isEmpty() && normalReturn) {
            holder.violations.addAll(missing);
        }
        return Set.copyOf(missing);
    }

    private static Holder holder(boolean create) {
        Holder holder = (Holder) TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        if (holder == null && create) {
            holder = new Holder();
            TransactionSynchronizationManager.bindResource(RESOURCE_KEY, holder);
            Holder bound = holder;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void suspend() {
                    if (TransactionSynchronizationManager.hasResource(RESOURCE_KEY)) {
                        TransactionSynchronizationManager.unbindResource(RESOURCE_KEY);
                    }
                }

                @Override
                public void resume() {
                    if (!TransactionSynchronizationManager.hasResource(RESOURCE_KEY)) {
                        TransactionSynchronizationManager.bindResource(RESOURCE_KEY, bound);
                    }
                }

                @Override
                public void beforeCommit(boolean readOnly) {
                    if (bound.savepointRolledBack) {
                        throw new AuditRecordingException(
                                "Audit recording does not allow a Spring-managed savepoint rollback");
                    }
                    if (bound.violation || !bound.violations.isEmpty()) {
                        throw new AuditMissingException(bound.violations);
                    }
                }

                @Override
                public void savepointRollback(Object savepoint) {
                    bound.savepointRolledBack = true;
                }

                @Override
                public void afterCompletion(int status) {
                    if (TransactionSynchronizationManager.hasResource(RESOURCE_KEY)) {
                        TransactionSynchronizationManager.unbindResource(RESOURCE_KEY);
                    }
                }
            });
        }
        return holder;
    }

    static final class Frame {
        private final Set<AuditAction> required;
        private final Set<AuditAction> recorded = new LinkedHashSet<>();

        private Frame(Set<AuditAction> required) {
            this.required = required;
        }
    }

    private static final class Holder {
        private final ArrayDeque<Frame> frames = new ArrayDeque<>();
        private final Set<AuditAction> violations = new LinkedHashSet<>();
        private boolean violation;
        private boolean savepointRolledBack;
    }
}
