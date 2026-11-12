import { useEffect, useId, useSyncExternalStore } from 'react';

// Forms with unsaved input register here, so the «new version» bar never reloads the page under the user's hands.
const dirty = new Set<string>();
const listeners = new Set<() => void>();
let snapshot = false;

function emit(): void {
  const next = dirty.size > 0;
  if (next === snapshot) return;
  snapshot = next;
  for (const listener of listeners) listener();
}

/** Marks one form (by a unique key) dirty or clean; returns nothing. Exported for tests. */
export function setFormDirty(key: string, isDirty: boolean): void {
  if (isDirty) dirty.add(key);
  else dirty.delete(key);
  emit();
}

/** Call in a form: while `isDirty` is true an app update is not applied. Cleans up on unmount. */
export function useDirtyGuard(isDirty: boolean): void {
  const key = useId();
  useEffect(() => {
    setFormDirty(key, isDirty);
    return () => setFormDirty(key, false);
  }, [key, isDirty]);
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** True while at least one registered form holds unsaved input. */
export function useAnyDirtyForm(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => snapshot,
    () => false,
  );
}
