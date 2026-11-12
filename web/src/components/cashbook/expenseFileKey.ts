/** The stable cache key of a stored expense file; the same file has the same key wherever it is shown. */
export function expenseFileKey(expenseId: string, n: number): string {
  return `expense-${expenseId}-${n}`;
}
