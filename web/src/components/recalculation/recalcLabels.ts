import type {
  RecalculationAction,
  RecalculationCauseKind,
  RecalculationCauseType,
  RecalculationChangeReason,
} from '@nadel/api-client';
import { format, formatMonth, type Messages } from '@nadel/shared';

/** Adding a variant to a generated enum makes every switch below a compile error until it is handled. */
export function assertNever(value: never): never {
  throw new Error(`Unhandled variant: ${String(value)}`);
}

/** An adjustment names the month it is booked in (`bookedIn`); the other actions are plain texts. */
export function actionLabel(
  t: Messages,
  line: { action: RecalculationAction; bookedIn?: string },
  locale: string,
): string {
  const a = t.recalculation.action;
  switch (line.action) {
    case 'REVERSE_AND_RECHARGE':
      return a.REVERSE_AND_RECHARGE;
    case 'ADJUST':
      return format(a.ADJUST, { month: line.bookedIn ? formatMonth(line.bookedIn, locale) : '' }).trim();
    case 'NEW':
      return a.NEW;
    case 'REVERSE':
      return a.REVERSE;
    default:
      return assertNever(line.action);
  }
}

export function causeKindLabel(t: Messages, kind: RecalculationCauseKind): string {
  const k = t.recalculation.causeKind;
  switch (kind) {
    case 'MANUAL':
      return k.MANUAL;
    case 'TARIFF_VERSION':
      return k.TARIFF_VERSION;
    case 'READING':
      return k.READING;
    default:
      return assertNever(kind);
  }
}

export function causeTypeLabel(t: Messages, type: RecalculationCauseType): string {
  const c = t.recalculation.causeType;
  switch (type) {
    case 'MANUAL':
      return c.MANUAL;
    case 'TARIFF_VERSION_ADD':
      return c.TARIFF_VERSION_ADD;
    case 'TARIFF_VERSION_UPDATE':
      return c.TARIFF_VERSION_UPDATE;
    case 'TARIFF_VERSION_DELETE':
      return c.TARIFF_VERSION_DELETE;
    case 'READING_ENTER':
      return c.READING_ENTER;
    case 'READING_CORRECT':
      return c.READING_CORRECT;
    case 'READING_DELETE':
      return c.READING_DELETE;
    default:
      return assertNever(type);
  }
}

/** The specific cause when the server names one, otherwise its kind. */
export function causeLabel(
  t: Messages,
  cause: { cause: RecalculationCauseKind; causeType?: RecalculationCauseType },
): string {
  return cause.causeType ? causeTypeLabel(t, cause.causeType) : causeKindLabel(t, cause.cause);
}

export function changeReasonLabel(t: Messages, reason: RecalculationChangeReason): string {
  const r = t.recalculation.changeReason;
  switch (reason) {
    case 'TARIFF_VERSION':
      return r.TARIFF_VERSION;
    case 'READING':
      return r.READING;
    case 'AREA':
      return r.AREA;
    case 'WATER_CONNECTION':
      return r.WATER_CONNECTION;
    case 'TARIFF_SCOPE':
      return r.TARIFF_SCOPE;
    case 'METER_TREE':
      return r.METER_TREE;
    case 'RUN':
      return r.RUN;
    case 'NOT_CHARGED':
      return r.NOT_CHARGED;
    default:
      return assertNever(reason);
  }
}
