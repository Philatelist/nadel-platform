import { generateReportTemplate, responseHeadersOf } from '@nadel/api-client';
import { useMutation } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Modal } from '../Modal';
import { UserContent } from '../UserContent';
import { Button, Notice, TextField } from '../ui';
import { downloadBlob } from '../../downloadBlob';
import { useLocale, useT } from '../../i18n';
import { problemCode } from '../../problem';
import { generatedFileName, previousMonthPeriod, validatePeriod } from '../../reportTemplateGenerate';
import { reportTemplateErrorMessage } from '../../reportTemplateError';
import { parseGenerationWarnings, type GenerationWarnings } from '../../reportTemplateWarnings';
import { GenerationResult } from './GenerationResult';

/** Asks for a period, fills the latest version of the template and downloads the file. */
export function GenerateDialog({
  template,
  onClose,
}: {
  template: { id: string; name: string };
  onClose: () => void;
}) {
  const t = useT();
  const s = t.reportTemplates;
  const { locale } = useLocale();
  const [period, setPeriod] = useState(previousMonthPeriod);
  const [submitted, setSubmitted] = useState(false);
  const [result, setResult] = useState<GenerationWarnings | null>(null);

  const generate = useMutation({
    mutationFn: async (chosen: { from: string; to: string }) => {
      const blob = await generateReportTemplate(template.id, { ...chosen, lang: locale });
      return { blob, chosen };
    },
    onSuccess: ({ blob, chosen }) => {
      downloadBlob(blob, generatedFileName(template.name, chosen));
      setResult(parseGenerationWarnings(responseHeadersOf(blob)));
    },
  });

  const problem = validatePeriod(period);
  const serverOnTo = generate.isError && problemCode(generate.error) === 'REPORT_FILTER_INVALID';

  function edit(next: { from: string; to: string }) {
    setPeriod(next);
    setResult(null);
    generate.reset();
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (problem !== undefined) return;
    setResult(null);
    generate.mutate(period);
  }

  return (
    <Modal title={s.generate.title} onClose={onClose} size="lg">
      <form onSubmit={onSubmit} className="mt-3 flex flex-col gap-3">
        <p className="text-[15px]">
          {s.generate.template}: <UserContent>{template.name}</UserContent>
        </p>
        <div className="grid gap-3 sm:grid-cols-2">
          <TextField
            label={s.generate.from}
            type="date"
            value={period.from}
            onChange={(event) => edit({ ...period, from: event.target.value })}
            error={
              submitted && problem === 'dateRequired' && period.from === ''
                ? s.generate.dateRequired
                : undefined
            }
          />
          <TextField
            label={s.generate.to}
            type="date"
            value={period.to}
            onChange={(event) => edit({ ...period, to: event.target.value })}
            error={
              submitted && problem !== undefined
                ? s.generate[problem]
                : serverOnTo
                  ? reportTemplateErrorMessage(t, generate.error, 'generate')
                  : undefined
            }
          />
        </div>
        <p className="text-sm text-ink-muted">{s.generate.hint}</p>
        {generate.isError && !serverOnTo && (
          <Notice tone="danger">{reportTemplateErrorMessage(t, generate.error, 'generate')}</Notice>
        )}
        {result && <GenerationResult warnings={result} />}
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            {s.generate.close}
          </Button>
          <Button type="submit" disabled={generate.isPending}>
            {generate.isPending ? s.generate.generating : s.generate.submit}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
