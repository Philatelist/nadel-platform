import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { Permission } from '@nadel/api-client';
import { describe, expect, it } from 'vitest';
import { checkHelp, formatIssues } from '../../help/check';
import { compileHelp } from '../../help/compile';
import { appRoutes } from '../App';
import { NAV_ITEMS } from '../nav';
import { appRoutePaths } from './appRoutePaths';

const root = resolve(__dirname, '..', '..', '..');

describe('real help content', () => {
  it('has no completeness issues', () => {
    const { pages, issues } = compileHelp(resolve(root, 'docs', 'help'));
    const all = [
      ...issues,
      ...checkHelp({
        pages,
        routes: appRoutePaths(appRoutes),
        navItems: NAV_ITEMS.map((item) => item.to),
        permissions: Object.values(Permission),
        shotExists: (locale, name) =>
          existsSync(resolve(root, 'web', 'public', 'help', 'img', locale, `${name}.png`)),
      }),
    ];
    expect(formatIssues(all)).toBe('');
  });
});
