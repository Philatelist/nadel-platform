package app.nadel.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.problem.ApiProblemException;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import nadel.contract.model.ImportProblem;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/** The file name stored with a run is reduced to a safe base name. */
class ImportServiceTests {

    @Test
    void thePathIsDroppedFromEitherKindOfSeparator() {
        assertThat(ImportService.baseName("C:\\Users\\chair\\plots.xlsx")).isEqualTo("plots.xlsx");
        assertThat(ImportService.baseName("/tmp/uploads/../plots.xlsx")).isEqualTo("plots.xlsx");
        assertThat(ImportService.baseName("plots.xlsx")).isEqualTo("plots.xlsx");
    }

    @Test
    void controlAndInvisibleFormattingCharactersAreRemoved() {
        assertThat(ImportService.baseName("pl\u0000ots\n.xlsx")).isEqualTo("plots.xlsx");
        assertThat(ImportService.baseName("\u202Efdp.xlsx\u200B")).isEqualTo("fdp.xlsx");
        assertThat(ImportService.baseName("  Ділянки 2026.xlsx  ")).isEqualTo("Ділянки 2026.xlsx");
    }

    @Test
    void aMissingOrEmptyNameGetsADefault() {
        assertThat(ImportService.baseName(null)).isEqualTo("import.xlsx");
        assertThat(ImportService.baseName("")).isEqualTo("import.xlsx");
        assertThat(ImportService.baseName("dir/")).isEqualTo("import.xlsx");
        assertThat(ImportService.baseName("\u0007\u0007")).isEqualTo("import.xlsx");
    }

    @Test
    void aLongNameIsCutAtTwoHundredFiftyFiveCharactersWithoutSplittingACharacter() {
        String longName = "a".repeat(300) + ".xlsx";
        String emoji = "\uD83D\uDE00".repeat(300);

        assertThat(ImportService.baseName(longName)).hasSize(255);
        String cut = ImportService.baseName(emoji);
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(255);
        assertThat(cut).isEqualTo("\uD83D\uDE00".repeat(255));
    }

    @Test
    void aFileThatCannotBeReadIsNotAWorkbook() {
        MultipartFile unreadable = new MockMultipartFile("file", "plots.xlsx", null, new byte[] {1}) {
            @Override
            public byte[] getBytes() throws IOException {
                throw new IOException("disk gone");
            }
        };
        ImportService service = new ImportService(null, null, null, Clock.systemUTC());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.preview(unreadable, null))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> assertThat(e.code()).isEqualTo("FILE_NOT_XLSX"));
    }

    @Test
    void aProblemKeepsItsParametersInTheApiModel() {
        ImportProblem api = ImportService.toApi(new RowProblem(ImportSheet.MEMBERS, 7, "email", "INVALID",
                Map.of("limit", "5")));

        assertThat(api.getSheet()).isEqualTo(ImportProblem.SheetEnum.MEMBERS);
        assertThat(api.getRow()).isEqualTo(7);
        assertThat(api.getColumn()).isEqualTo("email");
        assertThat(api.getCode()).isEqualTo("INVALID");
        assertThat(api.getParams()).containsEntry("limit", "5");
        assertThat(ImportService.toApi(new RowProblem(ImportSheet.FILE, 0, null, "SHEET_MISSING")).getParams())
                .isEmpty();
    }
}
