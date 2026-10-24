package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nadel.contract.model.ReportCode;
import org.junit.jupiter.api.Test;

class ReportCodeConverterTests {

    private final ReportCodeConverter converter = new ReportCodeConverter();

    @Test
    void readsTheKebabCaseCodeOfTheContract() {
        assertThat(converter.convert("land-tax")).isEqualTo(ReportCode.LAND_TAX);
        assertThat(converter.convert("charges-by-month")).isEqualTo(ReportCode.CHARGES_BY_MONTH);
    }

    @Test
    void refusesAnUnknownCodeAndTheConstantName() {
        assertThatThrownBy(() -> converter.convert("nonsense")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.convert("LAND_TAX")).isInstanceOf(IllegalArgumentException.class);
    }
}
