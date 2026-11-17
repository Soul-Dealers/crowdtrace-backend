package com.souldealers.crowdtracebackend.modules.notification.internal;

import com.souldealers.crowdtracebackend.modules.notification.NotifiedCaseStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Builds the reporter's status email from the case number and new status alone. It is given nothing else, so no
 * case details, admin notes or moderator identity can reach the inbox.
 */
@Component
class ReporterStatusEmailComposer {
    private final ITemplateEngine templates;
    private final String webBaseUrl;

    ReporterStatusEmailComposer(ITemplateEngine templates, @Value("${crowdtrace.web.base-url}") String webBaseUrl) {
        this.templates = templates;
        this.webBaseUrl = webBaseUrl;
    }

    record Email(String subject, String html) { }

    Email compose(long caseId, NotifiedCaseStatus status) {
        Context context = new Context();
        context.setVariable("caseId", caseId);
        context.setVariable("statusLabel", label(status));
        context.setVariable("caseUrl", webBaseUrl + "/cases/" + caseId);
        return new Email("Your CrowdTrace case " + caseId + " was updated",
                templates.process("case-status-update", context));
    }

    private static String label(NotifiedCaseStatus status) {
        return switch (status) {
            case MISSING -> "Missing";
            case FOUND_SAFE -> "Found safe";
            case FOUND_DECEASED -> "Found deceased";
            case CLOSED -> "Closed";
        };
    }
}
