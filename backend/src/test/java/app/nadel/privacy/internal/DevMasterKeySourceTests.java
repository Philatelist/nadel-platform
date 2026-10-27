package app.nadel.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

/**
 * The local key of the {@code dev} profile, on a temporary directory standing in for the working directory, and the
 * profile rules of {@link PrivacyConfiguration}: the environment wins, the file fallback exists only in {@code dev}.
 */
@ExtendWith(OutputCaptureExtension.class)
class DevMasterKeySourceTests {

    static final List<String> FILES = List.of("infra/.env", "../infra/.env");

    @TempDir
    Path root;

    @Test
    void readsTheKeyFromTheFirstExistingFile() throws Exception {
        String key = MasterKeysTests.key(32);
        Files.createDirectories(root.resolve("infra"));
        Files.writeString(root.resolve("infra/.env"), "# local\nOTHER=1\nNADEL_PD_MASTER_KEY=" + key + "\n");

        assertThat(new DevMasterKeySource(root, FILES).resolve()).isEqualTo(key);
    }

    @Test
    void readsTheKeyOfTheRepositoryWhenStartedFromBackend() throws Exception {
        String key = MasterKeysTests.key(32);
        Path backend = Files.createDirectories(root.resolve("backend"));
        Files.createDirectories(root.resolve("infra"));
        Files.writeString(root.resolve("infra/.env"), "export NADEL_PD_MASTER_KEY=\"" + key + "\"\n");

        assertThat(new DevMasterKeySource(backend, FILES).resolve()).isEqualTo(key);
        assertThat(backend.resolve("infra")).doesNotExist();
    }

    @Test
    void acceptsSingleQuotes() throws Exception {
        String key = MasterKeysTests.key(32);
        Files.createDirectories(root.resolve("infra"));
        Files.writeString(root.resolve("infra/.env"), "NADEL_PD_MASTER_KEY='" + key + "'\n");

        assertThat(new DevMasterKeySource(root, FILES).resolve()).isEqualTo(key);
    }

    @Test
    void aFileWithoutTheKeyIsAnErrorAndIsNotOverwritten() throws Exception {
        Files.createDirectories(root.resolve("infra"));
        Path env = root.resolve("infra/.env");
        Files.writeString(env, "OTHER=1\nNADEL_PD_MASTER_KEY=\n");

        assertThatThrownBy(() -> new DevMasterKeySource(root, FILES).resolve())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no NADEL_PD_MASTER_KEY line");
        assertThat(env).hasContent("OTHER=1\nNADEL_PD_MASTER_KEY=");
    }

    @Test
    void createsTheFileWithMode600WhenInfraExistsAndLogsOnlyThePath(CapturedOutput output) throws Exception {
        Files.createDirectories(root.resolve("infra"));

        String key = new DevMasterKeySource(root, FILES).resolve();

        Path env = root.resolve("infra/.env");
        assertThat(MasterKeys.of(key, null).current().id()).hasSize(8);
        assertThat(Files.readString(env, StandardCharsets.UTF_8)).isEqualTo("NADEL_PD_MASTER_KEY=" + key
                + System.lineSeparator());
        if (env.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Set<PosixFilePermission> mode = Files.getPosixFilePermissions(env);
            assertThat(PosixFilePermissions.toString(mode)).isEqualTo("rw-------");
        }
        assertThat(output.getAll()).contains("Created a local personal data master key in " + env)
                .doesNotContain(key);
        // the next start reads the same key instead of creating another
        assertThat(new DevMasterKeySource(root, FILES).resolve()).isEqualTo(key);
    }

    @Test
    void failsWithAHintWhenThereIsNoFileAndNoInfraDirectory() {
        Path backend = root.resolve("backend");

        assertThatThrownBy(() -> new DevMasterKeySource(backend, FILES).resolve())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NADEL_PD_MASTER_KEY is missing")
                .hasMessageContaining("make up");
        assertThatThrownBy(() -> new DevMasterKeySource(root, List.of()).resolve())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("make up");
        assertThat(root.resolve("infra")).doesNotExist();
    }

    @Test
    void whenAnotherProcessCreatedTheFileFirstItsKeyIsUsed() throws Exception {
        String key = MasterKeysTests.key(32);
        Files.createDirectories(root.resolve("infra"));
        Path env = Files.writeString(root.resolve("infra/.env"), "NADEL_PD_MASTER_KEY=" + key + "\n");

        assertThat(DevMasterKeySource.create(env)).isEqualTo(key);
        assertThat(env).hasContent("NADEL_PD_MASTER_KEY=" + key);
    }

    @Test
    void anUnreadableFileIsAnErrorThatNamesThePath() throws Exception {
        Files.createDirectories(root.resolve("infra"));
        Path env = Files.writeString(root.resolve("infra/.env"), "NADEL_PD_MASTER_KEY=" + MasterKeysTests.key(32));
        assumeTrue(withoutPermissions(env), "file permissions are not enforced here");
        try {
            assertThatThrownBy(() -> new DevMasterKeySource(root, FILES).resolve())
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessage("The local master key file " + env + " could not be read");
        } finally {
            Files.setPosixFilePermissions(env, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void anUnwritableInfraDirectoryIsAnErrorThatNamesThePath() throws Exception {
        Path infra = Files.createDirectories(root.resolve("infra"));
        assumeTrue(withoutPermissions(infra), "file permissions are not enforced here");
        try {
            assertThatThrownBy(() -> new DevMasterKeySource(root, FILES).resolve())
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessage("The local master key file " + infra.resolve(".env") + " could not be written");
        } finally {
            Files.setPosixFilePermissions(infra, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void theEnvironmentKeyWinsInDevAndNoFileIsTouched() throws Exception {
        String key = MasterKeysTests.key(32);
        Files.createDirectories(root.resolve("infra"));

        MasterKeys keys = configuration(environment("dev"), key, List.of(root.resolve("infra/.env").toString()));

        assertThat(keys.current().id()).isEqualTo(MasterKeysTests.idOf(key));
        assertThat(root.resolve("infra/.env")).doesNotExist();
    }

    @Test
    void devWithoutAnEnvironmentKeyUsesTheFile() throws Exception {
        String key = MasterKeysTests.key(32);
        Files.createDirectories(root.resolve("infra"));
        Files.writeString(root.resolve("infra/.env"), "NADEL_PD_MASTER_KEY=" + key + "\n");

        MasterKeys keys = configuration(environment("dev"), "", List.of(root.resolve("infra/.env").toString()));

        assertThat(keys.current().id()).isEqualTo(MasterKeysTests.idOf(key));
    }

    @Test
    void theDefaultDevProfileAlsoUsesTheFile() throws Exception {
        String key = MasterKeysTests.key(32);
        Files.createDirectories(root.resolve("infra"));
        Files.writeString(root.resolve("infra/.env"), "NADEL_PD_MASTER_KEY=" + key + "\n");
        MockEnvironment defaultDev = new MockEnvironment();
        defaultDev.setDefaultProfiles("dev");

        MasterKeys keys = configuration(defaultDev, null, List.of(root.resolve("infra/.env").toString()));

        assertThat(keys.current().id()).isEqualTo(MasterKeysTests.idOf(key));
    }

    @Test
    void outsideDevTheFileIsNeverReadOrCreated() throws Exception {
        Files.createDirectories(root.resolve("infra"));
        Files.writeString(root.resolve("infra/.env"), "NADEL_PD_MASTER_KEY=" + MasterKeysTests.key(32) + "\n");
        Files.createDirectories(root.resolve("other"));
        List<String> files = List.of(root.resolve("infra/.env").toString());

        for (MockEnvironment environment : List.of(environment("prod"), environment("dev", "prod"),
                environment())) {
            assertThatThrownBy(() -> configuration(environment, null, files))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(MasterKeysTests.CURRENT_MESSAGE);
        }
        assertThatThrownBy(() -> configuration(environment("prod"), null,
                List.of(root.resolve("other/.env").toString())))
                .hasMessage(MasterKeysTests.CURRENT_MESSAGE);
        assertThat(root.resolve("other/.env")).doesNotExist();
    }

    @Test
    void propertiesNeverPrintAKey() {
        String key = MasterKeysTests.key(32);

        assertThat(new PrivacyProperties(key, key, List.of(" infra/.env ", "")).toString())
                .doesNotContain(key)
                .isEqualTo("PrivacyProperties[masterKey=<set>, previousMasterKey=<set>, devKeyFile=[infra/.env]]");
        assertThat(new PrivacyProperties(null, " ", null).toString())
                .isEqualTo("PrivacyProperties[masterKey=<unset>, previousMasterKey=<unset>, devKeyFile=[]]");
    }

    /** Removes every permission; false when the file system or the user (root) does not enforce them. */
    private static boolean withoutPermissions(Path path) throws Exception {
        if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return false;
        }
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path)
                ? "r-x------" : "---------"));
        return Files.isDirectory(path) ? !Files.isWritable(path) : !Files.isReadable(path);
    }

    private static MasterKeys configuration(MockEnvironment environment, String masterKey, List<String> files) {
        return new PrivacyConfiguration().masterKeys(new PrivacyProperties(masterKey, null, files), environment);
    }

    private static MockEnvironment environment(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }
}
