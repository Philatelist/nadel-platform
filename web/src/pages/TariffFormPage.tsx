import {
  getListTariffsQueryKey,
  useCreateTariff,
  useListChargeTypes,
  type ChargeType,
} from '@nadel/api-client';
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
import { useT } from '../i18n';
import type { FieldErrors } from '../problem';
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
      <QueryBoundary query={query}>
        {(types) => <TariffForm types={types.filter((type) => type.active)} />}
      </QueryBoundary>
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
    types.some((x) => x.id === preselected) ? preselected : '',
  );
  const [name, setName] = useState('');
  const [version, setVersion] = useState<VersionValues>(emptyVersionValues);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string>();

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

  if (types.length === 0) return <Notice tone="warning">{f.noActiveTypes}</Notice>;
  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-4">
      <Panel>
        <Select
          label={f.chargeType}
          required
          value={chargeTypeId}
          error={errors.chargeTypeId}
          onChange={(e) => setChargeTypeId(e.target.value)}
        >
          <option value="" />
          {types.map((type) => (
            <option
              key={type.id}
              value={type.id}
              {...(isChargeTypeNameUserContent(type) ? { 'data-user-content': true } : {})}
            >
              {chargeTypeName(type, t)}
            </option>
          ))}
        </Select>
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
