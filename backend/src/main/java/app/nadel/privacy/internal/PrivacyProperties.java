package app.nadel.privacy.internal;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code nadel.privacy.*}. Plain strings, so binding never fails and no binder error can echo a key; the values are
 * validated by hand in {@link MasterKeys}.
 *
 * @param masterKey         {@code NADEL_PD_MASTER_KEY}: 32 bytes in base64
 * @param previousMasterKey {@code NADEL_PD_MASTER_KEY_PREVIOUS}: the master key before a rotation, optional
 * @param devKeyFile        {@code dev} profile only: files to read the master key from when the environment has none,
 *                          relative to the working directory; the first one is created when its directory exists
 */
@ConfigurationProperties("nadel.privacy")
record PrivacyProperties(@Nullable String masterKey, @Nullable String previousMasterKey,
        @Nullable List<String> devKeyFile) {

    List<String> devKeyFiles() {
        return devKeyFile == null ? List.of() : devKeyFile.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** Never prints a key. */
    @Override
    public String toString() {
        return "PrivacyProperties[masterKey=" + (masterKey == null || masterKey.isBlank() ? "<unset>" : "<set>")
                + ", previousMasterKey="
                + (previousMasterKey == null || previousMasterKey.isBlank() ? "<unset>" : "<set>")
                + ", devKeyFile=" + devKeyFiles() + "]";
    }
}
