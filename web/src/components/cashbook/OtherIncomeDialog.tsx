import {
  createOtherIncome,
  useListExpenseCategories,
  type MoneyAccountKind,
  type OtherIncomeInput,
} from '@nadel/api-client';
import { normalizeMoneyInput } from '@nadel/shared';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useLocale, useT } from '../../i18n';
import { kyivToday } from '../../kyivToday';
import { problemFieldErrors, type FieldErrors } from '../../problem';
import { Modal } from '../Modal';
import { QueryBoundary } from '../QueryBoundary';
import { Button, Notice, Select, TextArea, TextField } from '../ui';
import { categoryLabel } from './CategoryName';
import { invalidateCashbook } from './cashbookInvalidation';
import { cashbookProblemMessage } from './expenseErrors';

const ACCOUNTS = ['CASH', 'BANK'] as const satisfies readonly MoneyAccountKind[];
const MAX_DESCRIPTION = 500;

/** Record income that is not a member payment; mount it only while open. */
export function OtherIncomeDialog({ onClose }: { onClose: () => void }) {
  const t = useT();
  const { locale } = useLocale();
  const o = t.cashbook.otherIncome;
  const queryClient = useQueryClient();
  const categories = useListExpenseCategories({ kind: 'INCOME' });
  const [account, setAccount] = useState<MoneyAccountKind>('BANK');
  const [date, setDate] = useState(kyivToday());
  const [amountText, setAmountText] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [description, setDescription] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string>();

  const amount = normalizeMoneyInput(amountText);
  const amountError = amount === undefined || !/[1-9]/.test(amount) ? o.amountInvalid : undefined;
  const dateError = date === '' || date > kyivToday() ? o.dateFuture : undefined;
  const categoryError = categoryId === '' ? o.categoryRequired : undefined;

  const save = useMutation({
    mutationFn: (body: OtherIncomeInput) => createOtherIncome(body),
    onSuccess: async () => {
      await invalidateCashbook(queryClient);
      onClose();
    },
    onError: (error: unknown) => {
      const fields = problemFieldErrors(t, error);
      setServerErrors(fields);
      setNotice(Object.keys(fields).length > 0 ? undefined : cashbookProblemMessage(t, locale, error));
    },
  });

  function changing(set: (value: string) => void) {
    return (value: string) => {
      setServerErrors({});
      setNotice(undefined);
      set(value);
    };
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (amount === undefined || amountError || dateError || categoryError) return;
    save.mutate({
      account,
      date,
      amount,
      categoryId,
      description: description.trim() || undefined,
    });
  }

  return (
    <Modal title={o.title} onClose={onClose}>
      <form className="mt-3 flex flex-col gap-4" onSubmit={submit} noValidate>
        <p className="text-[15px] text-ink-muted">{o.text}</p>
        {notice && <Notice tone="danger">{notice}</Notice>}
        <Select
          label={o.account}
          value={account}
          onChange={(e) => changing((v) => setAccount(v as MoneyAccountKind))(e.target.value)}
        >
          {ACCOUNTS.map((kind) => (
            <option key={kind} value={kind}>
              {t.cashbook.account[kind]}
            </option>
          ))}
        </Select>
        <TextField
          label={o.date}
          type="date"
          value={date}
          max={kyivToday()}
          error={(submitted ? dateError : undefined) ?? serverErrors.date}
          onChange={(e) => changing(setDate)(e.target.value)}
        />
        <TextField
          label={o.amount}
          inputMode="decimal"
          value={amountText}
          error={(submitted ? amountError : undefined) ?? serverErrors.amount}
          onChange={(e) => changing(setAmountText)(e.target.value)}
        />
        <QueryBoundary query={categories} isEmpty={(data) => data.length === 0} emptyMessage={o.noCategories}>
          {(data) => (
            <Select
              label={o.category}
              value={categoryId}
              error={(submitted ? categoryError : undefined) ?? serverErrors.categoryId}
              onChange={(e) => changing(setCategoryId)(e.target.value)}
            >
              <option value="">{o.categoryPlaceholder}</option>
              {data
                .filter((category) => !category.archived)
                .map((category) => (
                  <option key={category.id} value={category.id}>
                    {categoryLabel(t, category)}
                  </option>
                ))}
            </Select>
          )}
        </QueryBoundary>
        <TextArea
          label={o.description}
          rows={3}
          maxLength={MAX_DESCRIPTION}
          value={description}
          error={serverErrors.description}
          onChange={(e) => changing(setDescription)(e.target.value)}
        />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            {o.cancel}
          </Button>
          <Button type="submit" disabled={save.isPending}>
            {save.isPending ? o.saving : o.save}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
