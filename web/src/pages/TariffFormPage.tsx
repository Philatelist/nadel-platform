import {
  getListChargeTypesQueryKey,
  getListTariffsQueryKey,
  useCreateTariff,
  useListChargeTypes,
  useListTariffs,
  useUpdateChargeType,
  type ChargeType,
} from '@nadel/api-client';
import { format } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { chargeTypeName } from '../chargeTypeName';
import { isChargeTypeNameUserContent } from '../components/ChargeTypeName';
import { QueryBoundary } from '../components/QueryBoundary';
import {
  emptyVersionValues,
  validateVersion,
  VersionFields,
  versionSaveErrors,
  type VersionValues,
} from '../components/tariffs/VersionFields';
import { Button, Notice, PageHeader, Panel, Select, TextField } from '../components/ui';
import { UserContent } from '../components/UserContent';
import { useT } from '../i18n';
import { problemMessage, type FieldErrors } from '../problem';
import { useDocumentTitle } from '../useDocumentTitle';

/** `/tariffs/new`: a tariff with its first version, then its card. */
export function TariffFormPage() {
  const t = useT();
  useDocumentTitle(t.tariffs.form.title);
  const query = useListChargeTypes();
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <Link to="/tariffs" className="text-ink-muted hover:text-ink">
        {t.tariffs.back}
      </Link>
      <PageHeader title={t.tariffs.form.title} />
      <QueryBoundary query={query}>{(types) => <TariffForm types={types} />}</QueryBoundary>
    </div>
  );
}

function TariffForm({ types }: { types: readonly ChargeType[] }) {
  const t = useT();
  const f = t.tariffs.form;
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [params] = useSearchParams();
  const preselected = params.get('chargeTypeId') ?? '';
  const [chargeTypeId, setChargeTypeId] = useState(
    types.some((x) => x.id === preselected && x.active) ? preselected : '',
  );
  const activeTypes = types.filter((type) => type.active);
  const [name, setName] = useState('');
  const [version, setVersion] = useState<VersionValues>(emptyVersionValues);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string>();

  const activate = useUpdateChargeType({
    mutation: {
      onSuccess: async (saved) => {
        await queryClient.invalidateQueries({ queryKey: getListChargeTypesQueryKey() });
        setChargeTypeId(saved.id);
      },
      onError: (error: unknown) => setFormError(problemMessage(t, error)),
    },
  });

  function chooseType(id: string) {
    setFormError(undefined);
    const picked = types.find((type) => type.id === id);
    if (picked && !picked.active) {
      // PUT replaces name, description and active: send the current values with active=true.
      activate.mutate({
        id: picked.id,
        data: { name: picked.name, description: picked.description, active: true },
      });
      return;
    }
    setChargeTypeId(id);
  }

  const create = useCreateTariff({
    mutation: {
      onSuccess: async (saved) => {
        await queryClient.invalidateQueries({ queryKey: getListTariffsQueryKey() });
        void navigate(`/tariffs/${saved.id}`);
      },
      onError: (error: unknown) => {
        const { fields, form } = versionSaveErrors(t, error);
        setErrors(fields);
        setFormError(form);
      },
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setFormError(undefined);
    const checked = validateVersion(version, t);
    const trimmed = name.trim();
    const found: Record<string, string> = { ...checked.errors };
    if (trimmed === '') found.name = t.validation.REQUIRED_name;
    if (chargeTypeId === '') found.chargeTypeId = t.validation.REQUIRED;
    setErrors(found);
    if (checked.data === undefined || Object.keys(found).length > 0) return;
    create.mutate({ data: { chargeTypeId, name: trimmed, firstVersion: checked.data } });
  }

  if (activeTypes.length === 0 && types.length === 0)
    return <Notice tone="warning">{f.noActiveTypes}</Notice>;
  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-4">
      <Panel>
        <Select
          label={f.chargeType}
          required
          value={chargeTypeId}
          error={errors.chargeTypeId}
          disabled={activate.isPending}
          onChange={(e) => chooseType(e.target.value)}
        >
          <option value="" />
          {types.map((type) => (
            <option
              key={type.id}
              value={type.id}
              {...(isChargeTypeNameUserContent(type) ? { 'data-user-content': '' } : {})}
            >
              {type.active
                ? chargeTypeName(type, t)
                : format(f.inactiveOption, { name: chargeTypeName(type, t) })}
            </option>
          ))}
        </Select>
        {activate.isPending && <p className="text-sm text-ink-muted">{f.activating}</p>}
        <p className="text-sm text-ink-muted">
          {f.missingType}{' '}
          <Link to="/charge-types" className="text-accent underline">
            {f.chargeTypesLink}
          </Link>
        </p>
        {chargeTypeId !== '' && <ActiveTariffsHint chargeTypeId={chargeTypeId} />}
        <TextField
          label={f.name}
          required
          maxLength={100}
          value={name}
          error={errors.name}
          onChange={(e) => setName(e.target.value)}
        />
      </Panel>
      <Panel title={f.firstVersion}>
        <VersionFields values={version} onChange={setVersion} errors={errors} />
      </Panel>
      {formError && <Notice tone="danger">{formError}</Notice>}
      <div className="flex gap-2">
        <Button type="submit" disabled={create.isPending}>
          {create.isPending ? f.saving : f.create}
        </Button>
        <Button variant="secondary" onClick={() => void navigate(-1)}>
          {f.cancel}
        </Button>
      </div>
    </form>
  );
}

const MAX_PLOTS_SHOWN = 8;

/** The active tariffs of the chosen charge type with the plots they cover, so a duplicate is easy to spot. */
function ActiveTariffsHint({ chargeTypeId }: { chargeTypeId: string }) {
  const t = useT();
  const f = t.tariffs.form;
  const query = useListTariffs({ status: 'ACTIVE', chargeTypeId });
  const tariffs = query.data ?? [];
  return (
    <div className="flex flex-col gap-1 text-sm" aria-label={f.activeTariffs}>
      <span className="font-medium">{f.activeTariffs}</span>
      {tariffs.length === 0 ? (
        <span className="text-ink-muted">{f.noActiveTariffs}</span>
      ) : (
        <ul className="flex flex-col gap-1">
          {tariffs.map((tariff) => {
            const shown = tariff.plotNumbers.slice(0, MAX_PLOTS_SHOWN);
            const more = tariff.plotNumbers.length - shown.length;
            return (
              <li key={tariff.id}>
                <UserContent>{tariff.name}</UserContent>
                <span className="text-ink-muted">
                  {' — '}
                  {tariff.plotNumbers.length === 0
                    ? f.noPlots
                    : format(f.plotsOf, {
                        plots:
                          shown.join(', ') + (more > 0 ? `, ${format(f.morePlots, { count: more })}` : ''),
                      })}
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
