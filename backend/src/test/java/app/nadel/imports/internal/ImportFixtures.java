package app.nadel.imports.internal;

import static app.nadel.imports.internal.ImportWorkbooks.MEMBER_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.OWNERSHIP_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.PLOT_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.member;
import static app.nadel.imports.internal.ImportWorkbooks.owner;
import static app.nadel.imports.internal.ImportWorkbooks.plot;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The content of the two committed workbooks under {@code src/test/resources/imports/}. {@link ImportFixtureGenerator}
 * writes them (switched off unless a system property is set); {@code ImportFixturesTests} previews the committed
 * files. Plot numbers start with {@code IMP-} and member last names with {@code Fixt}, so tests can remove what they
 * import, and none of them collides with the demo data.
 */
final class ImportFixtures {

    static final String VALID = "/imports/valid.xlsx";
    static final String ERRORS = "/imports/errors.xlsx";

    private ImportFixtures() {
    }

    static String plotNumber(int index) {
        return String.format("IMP-%02d", index);
    }

    /**
     * 30 plots, 35 members, 35 ownership rows: plots 1-26 have one owner each, plot 27 is owned 1/2 + 1/2, plot 28
     * 2/3 + 1/3, plot 29 by three members at 1/3 and plot 30 1/2 + 1/2. Members 20-25 have no e-mail (they are
     * recognised by name and phone), the others have an e-mail; member 3 prefers Telegram.
     */
    static byte[] valid() {
        List<List<?>> plots = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            boolean sotky = i % 5 == 0;
            plots.add(plot(plotNumber(i), "Fixture Street " + (1 + i % 3), "PLOT", sotky ? null : 600 + i,
                    sotky ? 6 : null, i <= 10 ? String.format("9900000000:01:001:%04d", i) : null,
                    i % 2 == 0 ? "yes" : null, i == 1 ? "Corner plot" : null));
        }
        List<List<?>> members = new ArrayList<>();
        for (int i = 1; i <= 35; i++) {
            String key = String.format("M%02d", i);
            boolean hasEmail = i < 20 || i > 25;
            String email = hasEmail ? String.format("fixture.m%02d@import.example.com", i) : null;
            members.add(member(key, String.format("Fixt%02d", i), "Person", i % 4 == 0 ? "Middle" : null,
                    String.format("+38050123%04d", i), email, i % 7 == 0 ? "Fixture Town, 1 Main St" : null,
                    i == 3 ? "TELEGRAM" : null, i == 3 ? "fixture_m03" : null, null));
        }
        List<List<?>> ownership = new ArrayList<>();
        for (int i = 1; i <= 26; i++) {
            ownership.add(owner(plotNumber(i), String.format("M%02d", i), "1", date(i)));
        }
        ownership.add(owner(plotNumber(27), "M27", "1/2", date(27)));
        ownership.add(owner(plotNumber(27), "M28", "1/2", date(27)));
        ownership.add(owner(plotNumber(28), "M29", "2/3", date(28)));
        ownership.add(owner(plotNumber(28), "M30", "1/3", date(28)));
        for (String key : List.of("M31", "M32", "M33")) {
            ownership.add(owner(plotNumber(29), key, "1/3", date(29)));
        }
        ownership.add(owner(plotNumber(30), "M34", "1/2", date(30)));
        ownership.add(owner(plotNumber(30), "M35", "1/2", date(30)));
        return ImportWorkbooks.builder()
                .sheet("Plots", PLOT_HEADER, plots.toArray(List<?>[]::new))
                .sheet("Members", MEMBER_HEADER, members.toArray(List<?>[]::new))
                .sheet("Ownership", OWNERSHIP_HEADER, ownership.toArray(List<?>[]::new))
                .build();
    }

    /** Alternates a real Excel date and the text form, as people type both. */
    private static Object date(int index) {
        return index % 2 == 0 ? LocalDate.of(2020, 3, 1) : "01.03.2020";
    }

    /**
     * The mistakes functional spec 2.2 names, one or more per sheet: a duplicate plot number, a wrong cadastral number,
     * both areas and no area, an unknown kind, a bad e-mail, a member that cannot be recognised again, shares that do
     * not add up, an owner key that is not in the Members sheet, an unknown plot and a conflict with the recorded owner
     * of demo plot 9.
     */
    static byte[] errors() {
        return ImportWorkbooks.builder()
                .sheet("Plots", PLOT_HEADER,
                        plot("ERR-01", "Fixture Street 1", "PLOT", 600, null, null, null, null),
                        plot("ERR-02", null, "PLOT", 600, null, "123", null, null),
                        plot("ERR-01", null, "PLOT", 650, null, null, null, null),
                        plot("ERR-04", null, "PLOT", 600, 6, null, null, null),
                        plot("ERR-05", null, "PLOT", null, null, null, null, null),
                        plot("ERR-06", null, "garden", 600, null, null, null, null))
                .sheet("Members", MEMBER_HEADER,
                        member("K1", "Fixt01", "Person", "fixture.k1@import.example.com"),
                        member("K2", "Fixt02", "Person", "not-an-email"),
                        member("K3", "Fixt03", "Person", null),
                        member("K4", "Fixt04", "Person", "fixture.k4@import.example.com"))
                .sheet("Ownership", OWNERSHIP_HEADER,
                        owner("ERR-01", "K1", "1/2", "01.03.2020"),
                        owner("ERR-01", "K4", "1/3", "01.03.2020"),
                        owner("NOPE-1", "K1", "1", "01.03.2020"),
                        owner("9", "K1", "1", "01.03.2020"),
                        owner("ERR-04", "K9", "1", "01.03.2020"))
                .build();
    }
}
