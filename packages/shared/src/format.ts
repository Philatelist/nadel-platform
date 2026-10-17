/** Fills `{name}` placeholders in a message template. Unknown placeholders are left as written. */
export function format(template: string, vars: Readonly<Record<string, string | number>>): string {
  return template.replace(/\{(\w+)\}/g, (match, key: string) => {
    const value = vars[key];
    return value === undefined ? match : String(value);
  });
}
