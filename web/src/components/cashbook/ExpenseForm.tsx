import {
  createExpense,
  downloadExpenseAttachment,
  type CreateExpenseBody,
  useListExpenseCategories,
  type Expense,
  type MoneyAccountKind,
} from '@nadel/api-client';
import { normalizeMoneyInput } from '@nadel/shared';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useLocale, useT } from '../../i18n';
import { kyivToday } from '../../kyivToday';
import { problemFieldErrors, type FieldErrors } from '../../problem';
import { QueryBoundary } from '../QueryBoundary';
import { Button, Notice, Select, TextArea, TextField } from '../ui';
import { FilePicker, type StoredFile } from '../files/FilePicker';
import { expenseFileKey } from './expenseFileKey';
import { categoryLabel } from './CategoryName';
import { invalidateCashbook } from './cashbookInvalidation';
import { attachmentProblemMessage, cashbookProblemMessage } from './expenseErrors';

/** A file of an earlier (reversed) expense that the server copies over. */
interface ReusedExpenseFile extends StoredFile {
  expenseId: string;
  n: number;
}

const ACCOUNTS = ['CASH', 'BANK'] as const satisfies readonly MoneyAccountKind[];

/** The expense form; `prefill` is a reversed expense whose values and files start the new one. */
export function ExpenseForm({
  prefill,
  onSaved,
}: {
  prefill?: Expense;
  onSaved: (expense: Expense) => void;
}) {
  const t = useT();
  const { locale } = useLocale();
  const f = t.expenses.form;
  const queryClient = useQueryClient();
  const categories = useListExpenseCategories({ kind: 'EXPENSE' });
  const offered = categories.data ?? [];

  const [account, setAccount] = useState<MoneyAccountKind>(prefill?.account ?? 'CASH');
  const [date, setDate] = useState(prefill?.date ?? kyivToday());
  const [amountText, setAmountText] = useState(prefill?.amount ?? '');
  // an archived category of the reversed expense is not offered: the person picks another
  const [categoryId, setCategoryId] = useState(
    prefill && !prefill.category.archived ? prefill.category.id : '',
  );
  const [counterparty, setCounterparty] = useState(prefill?.counterparty ?? '');
  const [description, setDescription] = useState(prefill?.description ?? '');
  const [documentNumber, setDocumentNumber] = useState(prefill?.documentNumber ?? '');
  const [documentDate, setDocumentDate] = useState(prefill?.documentDate ?? '');
  const [files, setFiles] = useState<File[]>([]);
  const [reused, setReused] = useState<ReusedExpenseFile[]>(
    () =>
      prefill?.attachments.map((a) => ({
        expenseId: prefill.id,
        n: a.n,
        key: expenseFileKey(prefill.id, a.n),
        fileName: a.fileName,
        contentType: a.contentType,
        load: () => downloadExpenseAttachment(prefill.id, a.n),
      })) ?? [],
  );
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [fileError, setFileError] = useState<string>();
  const [notice, setNotice] = useState<string>();

  const amount = normalizeMoneyInput(amountText);
  const amountError = amount === undefined || !/[1-9]/.test(amount) ? f.amountInvalid : undefined;
  const dateError = date === '' || date > kyivToday() ? f.dateFuture : undefined;
  const categoryError = categoryId === '' ? f.categoryRequired : undefined;

  const save = useMutation({
    mutationFn: (body: CreateExpenseBody) => createExpense(body),
    onSuccess: async (expense) => {
      await invalidateCashbook(queryClient);
      onSaved(expense);
    },
    onError: (error: unknown) => {
      const fields = problemFieldErrors(t, error);
      const attachment = attachmentProblemMessage(t, error);
      setServerErrors(fields);
      setFileError(attachment);
      const otherFields = Object.keys(fields).some((key) => !key.startsWith('files'));
      setNotice(
        otherFields || attachment !== undefined ? undefined : cashbookProblemMessage(t, locale, error),
      );
    },
  });

  function clearErrors() {
    setServerErrors({});
    setFileError(undefined);
    setNotice(undefined);
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (amount === undefined || amountError || dateError || categoryError) return;
    save.mutate({
      expense: {
        account,
        date,
        amount,
        categoryId,
        counterparty: counterparty.trim() || undefined,
        description: description.trim() || undefined,
        documentNumber: documentNumber.trim() || undefined,
        documentDate: documentDate || undefined,
        reuseAttachments:
          reused.length > 0 ? reused.map(({ expenseId, n }) => ({ expenseId, n })) : undefined,
      },
      files,
    });
  }

  const changing =
    <T,>(set: (value: T) => void) =>
    (value: T) => {
      clearErrors();
      set(value);
    };

  return (
    <form className="flex max-w-2xl flex-col gap-4" onSubmit={submit} noValidate>
      {prefill && <Notice tone="warning">{f.prefilled}</Notice>}
      {notice && <Notice tone="danger">{notice}</Notice>}
      <div className="flex flex-wrap items-start gap-x-6 gap-y-3">
        <Select
          label={f.account}
          value={account}
          onChange={(e) => changing(setAccount)(e.target.value as MoneyAccountKind)}
        >
          {ACCOUNTS.map((kind) => (
            <option key={kind} value={kind}>
              {t.cashbook.account[kind]}
            </option>
          ))}
        </Select>
        <TextField
          label={f.date}
          type="date"
          value={date}
          max={kyivToday()}
          error={(submitted ? dateError : undefined) ?? serverErrors.date}
          onChange={(e) => changing(setDate)(e.target.value)}
        />
        <TextField
          label={f.amount}
          inputMode="decimal"
          value={amountText}
          error={(submitted ? amountError : undefined) ?? serverErrors.amount}
          onChange={(e) => changing(setAmountText)(e.target.value)}
        />
      </div>
      <QueryBoundary query={categories} isEmpty={(data) => data.length === 0} emptyMessage={f.noCategories}>
        {() => (
          <Select
            label={f.category}
            value={categoryId}
            error={(submitted ? categoryError : undefined) ?? serverErrors.categoryId}
            onChange={(e) => changing(setCategoryId)(e.target.value)}
          >
            <option value="">{f.categoryPlaceholder}</option>
            {offered
              .filter((category) => !category.archived)
              .map((category) => (
                <option key={category.id} value={category.id}>
                  {categoryLabel(t, category)}
                </option>
              ))}
          </Select>
        )}
      </QueryBoundary>
      <TextField
        label={f.counterparty}
        value={counterparty}
        error={serverErrors.counterparty}
        onChange={(e) => changing(setCounterparty)(e.target.value)}
      />
      <TextArea
        label={f.description}
        rows={3}
        value={description}
        error={serverErrors.description}
        onChange={(e) => changing(setDescription)(e.target.value)}
      />
      <div className="flex flex-wrap items-start gap-x-6 gap-y-3">
        <TextField
          label={f.documentNumber}
          value={documentNumber}
          error={serverErrors.documentNumber}
          onChange={(e) => changing(setDocumentNumber)(e.target.value)}
        />
        <TextField
          label={f.documentDate}
          type="date"
          value={documentDate}
          error={serverErrors.documentDate}
          onChange={(e) => changing(setDocumentDate)(e.target.value)}
        />
      </div>
      <FilePicker
        files={files}
        reused={reused}
        capture="environment"
        serverError={fileError}
        onAdd={(added) => {
          clearErrors();
          setFiles((current) => [...current, ...added]);
        }}
        onRemoveFile={(index) => setFiles((current) => current.filter((_, i) => i !== index))}
        onRemoveReused={(index) => setReused((current) => current.filter((_, i) => i !== index))}
      />
      <div>
        <Button type="submit" disabled={save.isPending}>
          {save.isPending ? f.saving : f.submit}
        </Button>
      </div>
    </form>
  );
}
