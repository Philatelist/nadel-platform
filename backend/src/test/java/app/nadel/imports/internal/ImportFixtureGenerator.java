package app.nadel.imports.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Writes the committed workbooks of {@link ImportFixtures}. Switched off in normal builds; to regenerate them run
 * {@code ./mvnw -B test -Dtest=ImportFixtureGenerator -Dnadel.generateImportFixtures=true} from {@code backend/} and
 * commit the files. {@code ImportFixturesTests} then checks what they preview as.
 */
@EnabledIfSystemProperty(named = "nadel.generateImportFixtures", matches = "true")
class ImportFixtureGenerator {

    private static final Path DIRECTORY = Path.of("src/test/resources/imports");

    @Test
    void writesTheWorkbooks() throws IOException {
        Files.createDirectories(DIRECTORY);
        Files.write(DIRECTORY.resolve("valid.xlsx"), ImportFixtures.valid());
        Files.write(DIRECTORY.resolve("errors.xlsx"), ImportFixtures.errors());
    }
}
