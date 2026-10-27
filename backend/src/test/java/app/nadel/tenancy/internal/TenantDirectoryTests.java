package app.nadel.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.IntegrationTest;
import app.nadel.tenancy.TenantContext;
import app.nadel.tenancy.TenantDirectory;
import app.nadel.tenancy.TenantSummary;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The current cooperative with its ЄДРПОУ code, read from its own {@code tenant} row only. */
class TenantDirectoryTests extends IntegrationTest {

    @Autowired
    TenantDirectory directory;

    @AfterEach
    void cleanUp() {
        ownerJdbc().update("update tenant set edrpou = null where id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
    }

    @Test
    void theCurrentCooperativeCarriesItsOwnEdrpou() {
        ownerJdbc().update("update tenant set edrpou = '12345678' where id = ?", DEMO_TENANT);
        ownerJdbc().update("update tenant set edrpou = '99999999' where id = ?", OTHER_TENANT);

        Optional<TenantSummary> demo = TenantContext.runAs(DEMO_TENANT, directory::current);
        Optional<TenantSummary> other = TenantContext.runAs(OTHER_TENANT, directory::current);

        assertThat(demo).hasValueSatisfying(t -> {
            assertThat(t.id()).isEqualTo(DEMO_TENANT);
            assertThat(t.edrpou()).isEqualTo("12345678");
        });
        assertThat(other).hasValueSatisfying(t -> {
            assertThat(t.id()).isEqualTo(OTHER_TENANT);
            assertThat(t.edrpou()).isEqualTo("99999999");
        });
    }

    @Test
    void aCooperativeWithoutAnEdrpouHasNone() {
        assertThat(TenantContext.runAs(DEMO_TENANT, directory::current))
                .hasValueSatisfying(t -> assertThat(t.edrpou()).isNull());
    }

    @Test
    void withoutACooperativeThereIsNothing() {
        assertThat(directory.current()).isEmpty();
    }
}
