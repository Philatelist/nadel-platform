import type { ExpenseCategory } from '@nadel/api-client';
import type { Messages } from '@nadel/shared';
import { useT } from '../../i18n';
import { UserContent } from '../UserContent';

/** The name of a category: the one the cooperative gave, else the catalog name of its system code. */
export function categoryLabel(t: Messages, category: ExpenseCategory): string {
  if (category.name !== undefined) return category.name;
  const names: Readonly<Record<string, string>> = t.expenseCategories.system;
  return (category.systemCode !== undefined ? names[category.systemCode] : undefined) ?? '';
}

/** A category name for display; a name the cooperative typed is marked as user content. */
export function CategoryName({ category }: { category: ExpenseCategory }) {
  const t = useT();
  return category.name !== undefined ? (
    <UserContent>{category.name}</UserContent>
  ) : (
    <>{categoryLabel(t, category)}</>
  );
}
