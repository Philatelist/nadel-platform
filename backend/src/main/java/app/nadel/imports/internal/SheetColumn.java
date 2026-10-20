package app.nadel.imports.internal;

/** A column of an import sheet: the canonical key used in the template, in problems and in the API. */
interface SheetColumn {

    /** The canonical key, for example {@code area_m2}. */
    String key();

    /** True when a sheet without this column cannot be read ({@code HEADER_MISSING}). */
    boolean required();
}
