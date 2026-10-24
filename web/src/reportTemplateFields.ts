import type { ReportTemplateField } from '@nadel/api-client';
import { format, type Messages } from '@nadel/shared';

/**
 * The description of a catalog entry: `reportTemplates.fields.<field>` walked as a nested path, so
 * `rows.plots.debt.total` is `fields.rows.plots.debt.total`. A charge type family (`totals.charged.byChargeType`)
 * has the `{chargeType}` placeholder, filled with `chargeType`. Undefined when the catalog has no text.
 */
export function fieldDescription(
  t: Messages,
  field: Pick<ReportTemplateField, 'field'>,
  chargeType?: string,
): string | undefined {
  const node = field.field
    .split('.')
    .reduce<unknown>(
      (current, part) =>
        typeof current === 'object' && current !== null
          ? (current as Record<string, unknown>)[part]
          : undefined,
      t.reportTemplates.fields,
    );
  if (typeof node !== 'string') return undefined;
  return chargeType === undefined ? node : format(node, { chargeType });
}
