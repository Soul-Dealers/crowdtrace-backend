package com.souldealers.crowdtracebackend;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class CrowdtraceModulesTest {

    private static final Set<String> EXPECTED_MODULES = Set.of(
            "modules.identity",
            "modules.casefile",
            "modules.governance",
            "modules.discovery",
            "modules.community",
            "modules.notification",
            "modules.privacy",
            "modules.operations");

    @Test
    void plannedModulesAreDiscoveredAndStructurallyValid() {
        ApplicationModules modules = ApplicationModules.of(CrowdtraceBackendApplication.class);

        modules.verify();

        var shared = modules.getModuleByName("shared").orElseThrow();
        var auditApi = shared.getNamedInterfaces().getByName("audit").orElseThrow();
        assertTrue(auditApi.contains(com.souldealers.crowdtracebackend.shared.audit.AuditRecorder.class));
        assertTrue(!shared.isExposed(com.souldealers.crowdtracebackend.shared.audit.internal.AuditEvent.class));
        assertTrue(!shared.isExposed(shared.getType(
                "com.souldealers.crowdtracebackend.shared.audit.internal.AuditEventRepository").orElseThrow()));

        Set<String> discoveredModules = modules.stream()
                .map(module -> module.getName())
                .collect(Collectors.toSet());

        assertTrue(discoveredModules.containsAll(EXPECTED_MODULES),
                () -> "Missing planned modules: " + missingModules(discoveredModules));
    }

    private Set<String> missingModules(Set<String> discoveredModules) {
        return EXPECTED_MODULES.stream()
                .filter(module -> !discoveredModules.contains(module))
                .collect(Collectors.toSet());
    }
}
