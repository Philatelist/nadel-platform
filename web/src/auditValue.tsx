import type { AuditEntityType } from '@nadel/api-client';
import {
  format,
  formatDay,
  formatMonth,
  formatMoney,
  isMoney,
  formatMeterValue,
  formatRateWithCurrency,
  formatShare,
  normalizeRateInput,
  type Messages,
} from '@nadel/shared';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { UserContent } from './components/UserContent';
import { billingMonthLabel } from './components/charges/billingMonth';

const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;
const BASE_PREFIX = 'base:';

type Dictionary = Readonly<Record<string, string | undefined>>;

function user(text: string): ReactNode {
  return <UserContent>{text}</UserContent>;
}

/** `base:<CODE>` resolved through a catalog map; any other text is user content, shown as stored. */
function baseToken(raw: string, names: Dictionary): ReactNode {
  if (raw.startsWith(BASE_PREFIX)) {
    const name = names[raw.slice(BASE_PREFIX.length)];
    if (name !== undefined && Object.hasOwn(names, raw.slice(BASE_PREFIX.length))) return name;
  }
  return user(raw);
}

/** The name of a charge type in an audit entry: `base:<CODE>` for a never-renamed starting type. */
export function auditChargeTypeName(raw: string, t: Messages): ReactNode {
  return baseToken(raw, t.chargeTypes.defaultNames);
}

function roleNames(raw: string, names: Dictionary): ReactNode {
  return raw.split(', ').map((part, index) => (
    <span key={`${part}-${index}`}>
      {index > 0 && ', '}
      {baseToken(part, names)}
    </span>
  ));
}

interface OwnerLine {
  member: string;
  share?: string;
  from?: string;
  to?: string;
}

function parseOwners(raw: string): OwnerLine[] | undefined {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return undefined;
  }
  if (!Array.isArray(parsed)) return undefined;
  const lines: OwnerLine[] = [];
  for (const item of parsed as unknown[]) {
    if (typeof item !== 'object' || item === null) return undefined;
    const record = item as Record<string, unknown>;
    if (typeof record.member !== 'string') return undefined;
    const text = (key: string): string | undefined =>
      typeof record[key] === 'string' ? (record[key] as string) : undefined;
    lines.push({ member: record.member, share: text('share'), from: text('from'), to: text('to') });
  }
  return lines;
}

function day(value: string, locale: string): string {
  return ISO_DAY.test(value) ? formatDay(value, locale) : value;
}

function ownersList(raw: string, t: Messages, locale: string): ReactNode {
  const lines = parseOwners(raw);
  if (!lines) return user(raw);
  return lines.map((line, index) => {
    let period = '';
    if (line.from && line.to) {
      period = t.ownership.period
        .replace('{from}', day(line.from, locale))
        .replace('{to}', day(line.to, locale));
    } else if (line.from) {
      period = t.ownership.since.replace('{date}', day(line.from, locale));
    } else if (line.to) {
      period = t.ownership.period.replace('{from}', '').replace('{to}', day(line.to, locale)).trim();
    }
    return (
      <span key={`${line.member}-${index}`} className="block">
        {user(line.member)}
        {line.share ? ` ${formatShare(line.share)}` : ''}
        {period ? `, ${period}` : ''}
      </span>
    );
  });
}

const PLAIN_NUMBER = /^-?\d+(\.\d+)?$/;
const NUMBER_FIELDS = new Set([
  'count',
  'nodes',
  'areaM2',
  'position',
  'plotsCreated',
  'plotsUpdated',
  'plotsUnchanged',
  'membersCreated',
  'membersUpdated',
  'membersUnchanged',
  'ownershipsCreated',
  'ownershipsUnchanged',
  'metersCreated',
  'metersUnchanged',
  'waterConnectionsCreated',
  'waterConnectionsUnchanged',
  'openingBalancesCreated',
  'openingBalancesUnchanged',
]);

function localeNumber(raw: string, locale: string): string {
  const decimals = raw.split('.')[1]?.length ?? 0;
  return new Intl.NumberFormat(locale, {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  }).format(raw as unknown as number);
}

/**
 * One audit value as the user should read it: codes become catalog text, ISO dates and numbers follow the
 * locale, and anything the client does not recognise (including entries written before the code format) is
 * shown as stored. Text a person typed is wrapped in a user-content marker.
 */
export function auditValue(
  entityType: AuditEntityType,
  field: string,
  raw: string | undefined,
  t: Messages,
  locale: string,
): ReactNode {
  const a = t.audit;
  // an absent split is the cooperative default
  if ((raw === undefined || raw === '') && field === 'split') return a.splitDefault;
  if (raw === undefined || raw === '') return a.noValue;
  const catalog = (map: Dictionary): ReactNode => map[raw] ?? raw;
  switch (field) {
    case 'archived':
    case 'hasBuilding':
    case 'active':
      return raw === 'true' ? a.yes : a.no;
    case 'kind':
      if (Object.hasOwn(t.billing.notCharged.kind, raw)) return catalog(t.billing.notCharged.kind);
      return catalog(entityType === 'METER' ? t.meters.kind : t.plot.kind);
    case 'resource':
      return catalog(t.meters.resource);
    case 'initialReading':
    case 'finalReading':
      return /^\d+(\.\d{1,3})?$/.test(raw) ? formatMeterValue(raw, locale) : raw;
    case 'value':
      if (entityType !== 'METER_READING') return user(raw);
      return /^\d+(\.\d{1,3})?$/.test(raw) ? formatMeterValue(raw, locale) : raw;
    case 'rate':
      return normalizeRateInput(raw) === raw ? formatRateWithCurrency(raw, locale) : raw;
    case 'scope':
      return catalog({ ALL_PLOTS: t.tariffs.scope.all, SELECTED_PLOTS: t.tariffs.scope.selected });
    case 'frequency':
      return catalog(t.tariffs.frequency);
    case 'basis':
      return entityType === 'TARIFF' ? catalog(t.tariffs.basis) : user(raw);
    case 'deliveryChannel':
      return catalog(t.channel);
    case 'memberPlotStatusVisibility':
      return t.settings.visibility[raw as keyof Messages['settings']['visibility']]?.label ?? raw;
    case 'status':
      return entityType === 'BILLING_MONTH' ? catalog(t.billing.status) : user(raw);
    case 'lossesMode':
      return catalog(a.lossesMode);
    case 'split':
    case 'commonConsumerDefaultSplit':
      return catalog(a.split);
    case 'lossesPercent':
      return PLAIN_NUMBER.test(raw) ? `${localeNumber(raw, locale)} %` : raw;
    case 'effectiveFrom':
    case 'validFrom':
    case 'dueDate':
    case 'installedOn':
    case 'removedOn':
    case 'takenOn':
    case 'serviceFrom':
    case 'serviceTo':
    case 'from':
    case 'to':
    case 'until':
    case 'date':
      return ISO_DAY.test(raw) ? formatDay(raw, locale) : raw;
    case 'amount':
    case 'redAmount':
    case 'total':
      return isMoney(raw) ? formatMoney(raw, locale) : raw;
    case 'billingMonth':
    case 'month':
      return /^\d{4}-\d{2}$/.test(raw) ? formatMonth(raw, locale) : raw;
    case 'permissions':
      return codes(raw)
        .map((code) => permissionName(code, t))
        .join(', ');
    case 'owners':
      return ownersList(raw, t, locale);
    case 'roles':
      return roleNames(raw, t.roles);
    case 'chargeType':
      return auditChargeTypeName(raw, t);
    case 'reason':
      if (Object.hasOwn(t.billing.notCharged.reason, raw)) return catalog(t.billing.notCharged.reason);
      return user(raw);
    case 'outcome':
      return catalog(a.outcome);
    case 'runId':
      return (
        <Link to={`/charges/losses?run=${raw}`} className="text-accent underline">
          {t.charges.detail.openRun}
        </Link>
      );
    case 'source':
      // a water source is a meter name (user content) or `base:COOPERATIVE_SUPPLY`
      return entityType === 'PLOT'
        ? baseToken(raw, { COOPERATIVE_SUPPLY: t.water.cooperativeSupply })
        : user(raw);
    case 'replacementMeter':
    case 'parent':
      return user(raw);
    case 'name':
      if (entityType === 'ROLE') return baseToken(raw, t.roles);
      return entityType === 'CHARGE_TYPE' ? auditChargeTypeName(raw, t) : user(raw);
    case 'description':
      return entityType === 'ROLE' ? baseToken(raw, t.roleDescriptions) : user(raw);
    default:
      if (NUMBER_FIELDS.has(field) && PLAIN_NUMBER.test(raw)) return localeNumber(raw, locale);
      return user(raw);
  }
}

function codes(raw: string | undefined): string[] {
  return raw ? raw.split(', ') : [];
}

function permissionName(code: string, t: Messages): string {
  return t.permission[code as keyof Messages['permission']]?.name ?? code;
}

/**
 * A change of a role's permission set as the difference of the two stored lists: one line per added permission,
 * then one per removed one, instead of both full lists.
 */
export function auditPermissionChanges(
  oldValue: string | undefined,
  newValue: string | undefined,
  t: Messages,
): string[] {
  const before = codes(oldValue);
  const after = codes(newValue);
  return [
    ...after
      .filter((code) => !before.includes(code))
      .map((code) => format(t.audit.permissionAdded, { name: permissionName(code, t) })),
    ...before
      .filter((code) => !after.includes(code))
      .map((code) => format(t.audit.permissionRemoved, { name: permissionName(code, t) })),
  ];
}

/** The label of the changed record in the "Record" column. */
export function auditRecordLabel(
  entityType: AuditEntityType,
  label: string,
  t: Messages,
  locale: string,
): ReactNode {
  // a losses run and a common consumers run are labelled by their range, `from/to` in ISO days
  if (entityType === 'LOSSES_RUN' || entityType === 'COMMON_CONSUMER_RUN') {
    const [from, to] = label.split('/');
    if (from && to && ISO_DAY.test(from) && ISO_DAY.test(to)) {
      return format(t.billing.runHistory.range, { from: formatDay(from, locale), to: formatDay(to, locale) });
    }
    return label;
  }
  if (entityType === 'SETTINGS') return t.settings.title;
  if (entityType === 'BILLING_MONTH') return billingMonthLabel(label, t);
  if (entityType === 'ROLE') return baseToken(label, t.roles);
  if (entityType === 'CHARGE_TYPE') return auditChargeTypeName(label, t);
  return user(label);
}
