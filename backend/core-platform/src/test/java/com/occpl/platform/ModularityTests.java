package com.occpl.platform;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(CorePlatformApplication.class);

    @Test
    void moduleBoundariesAreRespected() {
        modules.verify();
    }
}
