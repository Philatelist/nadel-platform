import {
  DeliveryChannel,
  getGetMemberQueryKey,
  getListMembersQueryKey,
  useCreateMember,
  useUpdateMember,
  type Member,
  type MemberInput,
} from '@nadel/api-client';
import { format } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useRef, useState, type FormEvent } from 'react';
import { useT } from '../i18n';
import { useCan } from '../me';
import { problemFieldErrors, problemMessage, type FieldErrors } from '../problem';
import { Banner } from './Banner';
import { Field } from './ui/Field';
import { SelectInput, TextArea, TextInput } from './inputs';

interface PhoneRow {
  id: string;
  value: string;
}

const CHANNELS = [
  DeliveryChannel.PAPER,
  DeliveryChannel.EMAIL,
  DeliveryChannel.TELEGRAM,
  DeliveryChannel.VIBER,
] as const;

const buttonClass = 'rounded-md border border-line px-3 py-1.5 text-sm';

function optional(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed === '' ? undefined : trimmed;
}

/**
 * Blank phone rows are dropped before sending, so the server's `phones[i]` refers to the i-th sent
 * phone. `sentRowIndexes[i]` is the form row that became sent phone i; errors are moved back to it.
 */
export function remapPhoneErrors(errors: FieldErrors, sentRowIndexes: readonly number[]): FieldErrors {
  return Object.fromEntries(
    Object.entries(errors).map(([key, message]) => {
      const match = /^phones\[(\d+)\]$/.exec(key);
      const row = match ? sentRowIndexes[Number(match[1])] : undefined;
      return [row === undefined ? key : `phones[${row}]`, message];
    }),
  );
}

/**
 * Create / edit form for a member. Initial values come from `member` once, so mount it with a `key`
 * (for example the member id) instead of syncing state in effects.
 */
export function MemberForm({
  member,
  onSaved,
  onCancel,
}: {
  member?: Member;
  onSaved: (saved: Member) => void;
  onCancel: () => void;
}) {
  const t = useT();
  const f = t.member.fields;
  const canViewNotes = useCan('MEMBERS_VIEW');
  const queryClient = useQueryClient();

  const [lastName, setLastName] = useState(member?.lastName ?? '');
  const [firstName, setFirstName] = useState(member?.firstName ?? '');
  const [middleName, setMiddleName] = useState(member?.middleName ?? '');
  const [phones, setPhones] = useState<PhoneRow[]>(() =>
    (member?.phones ?? []).map((value) => ({ id: crypto.randomUUID(), value })),
  );
  const [email, setEmail] = useState(member?.email ?? '');
  const [postalAddress, setPostalAddress] = useState(member?.postalAddress ?? '');
  const [deliveryChannel, setDeliveryChannel] = useState<MemberInput['deliveryChannel']>(
    member?.deliveryChannel ?? DeliveryChannel.PAPER,
  );
  const [telegramUsername, setTelegramUsername] = useState(member?.telegramUsername ?? '');
  const [viberPhone, setViberPhone] = useState(member?.viberPhone ?? '');
  const [notes, setNotes] = useState(member?.notes ?? '');
  const [errors, setErrors] = useState<FieldErrors>({});
  const [bannerError, setBannerError] = useState<string>();
  const sentPhoneRows = useRef<number[]>([]);

  async function handleSaved(saved: Member) {
    queryClient.setQueryData(getGetMemberQueryKey(saved.id), saved);
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: getListMembersQueryKey() }),
      queryClient.invalidateQueries({ queryKey: getGetMemberQueryKey(saved.id) }),
    ]);
    onSaved(saved);
  }

  function handleError(error: unknown) {
    const fieldErrors = remapPhoneErrors(problemFieldErrors(t, error), sentPhoneRows.current);
    setErrors(fieldErrors);
    setBannerError(Object.keys(fieldErrors).length === 0 ? problemMessage(t, error) : undefined);
  }

  const create = useCreateMember({ mutation: { onSuccess: handleSaved, onError: handleError } });
  const update = useUpdateMember({ mutation: { onSuccess: handleSaved, onError: handleError } });
  const isPending = create.isPending || update.isPending;

  function clearError(...keys: string[]) {
    setErrors((prev) => {
      const next = Object.fromEntries(Object.entries(prev).filter(([k]) => !keys.includes(k)));
      return Object.keys(next).length === Object.keys(prev).length ? prev : next;
    });
  }

  function clearPhoneErrors() {
    setErrors((prev) => Object.fromEntries(Object.entries(prev).filter(([k]) => !k.startsWith('phones['))));
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    setBannerError(undefined);
    const clientErrors: Record<string, string> = {};
    if (lastName.trim() === '') clientErrors.lastName = t.validation.REQUIRED_lastName;
    if (firstName.trim() === '') clientErrors.firstName = t.validation.REQUIRED_firstName;
    setErrors(clientErrors);
    if (Object.keys(clientErrors).length > 0) return;

    sentPhoneRows.current = phones.flatMap((p, index) => (p.value.trim() === '' ? [] : [index]));
    const data: MemberInput = {
      lastName: lastName.trim(),
      firstName: firstName.trim(),
      middleName: optional(middleName),
      phones: phones.map((p) => p.value.trim()).filter((v) => v !== ''),
      email: optional(email),
      postalAddress: optional(postalAddress),
      deliveryChannel,
      telegramUsername: optional(telegramUsername),
      viberPhone: optional(viberPhone),
      ...(canViewNotes ? { notes: optional(notes) } : {}),
    };
    if (member) update.mutate({ id: member.id, data });
    else create.mutate({ data });
  }

  return (
    <form
      onSubmit={submit}
      noValidate
      className="flex flex-col gap-4 rounded-xl border border-line bg-surface p-5"
    >
      {bannerError && <Banner variant="error">{bannerError}</Banner>}

      <Field label={f.lastName} error={errors.lastName}>
        {({ invalid, ...control }) => (
          <TextInput
            {...control}
            invalid={invalid}
            value={lastName}
            maxLength={100}
            autoComplete="family-name"
            onChange={(e) => {
              setLastName(e.target.value);
              clearError('lastName');
            }}
          />
        )}
      </Field>
      <Field label={f.firstName} error={errors.firstName}>
        {({ invalid, ...control }) => (
          <TextInput
            {...control}
            invalid={invalid}
            value={firstName}
            maxLength={100}
            autoComplete="given-name"
            onChange={(e) => {
              setFirstName(e.target.value);
              clearError('firstName');
            }}
          />
        )}
      </Field>
      <Field label={f.middleName} error={errors.middleName}>
        {({ invalid, ...control }) => (
          <TextInput
            {...control}
            invalid={invalid}
            value={middleName}
            maxLength={100}
            onChange={(e) => {
              setMiddleName(e.target.value);
              clearError('middleName');
            }}
          />
        )}
      </Field>

      <fieldset className="flex flex-col gap-3">
        <legend className="mb-1 text-sm text-ink-muted">{f.phones}</legend>
        {phones.map((row, index) => (
          <div key={row.id} className="flex items-start gap-2">
            <div className="flex-1">
              <Field label={format(t.memberForm.phone, { n: index + 1 })} error={errors[`phones[${index}]`]}>
                {({ invalid, ...control }) => (
                  <TextInput
                    {...control}
                    invalid={invalid}
                    type="tel"
                    value={row.value}
                    maxLength={32}
                    onChange={(e) => {
                      const value = e.target.value;
                      setPhones((prev) => prev.map((p) => (p.id === row.id ? { ...p, value } : p)));
                      clearError(`phones[${index}]`);
                    }}
                  />
                )}
              </Field>
            </div>
            <button
              type="button"
              className={`${buttonClass} mt-6`}
              aria-label={format(t.memberForm.removePhone, { n: index + 1 })}
              onClick={() => {
                setPhones((prev) => prev.filter((p) => p.id !== row.id));
                clearPhoneErrors();
              }}
            >
              ×
            </button>
          </div>
        ))}
        {errors.phones && (
          <p role="alert" className="text-sm text-danger">
            {errors.phones}
          </p>
        )}
        <button
          type="button"
          className={`${buttonClass} self-start`}
          disabled={phones.length >= 10}
          onClick={() => setPhones((prev) => [...prev, { id: crypto.randomUUID(), value: '' }])}
        >
          {t.memberForm.addPhone}
        </button>
      </fieldset>

      <Field label={f.email} error={errors.email}>
        {({ invalid, ...control }) => (
          <TextInput
            {...control}
            invalid={invalid}
            type="email"
            value={email}
            maxLength={254}
            autoComplete="email"
            onChange={(e) => {
              setEmail(e.target.value);
              clearError('email');
            }}
          />
        )}
      </Field>
      <Field label={f.postalAddress} error={errors.postalAddress}>
        {({ invalid, ...control }) => (
          <TextArea
            {...control}
            invalid={invalid}
            rows={2}
            value={postalAddress}
            maxLength={500}
            onChange={(e) => {
              setPostalAddress(e.target.value);
              clearError('postalAddress');
            }}
          />
        )}
      </Field>
      <Field label={f.deliveryChannel} error={errors.deliveryChannel}>
        {({ invalid, ...control }) => (
          <SelectInput
            {...control}
            invalid={invalid}
            value={deliveryChannel}
            onChange={(e) => {
              setDeliveryChannel(e.target.value as MemberInput['deliveryChannel']);
              clearError('deliveryChannel', 'email', 'telegramUsername', 'viberPhone');
            }}
          >
            {CHANNELS.map((c) => (
              <option key={c} value={c}>
                {t.channel[c]}
              </option>
            ))}
          </SelectInput>
        )}
      </Field>
      <Field label={f.telegramUsername} error={errors.telegramUsername}>
        {({ invalid, ...control }) => (
          <TextInput
            {...control}
            invalid={invalid}
            value={telegramUsername}
            maxLength={64}
            onChange={(e) => {
              setTelegramUsername(e.target.value);
              clearError('telegramUsername');
            }}
          />
        )}
      </Field>
      <Field label={f.viberPhone} error={errors.viberPhone}>
        {({ invalid, ...control }) => (
          <TextInput
            {...control}
            invalid={invalid}
            type="tel"
            value={viberPhone}
            maxLength={32}
            onChange={(e) => {
              setViberPhone(e.target.value);
              clearError('viberPhone');
            }}
          />
        )}
      </Field>
      {canViewNotes && (
        <Field label={f.notes} error={errors.notes}>
          {({ invalid, ...control }) => (
            <TextArea
              {...control}
              invalid={invalid}
              rows={3}
              value={notes}
              maxLength={2000}
              onChange={(e) => {
                setNotes(e.target.value);
                clearError('notes');
              }}
            />
          )}
        </Field>
      )}

      <div className="flex gap-2">
        <button
          type="submit"
          disabled={isPending}
          className="rounded-md bg-accent px-4 py-1.5 text-sm font-semibold text-on-accent disabled:opacity-60"
        >
          {isPending ? t.memberForm.saving : t.memberForm.save}
        </button>
        <button type="button" className={buttonClass} onClick={onCancel}>
          {t.memberForm.cancel}
        </button>
      </div>
    </form>
  );
}
