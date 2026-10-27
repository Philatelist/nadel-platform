package app.nadel.imports.internal;

import static app.nadel.imports.internal.ImportWorkbooks.member;
import static app.nadel.imports.internal.ImportWorkbooks.owner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.tenancy.TenantContext;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The plan of a preview is stored sealed (spec 014, 2.5): {@code import_run} holds no contact of the file in any
 * readable form, the preview still commits, the commit compares against the digest inside the sealed envelope, and a
 * plan copied into another run does not open (500 {@code PERSONAL_DATA_UNREADABLE}, the run left as it was).
 */
class ImportPlanEncryptionTests extends ImportCommitSupport {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Seven random digits, so the phone is unique to this test run. */
    private final String phoneDigits = String.valueOf(1_000_000 + RANDOM.nextInt(9_000_000));

    private byte[] fileWithContacts() {
        return workbook(rows(newPlot("a")),
                rows(member("E-1", "Fixt" + tag + "Sealed", "Person", null, "+38067" + phoneDigits,
                        email("sealed"), "Sealed street " + tag, null, null, null)),
                rows(owner(plotNo("a"), "E-1", "1", "01.03.2020")));
    }

    @Test
    void thePreviewStoresNoReadableContactAndStillCommits() throws Exception {
        String id = preview(fileWithContacts()).get("id").asString();

        // the whole row as the owner sees it, with the bytes of the sealed plan rendered as text where printable
        String row = ownerJdbc().queryForObject("select row_to_json(r)::text || ' ' || "
                + "coalesce(encode(r.plan_enc, 'escape'), '') from import_run r where id = ?::uuid", String.class, id);
        assertThat(row).doesNotContain("Fixt" + tag).doesNotContain("sealed." + tag).doesNotContain(phoneDigits)
                .doesNotContain("Sealed street").doesNotContain(plotNo("a"));
        assertThat(runRow(id)).doesNotContainKeys("plan", "plan_digest");
        // the sealed envelope holds the rows of the file and the digest of the preview
        SealedPlans.Opened opened = openedPlan(id);
        assertThat(opened.digest()).matches("[0-9a-f]{64}");
        assertThat(storedPlan(id).toString()).contains("Fixt" + tag + "Sealed").contains(email("sealed"))
                .contains(phoneDigits).contains("Sealed street " + tag);

        commit(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.plots.created").value(1))
                .andExpect(jsonPath("$.counts.members.created").value(1));

        assertThat(runRow(id)).containsEntry("status", "COMMITTED").containsEntry("plan_enc", null);
        assertThat(plotsLike("CMT-" + tag)).isEqualTo(1);
        assertThat(membersLike("Fixt" + tag)).isEqualTo(1);
    }

    @Test
    void theCommitComparesWithTheDigestInsideTheSealedPlan() throws Exception {
        String id = preview(fileWithContacts()).get("id").asString();
        Map<String, Object> rows = openedPlan(id).plan();
        // the same rows with another digest, sealed properly for this run
        byte[] otherDigest = TenantContext.runAs(DEMO_TENANT,
                () -> sealedPlans.seal(DEMO_TENANT, UUID.fromString(id), rows, "0".repeat(64)));
        ownerJdbc().update("update import_run set plan_enc = ? where id = ?::uuid", otherDigest, id);

        commit(id)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_STALE"));

        assertThat(runRow(id)).containsEntry("status", "STALE").containsEntry("plan_enc", null);
        assertThat(plotsLike("CMT-" + tag)).isZero();
        assertThat(membersLike("Fixt" + tag)).isZero();
    }

    @Test
    void aPlanCopiedToAnotherRunDoesNotOpen() throws Exception {
        String source = preview(fileWithContacts()).get("id").asString();
        String target = preview(workbook(rows(newPlot("b")), rows(), rows())).get("id").asString();
        byte[] copied = ownerJdbc().queryForObject("select plan_enc from import_run where id = ?::uuid", byte[].class,
                source);
        ownerJdbc().update("update import_run set plan_enc = ? where id = ?::uuid", copied, target);

        commit(target)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("PERSONAL_DATA_UNREADABLE"));

        // nothing saved, and the run is left as it was (never an empty plan)
        assertThat(runRow(target)).containsEntry("status", "PREVIEW").containsEntry("committed_at", null);
        assertThat(ownerJdbc().queryForObject("select plan_enc from import_run where id = ?::uuid", byte[].class,
                target)).isEqualTo(copied);
        assertThat(plotsLike("CMT-" + tag)).isZero();
        assertThat(membersLike("Fixt" + tag)).isZero();
        // the plan still opens in its own run
        commit(source).andExpect(status().isOk());
    }

    private static List<List<?>> rows(List<?>... rows) {
        return List.of(rows);
    }
}
