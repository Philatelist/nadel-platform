import type { RouteObject } from 'react-router';

function join(parent: string, path: string): string {
  return `${parent.replace(/\/$/, '')}/${path.replace(/^\//, '')}`;
}

function isExcluded(pattern: string): boolean {
  return pattern === '/help' || pattern.startsWith('/help/') || pattern.endsWith('*');
}

function walk(routes: readonly RouteObject[], parent: string, out: Set<string>): void {
  for (const route of routes) {
    if (route.index) continue;
    const full = route.path === undefined ? parent : join(parent, route.path);
    const children = route.children ?? [];
    const hasIndexChild = children.some((child) => child.index);
    // A path with only layout-style children is a wrapper; with no children, or with an index child, it is a screen.
    if (route.path !== undefined && (children.length === 0 || hasIndexChild) && !isExcluded(full)) {
      out.add(full);
    }
    walk(children, full, out);
  }
}

/** Full path patterns of the application's screens; skips `*`, index routes and the help's own routes. */
export function appRoutePaths(routes: readonly RouteObject[]): string[] {
  const out = new Set<string>();
  walk(routes, '', out);
  return [...out];
}
