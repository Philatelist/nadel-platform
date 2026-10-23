package app.nadel.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The generated import templates in both languages, read back with the same library the import will use. */
class TemplateWriterTests {

    private final byte[] uk = new TemplateWriter().write(TemplateLanguage.UK);
    private final byte[] en = new TemplateWriter().write(TemplateLanguage.EN);

    // ---- English: unchanged content

    @Test
    void englishHasThePlotsMembersOwnershipMetersAndOpeningBalancesSheetsInThatOrder() throws IOException {
        try (ReadableWorkbook workbook = open(en)) {
            assertThat(workbook.getSheets().map(Sheet::getName)).containsExactly("Plots", "Members", "Ownership",
                    "Meters", "Opening balances");
        }
    }

    @Test
    void englishHeadersAreTheCanonicalColumnNames() throws IOException {
        assertThat(headers(en, "Plots")).containsExactly(
                "number", "street", "kind", "area_m2", "area_sotky", "cadastral_number", "has_building", "notes",
                "water");
        assertThat(headers(en, "Members")).containsExactly(
                "external_id", "last_name", "first_name", "middle_name", "phones", "email", "postal_address",
                "delivery_channel", "telegram_username", "viber_phone");
        assertThat(headers(en, "Ownership")).containsExactly(
                "plot_number", "member_external_id", "share", "valid_from");
        assertThat(headers(en, "Meters")).containsExactly(
                "plot_number", "resource", "serial", "installed_on", "initial_reading");
        assertThat(headers(en, "Opening balances")).containsExactly(
                "plot_number", "charge_type", "amount", "date", "reason");
    }

    @Test
    void englishOpeningBalanceRowsExplainTheColumnsAndGiveAMembershipDebtOfTheExamplePlot() throws IOException {
        try (ReadableWorkbook workbook = open(en)) {
            assertThat(rowTexts(workbook, "Opening balances", 1)).containsExactly(
                    "# Required. Number of a plot from the Plots sheet or an existing plot",
                    "Required. Name of the charge type as the cooperative calls it, for example Membership fee",
                    "Required. Debt in UAH, greater than zero, up to 2 decimals",
                    "Required. As of date, DD.MM.YYYY, not in the future",
                    "Optional. Reason or document reference, at most 500 characters");
            assertThat(rowTexts(workbook, "Opening balances", 2)).containsExactly(
                    "# example: 12a", "Membership fee", "1500.00", "01.09.2026", "Debt for 2025");
        }
    }

    @Test
    void englishMetersRowsExplainTheColumnsAndGiveAnElectricityMeterOfTheExamplePlot() throws IOException {
        try (ReadableWorkbook workbook = open(en)) {
            assertThat(rowTexts(workbook, "Meters", 1)).containsExactly(
                    "# Required. Number of a plot from the Plots sheet or an existing plot. Plot meters only",
                    "Required. ELECTRICITY or WATER",
                    "Required. Serial number of the meter, at most 50 characters",
                    "Required. Installation date, DD.MM.YYYY, not in the future",
                    "Required. Reading on the installation date, up to 3 decimals");
            assertThat(rowTexts(workbook, "Meters", 2)).containsExactly(
                    "# example: 12a", "ELECTRICITY", "0456789", "01.03.2020", "1250.5");
        }
    }

    @Test
    void englishInstructionAndExampleRowsAreAsTheyAlwaysWere() throws IOException {
        try (ReadableWorkbook workbook = open(en)) {
            assertThat(rowTexts(workbook, "Plots", 1)).containsExactly(
                    "# Required. Plot number, unique in the cooperative", "Street name",
                    "PLOT or COMMON (common land). Required for a new plot",
                    "Area in square metres. Fill exactly one of area_m2 and area_sotky",
                    "Area in sotky (1 sotka = 100 m2). Fill exactly one of the two",
                    "Optional. Format 1234567890:12:345:6789", "yes or no. Default no", "Free text",
                    "Optional. yes connects the plot to the cooperative's water supply; the name of a well connects it "
                            + "to that well; no or empty changes nothing");
            assertThat(rowTexts(workbook, "Plots", 2)).containsExactly(
                    "# example: 12a", "Garden street", "PLOT", "600", "", "1234567890:12:345:6789", "yes",
                    "Corner plot", "yes");
            assertThat(rowTexts(workbook, "Members", 1)).containsExactly(
                    "# Required. Any key unique within this file, used by the Ownership sheet", "Required", "Required",
                    "Optional", "Comma-separated, international format",
                    "Optional. Used to match an existing member on re-import", "Optional",
                    "EMAIL, TELEGRAM, VIBER or PAPER. The channel needs its contact",
                    "Needed for the TELEGRAM channel", "Needed for the VIBER channel");
            assertThat(rowTexts(workbook, "Members", 2)).containsExactly(
                    "# example: M-001", "Shevchenko", "Taras", "Hryhorovych", "+380501234567, +380671234567",
                    "taras@example.com", "1 Garden street, Kyiv", "EMAIL", "", "");
            assertThat(rowTexts(workbook, "Ownership", 1)).containsExactly(
                    "# Required. Number of a plot from the Plots sheet or an existing plot",
                    "Required. external_id from the Members sheet",
                    "Share of the plot, for example 1, 1/2 or 1/3. Shares of a plot add up to 1",
                    "Owner since, DD.MM.YYYY. The same date for every owner of a plot");
            assertThat(rowTexts(workbook, "Ownership", 2)).containsExactly(
                    "# example: 12a", "M-001", "1", "01.03.2020");
        }
    }

    @Test
    void englishContainsNoCyrillicText() throws IOException {
        for (String text : allTexts(en)) {
            assertThat(text).doesNotContainPattern("[\\u0400-\\u04FF]");
        }
    }

    // ---- Ukrainian

    @Test
    void ukrainianHasTheUkrainianSheetNamesInThatOrder() throws IOException {
        try (ReadableWorkbook workbook = open(uk)) {
            assertThat(workbook.getSheets().map(Sheet::getName)).containsExactly("Ділянки", "Члени", "Власність",
                    "Лічильники", "Вхідне сальдо");
        }
    }

    @Test
    void ukrainianHeadersAreTheFirstWordsOfTheVocabulary() throws IOException {
        assertThat(headers(uk, "Ділянки")).containsExactly(
                "Номер ділянки", "Вулиця", "Тип ділянки", "Площа, м²", "Площа, сот.", "Кадастровий номер",
                "Є будівля", "Примітки", "Вода");
        assertThat(headers(uk, "Члени")).containsExactly(
                "Ключ члена", "Прізвище", "Ім'я", "По батькові", "Телефони", "Електронна пошта", "Поштова адреса",
                "Канал доставки", "Нік у Telegram", "Телефон Viber");
        assertThat(headers(uk, "Власність")).containsExactly("Номер ділянки", "Ключ члена", "Частка", "Власник з");
        assertThat(headers(uk, "Лічильники")).containsExactly("Номер ділянки", "Ресурс", "Заводський номер",
                "Дата встановлення", "Початковий показ");
        assertThat(headers(uk, "Вхідне сальдо")).containsExactly("Номер ділянки", "Вид нарахування", "Сума", "Дата",
                "Підстава");
    }

    @Test
    void ukrainianOpeningBalanceRowsAreUkrainianAndTheExampleIsADebtOfTheExamplePlot() throws IOException {
        try (ReadableWorkbook workbook = open(uk)) {
            assertThat(rowTexts(workbook, "Вхідне сальдо", 1)).containsExactly(
                    "# Обов'язково. Номер ділянки з аркуша «Ділянки» або наявної ділянки",
                    "Обов'язково. Назва виду нарахування, як у кооперативі, наприклад Членський внесок",
                    "Обов'язково. Борг у гривнях, більше нуля, до 2 знаків після коми",
                    "Обов'язково. Станом на дату, ДД.ММ.РРРР, не пізніше сьогодні",
                    "Необов'язково. Підстава або документ, до 500 символів");
            assertThat(rowTexts(workbook, "Вхідне сальдо", 2)).containsExactly(
                    "# приклад: 31а", "Членський внесок", "1500,00", "01.09.2026", "Борг за 2025 рік");
            String plot = rowTexts(workbook, "Ділянки", 2).get(0);
            assertThat(rowTexts(workbook, "Вхідне сальдо", 2).get(0)).isEqualTo(plot);
        }
    }

    @Test
    void ukrainianMetersRowsAreUkrainianAndTheExampleIsAMeterOfTheExamplePlot() throws IOException {
        try (ReadableWorkbook workbook = open(uk)) {
            assertThat(rowTexts(workbook, "Лічильники", 1)).containsExactly(
                    "# Обов'язково. Номер ділянки з аркуша «Ділянки» або наявної ділянки. Лише лічильники ділянок",
                    "Обов'язково. ЕЛЕКТРОЕНЕРГІЯ або ВОДА",
                    "Обов'язково. Заводський номер лічильника, до 50 символів",
                    "Обов'язково. Дата встановлення, ДД.ММ.РРРР, не пізніше сьогодні",
                    "Обов'язково. Показ на дату встановлення, до 3 знаків після коми");
            assertThat(rowTexts(workbook, "Лічильники", 2)).containsExactly(
                    "# приклад: 31а", "ЕЛЕКТРОЕНЕРГІЯ", "0456789", "01.03.2020", "1250,5");
            String plot = rowTexts(workbook, "Ділянки", 2).get(0);
            assertThat(rowTexts(workbook, "Лічильники", 2).get(0)).isEqualTo(plot);
        }
    }

    @Test
    void ukrainianInstructionRowsAreUkrainianAndFilledForEveryColumn() throws IOException {
        try (ReadableWorkbook workbook = open(uk)) {
            assertThat(rowTexts(workbook, "Ділянки", 1)).containsExactly(
                    "# Обов'язково. Номер ділянки, унікальний у кооперативі", "Назва вулиці",
                    "ДІЛЯНКА або СПІЛЬНА ЗЕМЛЯ. Обов'язково для нової ділянки",
                    "Площа в квадратних метрах. Заповніть рівно одну з колонок «Площа, м²» і «Площа, сот.»",
                    "Площа в сотках (1 сотка = 100 м²). Заповніть рівно одну з двох колонок",
                    "Необов'язково. Формат 1234567890:12:345:6789", "так або ні. За замовчуванням ні",
                    "Довільний текст",
                    "Необов'язково. так — підключити до водопроводу кооперативу; назва свердловини — до неї; ні або "
                            + "порожньо — не змінювати");
            assertThat(rowTexts(workbook, "Члени", 1)).hasSize(10).allSatisfy(text -> assertThat(text).isNotBlank());
            assertThat(rowTexts(workbook, "Члени", 1).get(0)).contains("«Власність»");
            assertThat(rowTexts(workbook, "Члени", 1).get(7))
                    .isEqualTo("ЕЛ. ПОШТА, ТЕЛЕГРАМ, ВАЙБЕР або ПАПЕР. Для каналу потрібен його контакт");
            assertThat(rowTexts(workbook, "Власність", 1).get(1))
                    .isEqualTo("Обов'язково. Ключ із колонки «Ключ члена» аркуша «Члени»");
        }
    }

    @Test
    void ukrainianExampleRowsCarryRealisticUkrainianValues() throws IOException {
        try (ReadableWorkbook workbook = open(uk)) {
            assertThat(rowTexts(workbook, "Ділянки", 2)).containsExactly(
                    "# приклад: 31а", "вулиця Садова", "ДІЛЯНКА", "600", "", "1234567890:12:345:6789", "так",
                    "Кутова ділянка", "так");
            assertThat(rowTexts(workbook, "Члени", 2)).containsExactly(
                    "# приклад: Ч-001", "Шевченко", "Тарас", "Григорович", "+380 67 123 45 67",
                    "taras.shevchenko@example.com", "вулиця Садова, 1, Київ", "ЕЛ. ПОШТА", "", "");
            assertThat(rowTexts(workbook, "Власність", 2)).containsExactly(
                    "# приклад: 31а", "Ч-001", "1", "01.03.2020");
        }
    }

    @Test
    void ukrainianMemberExampleKeyIsTheOneTheOwnershipExampleUses() throws IOException {
        try (ReadableWorkbook workbook = open(uk)) {
            String memberKey = rowTexts(workbook, "Члени", 2).get(0).replaceFirst("^# приклад: ", "");
            String ownerKey = rowTexts(workbook, "Власність", 2).get(1);
            assertThat(ownerKey).isEqualTo(memberKey);
            String plot = rowTexts(workbook, "Ділянки", 2).get(0).replaceFirst("^# приклад: ", "");
            assertThat(rowTexts(workbook, "Власність", 2).get(0).replaceFirst("^# приклад: ", "")).isEqualTo(plot);
        }
    }

    // ---- both languages

    @ParameterizedTest
    @EnumSource(TemplateLanguage.class)
    void everySheetHasHeaderInstructionAndExampleRowsAndTheLastTwoStartWithAHash(TemplateLanguage language)
            throws IOException {
        try (ReadableWorkbook workbook = open(language)) {
            for (Sheet sheet : workbook.getSheets().toList()) {
                List<Row> rows = sheet.read();
                assertThat(rows).as(sheet.getName()).hasSize(3);
                assertThat(rows.get(0).getCellText(0)).doesNotStartWith("#");
                assertThat(rows.get(1).getCellText(0)).as(sheet.getName() + " instruction row").startsWith("#");
                assertThat(rows.get(2).getCellText(0)).as(sheet.getName() + " example row").startsWith("#");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(TemplateLanguage.class)
    void identifierAndPhoneColumnsHaveTheTextFormat(TemplateLanguage language) throws IOException {
        // text columns: Plots number and cadastral_number; Members external_id, phones, viber_phone;
        // Ownership plot_number, member_external_id and share ("1/2" typed into a General cell becomes a date);
        // Meters plot_number and serial
        byte[] template = language == TemplateLanguage.UK ? uk : en;
        assertThat(textColumns(template, 1)).containsExactly(1, 6);
        assertThat(textColumns(template, 2)).containsExactly(1, 5, 10);
        assertThat(textColumns(template, 3)).containsExactly(1, 2, 3);
        // Meters plot_number and serial (a serial number can start with zeros)
        assertThat(textColumns(template, 4)).containsExactly(1, 3);
        // Opening balances plot_number
        assertThat(textColumns(template, 5)).containsExactly(1);
        assertThat(entry(template, "xl/styles.xml")).contains("formatCode=\"@\"");
    }

    @ParameterizedTest
    @EnumSource(TemplateLanguage.class)
    void everyTextOfTheTemplateIsResolved(TemplateLanguage language) throws IOException {
        for (String text : allTexts(language == TemplateLanguage.UK ? uk : en)) {
            assertThat(text).doesNotContain("{").doesNotContain("}");
        }
    }

    // ---- helpers

    private ReadableWorkbook open(TemplateLanguage language) throws IOException {
        return open(language == TemplateLanguage.UK ? uk : en);
    }

    private static ReadableWorkbook open(byte[] template) throws IOException {
        return new ReadableWorkbook(new ByteArrayInputStream(template));
    }

    /** The texts of every cell of the workbook. */
    private static List<String> allTexts(byte[] template) throws IOException {
        List<String> texts = new ArrayList<>();
        try (ReadableWorkbook workbook = open(template)) {
            for (Sheet sheet : workbook.getSheets().toList()) {
                texts.add(sheet.getName());
                for (Row row : sheet.read()) {
                    for (int column = 0; column < row.getCellCount(); column++) {
                        texts.add(row.getCellText(column));
                    }
                }
            }
        }
        return texts;
    }

    /** The texts of the cells of a row (0 header, 1 instruction, 2 example), empty cells as empty text. */
    private static List<String> rowTexts(ReadableWorkbook workbook, String sheet, int row) throws IOException {
        Row cells = workbook.findSheet(sheet).orElseThrow().read().get(row);
        List<String> texts = new ArrayList<>();
        int count = switch (sheet) {
            case "Plots", "Ділянки" -> 9;
            case "Members", "Члени" -> 10;
            case "Meters", "Лічильники", "Opening balances", "Вхідне сальдо" -> 5;
            default -> 4;
        };
        for (int column = 0; column < count; column++) {
            texts.add(cells.getCellText(column));
        }
        return texts;
    }

    private static List<String> headers(byte[] template, String sheet) throws IOException {
        try (ReadableWorkbook workbook = open(template)) {
            Row header = workbook.findSheet(sheet).orElseThrow().read().get(0);
            List<String> names = new ArrayList<>();
            for (int column = 0; column < header.getCellCount(); column++) {
                names.add(header.getCellText(column));
            }
            return names;
        }
    }

    /** 1-based indexes of the columns that carry a column-level style in the given worksheet part. */
    private static List<Integer> textColumns(byte[] template, int sheetNumber) throws IOException {
        Matcher cols = Pattern.compile("<col [^>]*min=\"(\\d+)\"[^>]*style=\"\\d+\"")
                .matcher(entry(template, "xl/worksheets/sheet" + sheetNumber + ".xml"));
        List<Integer> indexes = new ArrayList<>();
        while (cols.find()) {
            indexes.add(Integer.parseInt(cols.group(1)));
        }
        return indexes;
    }

    /** A part of the package as text. A temp file and ZipFile, because the writer's entries use data descriptors. */
    private static String entry(byte[] template, String name) throws IOException {
        Path file = Files.createTempFile("nadel-template", ".xlsx");
        try {
            Files.write(file, template);
            try (ZipFile zip = new ZipFile(file.toFile())) {
                ZipEntry entry = zip.getEntry(name);
                assertThat(entry).as("part " + name).isNotNull();
                return new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            Files.delete(file);
        }
    }
}
