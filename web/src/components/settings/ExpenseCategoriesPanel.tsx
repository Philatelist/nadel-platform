import {
  getListExpenseCategoriesQueryKey,
  useCreateExpenseCategory,
  useListExpenseCategories,
  useReorderExpenseCategories,
  useUpdateExpenseCategory,
  type ExpenseCategory,
  type ExpenseCategoryKind,
} from '@nadel/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useT } from '../../i18n';
import { problemMessage } from '../../problem';
import { categoryLabel, CategoryName } from '../cashbook/CategoryName';
import { QueryBoundary } from '../QueryBoundary';
import { Badge, Button, Notice, Panel, TextField } from '../ui';

const KINDS = ['EXPENSE', 'INCOME'] as const satisfies readonly ExpenseCategoryKind[];

/** One list (expenses or income): rename, reorder, archive or restore, add. */
function CategoryList({
  kind,
  categories,
  onError,
}: {
  kind: ExpenseCategoryKind;
  categories: readonly ExpenseCategory[];
  onError: (message: string | undefined) => void;
}) {
  const t = useT();
  const c = t.expenseCategories;
  const queryClient = useQueryClient();
  const reload = () => queryClient.invalidateQueries({ queryKey: [getListExpenseCategoriesQueryKey()[0]] });
  const [editing, setEditing] = useState<string>();
  const [name, setName] = useState('');
  const [submitted, setSubmitted] = useState(false);

  const fail = (error: unknown) => onError(problemMessage(t, error));
  const update = useUpdateExpenseCategory({
    mutation: {
      onSuccess: async () => {
        await reload();
        setEditing(undefined);
      },
      onError: fail,
    },
  });
  const create = useCreateExpenseCategory({
    mutation: {
      onSuccess: async () => {
        await reload();
        setEditing(undefined);
      },
      onError: fail,
    },
  });
  const reorder = useReorderExpenseCategories({
    mutation: {
      onSuccess: reload,
      onError: async (error: unknown) => {
        fail(error);
        await reload();
      },
    },
  });

  const active = categories.filter((category) => !category.archived);
  function move(category: ExpenseCategory, delta: -1 | 1) {
    const ids = active.map((item) => item.id);
    const index = ids.indexOf(category.id);
    const moved = ids.splice(index, 1);
    ids.splice(index + delta, 0, ...moved);
    onError(undefined);
    reorder.mutate({ data: { kind, ids } });
  }

  function startEdit(id: string, current: string) {
    onError(undefined);
    setSubmitted(false);
    setEditing(id);
    setName(current);
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    const trimmed = name.trim();
    if (trimmed === '') return;
    onError(undefined);
    if (editing === 'new') create.mutate({ data: { kind, name: trimmed } });
    else if (editing !== undefined) update.mutate({ id: editing, data: { name: trimmed } });
  }

  const busy = update.isPending || create.isPending || reorder.isPending;
  return (
    <section className="flex flex-col gap-2" aria-label={kind === 'EXPENSE' ? c.expense : c.income}>
      <h3 className="text-lg font-semibold">{kind === 'EXPENSE' ? c.expense : c.income}</h3>
      {categories.length === 0 && <p className="text-ink-muted">{c.empty}</p>}
      <ul className="flex flex-col">
        {categories.map((category) => {
          const position = active.indexOf(category);
          return (
            <li
              key={category.id}
              className="flex flex-wrap items-center justify-between gap-3 border-b border-line-subtle py-2 last:border-b-0"
            >
              {editing === category.id ? (
                <form className="flex flex-wrap items-start gap-2" onSubmit={submit} noValidate>
                  <TextField
                    label={c.name}
                    value={name}
                    maxLength={100}
                    error={submitted && name.trim() === '' ? c.nameRequired : undefined}
                    onChange={(e) => setName(e.target.value)}
                  />
                  <div className="flex gap-2 pt-6">
                    <Button type="submit" disabled={busy}>
                      {c.save}
                    </Button>
                    <Button variant="secondary" onClick={() => setEditing(undefined)}>
                      {c.cancel}
                    </Button>
                  </div>
                </form>
              ) : (
                <>
                  <span className="flex flex-wrap items-center gap-2 font-medium">
                    <CategoryName category={category} />
                    {category.archived && <Badge tone="neutral">{c.archived}</Badge>}
                  </span>
                  <div className="flex flex-wrap gap-2">
                    {!category.archived && (
                      <>
                        <Button
                          variant="secondary"
                          disabled={position <= 0 || busy}
                          onClick={() => move(category, -1)}
                        >
                          {c.moveUp}
                        </Button>
                        <Button
                          variant="secondary"
                          disabled={position === active.length - 1 || busy}
                          onClick={() => move(category, 1)}
                        >
                          {c.moveDown}
                        </Button>
                      </>
                    )}
                    <Button
                      variant="secondary"
                      onClick={() => startEdit(category.id, categoryLabel(t, category))}
                    >
                      {c.rename}
                    </Button>
                    <Button
                      variant={category.archived ? 'secondary' : 'danger'}
                      disabled={busy}
                      onClick={() => {
                        onError(undefined);
                        update.mutate({ id: category.id, data: { archived: !category.archived } });
                      }}
                    >
                      {category.archived ? c.restore : c.archive}
                    </Button>
                  </div>
                </>
              )}
            </li>
          );
        })}
        {editing === 'new' && (
          <li className="py-2">
            <form className="flex flex-wrap items-start gap-2" onSubmit={submit} noValidate>
              <TextField
                label={c.newName}
                value={name}
                maxLength={100}
                error={submitted && name.trim() === '' ? c.nameRequired : undefined}
                onChange={(e) => setName(e.target.value)}
              />
              <div className="flex gap-2 pt-6">
                <Button type="submit" disabled={busy}>
                  {c.save}
                </Button>
                <Button variant="secondary" onClick={() => setEditing(undefined)}>
                  {c.cancel}
                </Button>
              </div>
            </form>
          </li>
        )}
      </ul>
      <div>
        <Button variant="secondary" onClick={() => startEdit('new', '')}>
          {c.add}
        </Button>
      </div>
    </section>
  );
}

/** The expense and income categories of the cash book (settings; those who approve the cash book). */
export function ExpenseCategoriesPanel() {
  const t = useT();
  const query = useListExpenseCategories({ includeArchived: true });
  const [error, setError] = useState<string>();
  return (
    <Panel title={t.expenseCategories.title}>
      <div className="flex flex-col gap-4">
        <p className="text-ink-muted">{t.expenseCategories.text}</p>
        {error && <Notice tone="danger">{error}</Notice>}
        <QueryBoundary query={query}>
          {(all) =>
            KINDS.map((kind) => (
              <CategoryList
                key={kind}
                kind={kind}
                categories={all.filter((category) => category.kind === kind)}
                onError={setError}
              />
            ))
          }
        </QueryBoundary>
      </div>
    </Panel>
  );
}
