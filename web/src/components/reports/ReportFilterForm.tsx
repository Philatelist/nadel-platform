import { useState } from 'react';
import { useListChargeTypes, type ReportFilterParam, type ReportInfo } from '@nadel/api-client';
import type { Messages } from '@nadel/shared';
import { BillingMonthSelect } from '../charges/BillingMonthSelect';
import { chargeTypeName } from '../../chargeTypeName';
import { useT } from '../../i18n';
import { useCan } from '../../me';
import { PARAM_CONTROLS, reportParams, type FilterValues } from '../../reportFilters';
import { Button, Select, TextField } from '../ui';

interface Option {
  value: string;
  label: string;
}

function paramLabel(t: Messages, param: ReportFilterParam): string {
  if (param === 'chargeTypeIds') return t.reports.filters.chargeTypes;
  if (param === 'street') return t.reports.filters.street;
  return t.reports.filters.params[param];
}

function optionLabels(t: Messages, param: ReportFilterParam): Readonly<Record<string, string>> {
  const options: Readonly<Record<string, Readonly<Record<string, string>>>> = t.reports.filters.options;
  return options[param] ?? {};
}

/**
 * The filters of one report, driven by the definitions the server returns: one control per query parameter
 * (`PARAM_CONTROLS` says which). Submitting hands the draft to `onSubmit`; nothing is validated here.
 */
export function ReportFilterForm({
  report,
  initial,
  onSubmit,
}: {
  report: ReportInfo;
  initial: FilterValues;
  onSubmit: (values: FilterValues) => void;
}) {
  const t = useT();
  const [draft, setDraft] = useState<FilterValues>(initial);
  const canListChargeTypes = useCan('TARIFFS_VIEW');
  const chargeTypes = useListChargeTypes({ query: { enabled: canListChargeTypes } });

  const set = (param: ReportFilterParam, list: string[]) =>
    setDraft((current) => ({ ...current, [param]: list }));
  const one = (param: ReportFilterParam): string => draft[param]?.[0] ?? '';

  const optionsOf = (param: ReportFilterParam): Option[] => {
    if (param === 'chargeTypeIds') {
      return (chargeTypes.data ?? []).map((type) => ({ value: type.id, label: chargeTypeName(type, t) }));
    }
    const control = PARAM_CONTROLS[param];
    const labels = optionLabels(t, param);
    return control.kind === 'select' || control.kind === 'multi'
      ? control.options.map((value) => ({ value, label: labels[value] ?? value }))
      : [];
  };

  return (
    <form
      aria-label={t.reports.filtersLabel}
      className="flex flex-wrap items-end gap-x-4 gap-y-3 rounded-panel border border-line bg-surface p-4"
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit(draft);
      }}
    >
      {reportParams(report.filters).map((param) => {
        const control = PARAM_CONTROLS[param];
        const label = paramLabel(t, param);
        switch (control.kind) {
          case 'month':
            return (
              <BillingMonthSelect
                key={param}
                label={label}
                emptyLabel={t.reports.noMonth}
                value={one(param)}
                onChange={(v) => set(param, [v])}
              />
            );
          case 'date':
            return (
              <TextField
                key={param}
                type="date"
                label={label}
                value={one(param)}
                onChange={(e) => set(param, [e.target.value])}
              />
            );
          case 'integer':
            return (
              <TextField
                key={param}
                type="number"
                inputMode="numeric"
                label={label}
                value={one(param)}
                onChange={(e) => set(param, [e.target.value])}
              />
            );
          case 'amount':
            return (
              <TextField
                key={param}
                inputMode="decimal"
                label={label}
                value={one(param)}
                onChange={(e) => set(param, [e.target.value])}
              />
            );
          case 'text':
            return (
              <TextField
                key={param}
                label={label}
                value={one(param)}
                onChange={(e) => set(param, [e.target.value])}
              />
            );
          case 'select':
            return (
              <Select
                key={param}
                label={label}
                value={one(param)}
                onChange={(e) => set(param, [e.target.value])}
              >
                <option value="">{t.reports.filters.all}</option>
                {optionsOf(param).map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </Select>
            );
          case 'multi': {
            const options = optionsOf(param);
            if (options.length === 0) return null;
            const chosen = draft[param] ?? [];
            return (
              <fieldset key={param} className="flex min-w-0 flex-col gap-1">
                <legend className="mb-1 text-sm font-medium text-ink-muted">{label}</legend>
                <div className="flex flex-wrap gap-x-4 gap-y-1">
                  {options.map((option) => (
                    <label key={option.value} className="flex min-h-11 items-center gap-2 text-[15px]">
                      <input
                        type="checkbox"
                        className="size-4 accent-accent"
                        checked={chosen.includes(option.value)}
                        onChange={(e) =>
                          set(
                            param,
                            e.target.checked
                              ? [...chosen, option.value]
                              : chosen.filter((value) => value !== option.value),
                          )
                        }
                      />
                      {option.label}
                    </label>
                  ))}
                </div>
              </fieldset>
            );
          }
        }
      })}
      <Button type="submit">{t.reports.show}</Button>
    </form>
  );
}
