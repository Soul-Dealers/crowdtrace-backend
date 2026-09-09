package com.souldealers.crowdtracebackend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class CrowdtraceModulesTest {

    private static final Set<String> EXPECTED_MODULES = Set.of(
            "identity",
            "casefile",
            "governance",
            "discovery",
            "community",
            "notification",
            "privacy",
            "operations");

    @Test
    void plannedModulesAreDiscoveredAndStructurallyValid() {
        ApplicationModules modules = ApplicationModules.of(CrowdtraceBackendApplication.class);

        modules.verify();

        Set<String> discoveredModules = modules.stream()
                .map(module -> module.getName())
                .collect(Collectors.toSet());

        assertEquals(EXPECTED_MODULES, discoveredModules);
    }
}
