package app.nadel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Re-running {@code R__demo_data.sql} on a database that still holds the English demo text of earlier versions turns
 * that text Ukrainian, and never overwrites a value the chairman changed.
 */
class DemoDataTextTests extends IntegrationTest {

    private static final String ROAD = "eeeeeeee-0000-0000-0000-000000000001";
    private static final String ELECTRICIAN = "cccccccc-0000-0000-0000-000000000001";
    private static final String SECRETARY = "cccccccc-0000-0000-0000-000000000002";

    private JdbcTemplate owner;

    @BeforeEach
    void putTheLegacyEnglishTextBack() {
        owner = ownerJdbc();
        DemoTariffData.restore(owner);
        owner.update("update tenant set name = 'Demo Garden Cooperative' where id = ?", DEMO_TENANT);
        owner.update("update tenant set name = 'Other Cooperative' where id = ?", OTHER_TENANT);
        owner.update("update role set name = 'Electrician', description = 'Custom role without permissions' "
                + "where id = ?::uuid", ELECTRICIAN);
        // the chairman renamed the Secretary role: this edit must survive
        owner.update("update role set name = 'Board secretary' where id = ?::uuid", SECRETARY);
        owner.update("update charge_type set name = 'Road repair', "
                + "description = 'Special-purpose fee for repairing the main road' where id = ?::uuid", ROAD);
        owner.update("update tariff set name = 'For members' where id = 'ffffffff-0000-0000-0000-000000000001'");
        owner.update("update tariff set name = 'Land tax' where id = 'ffffffff-0000-0000-0000-000000000002'");
        // renamed by the chairman: must survive
        owner.update("update tariff set name = 'Our electricity' where id = 'ffffffff-0000-0000-0000-000000000003'");
        owner.update("update tariff set name = 'Road repair fee' where id = 'ffffffff-0000-0000-0000-000000000004'");
        owner.update("update tariff_version set note = 'General meeting of 2025-12-14' "
                + "where id = 'ffffffff-0000-0000-0001-000000000001'");
        owner.update("update tariff_scope_plot set reason = 'Exempted by the general meeting' "
                + "where tariff_id = 'ffffffff-0000-0000-0000-000000000001'");
        owner.update("update plot set street = 'Garden St' where tenant_id = ? and number = '9'", DEMO_TENANT);
        owner.update("update member set last_name = 'Kovalenko', first_name = 'Oleksandr' "
                + "where id = 'aaaaaaaa-0000-0000-0000-000000000001'");
    }

    @AfterEach
    void restoreTheDemoState() {
        // the script does not overwrite the chairman's edits, so undo the ones this test made
        owner.update("update role set name = 'Секретар' where id = ?::uuid", SECRETARY);
        owner.update("update tariff set name = 'Електроенергія за лічильником' "
                + "where id = 'ffffffff-0000-0000-0000-000000000003'");
        owner.execute(script());
    }

    @Test
    void rerunningTheScriptTurnsTheEnglishRowsUkrainianAndKeepsTheChairmansEdits() {
        owner.execute(script());

        assertThat(text("select name from tenant where id = ?", DEMO_TENANT)).isEqualTo("СТ «Надія»");
        assertThat(text("select name from tenant where id = ?", OTHER_TENANT)).isEqualTo("СТ «Світанок»");
        assertThat(text("select name || '|' || description from role where id = ?::uuid", ELECTRICIAN))
                .isEqualTo("Електрик|Вносить покази лічильників на обході");
        assertThat(text("select name from role where id = ?::uuid", SECRETARY)).isEqualTo("Board secretary");
        assertThat(text("select name || '|' || description from charge_type where id = ?::uuid", ROAD))
                .isEqualTo("Ремонт дороги|Цільовий збір на ремонт головної дороги");
        assertThat(text("select name from tariff where id = 'ffffffff-0000-0000-0000-000000000001'"))
                .isEqualTo("Для членів кооперативу");
        assertThat(text("select name from tariff where id = 'ffffffff-0000-0000-0000-000000000002'"))
                .isEqualTo("Земельний податок");
        assertThat(text("select name from tariff where id = 'ffffffff-0000-0000-0000-000000000003'"))
                .isEqualTo("Our electricity");
        assertThat(text("select name from tariff where id = 'ffffffff-0000-0000-0000-000000000004'"))
                .isEqualTo("Збір на ремонт дороги");
        assertThat(text("select note from tariff_version where id = 'ffffffff-0000-0000-0001-000000000001'"))
                .isEqualTo("Загальні збори 14.12.2025");
        assertThat(text("select reason from tariff_scope_plot "
                + "where tariff_id = 'ffffffff-0000-0000-0000-000000000001'"))
                .isEqualTo("Звільнено рішенням загальних зборів");
        assertThat(text("select street from plot where tenant_id = ? and number = '9'", DEMO_TENANT))
                .isEqualTo("вул. Садова");
        assertThat(text("select last_name || ' ' || first_name from member "
                + "where id = 'aaaaaaaa-0000-0000-0000-000000000001'")).isEqualTo("Коваленко Олександр");
    }

    private String text(String sql, Object... args) {
        return owner.queryForObject(sql, String.class, args);
    }

    private static String script() {
        try (InputStream in = DemoDataTextTests.class.getResourceAsStream("/db/demo/R__demo_data.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the demo data", e);
        }
    }
}
