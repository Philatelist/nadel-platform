package app.nadel;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** Verifies module boundaries: a violation breaks the build. */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(NadelApplication.class);

    @Test
    void modulesRespectBoundaries() {
        modules.verify();
    }

    @Test
    void writeModuleDocumentation() {
        // target/spring-modulith-docs: module diagrams for the docs
        new Documenter(modules).writeDocumentation();
    }
}
