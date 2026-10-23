import { messages } from '@nadel/shared';
import { describe, expect, it } from 'vitest';
import { auditPermissionChanges } from './auditValue';

describe('auditPermissionChanges', () => {
  const uk = messages('uk');
  const en = messages('en');

  it('lists only the added permissions', () => {
    expect(auditPermissionChanges('MEMBERS_VIEW', 'IMPORT_RUN, MEMBERS_VIEW', uk)).toEqual([
      '+ Запуск імпорту',
    ]);
  });

  it('lists only the removed permissions', () => {
    expect(auditPermissionChanges('MEMBERS_VIEW, TARIFFS_VIEW', 'MEMBERS_VIEW', uk)).toEqual([
      '− Перегляд тарифів',
    ]);
  });

  it('lists the added permissions, then the removed ones, in the UI language', () => {
    expect(
      auditPermissionChanges('MEMBERS_VIEW, TARIFFS_VIEW', 'IMPORT_RUN, MEMBERS_VIEW, PLOTS_EDIT', en),
    ).toEqual(['+ Run import', '+ Edit plots', '− View tariffs']);
  });

  it('treats an absent list as empty', () => {
    expect(auditPermissionChanges(undefined, 'PLOTS_EDIT', en)).toEqual(['+ Edit plots']);
    expect(auditPermissionChanges('PLOTS_EDIT', undefined, en)).toEqual(['− Edit plots']);
  });
});
