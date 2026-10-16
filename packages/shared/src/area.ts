/** Area is stored in m² and shown in sotkas in the UI (1 sotka = 100 m²). */
export function formatSotky(areaM2: number, locale: string = 'uk-UA'): string {
  const sotky = areaM2 / 100;
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format(sotky);
}
