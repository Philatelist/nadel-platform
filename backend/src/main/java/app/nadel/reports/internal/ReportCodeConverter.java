package app.nadel.reports.internal;

import nadel.contract.model.ReportCode;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Reads the {@code code} path variable of {@code /reports/{code}} and {@code /reports/{code}.xlsx}. The codes are
 * kebab case ({@code land-tax}), so the default enum conversion by constant name ({@code LAND_TAX}) cannot read them.
 * Spring Boot registers every {@link Converter} bean with the MVC conversion service; an unknown code fails the
 * conversion and is answered 400 {@code MALFORMED_REQUEST} (the answer to every unknown enum value).
 */
@Component
class ReportCodeConverter implements Converter<String, ReportCode> {

    @Override
    public ReportCode convert(String source) {
        return ReportCode.fromValue(source);
    }
}
