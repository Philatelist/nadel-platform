import { format, formatDateTime } from '@nadel/shared';
import { UserContent } from '../UserContent';
import { useLocale, useT } from '../../i18n';

/** "Uploaded <time> by <member>": the member name is user content, the rest comes from the catalog. */
export function UploadedLine({ at, by }: { at: string; by?: string }) {
  const s = useT().reportTemplates;
  const { locale } = useLocale();
  const when = formatDateTime(at, locale);
  if (by === undefined) return <>{format(s.uploadedAt, { at: when })}</>;
  const [before = '', after = ''] = s.uploadedBy.split('{by}');
  return (
    <>
      {format(before, { at: when })}
      <UserContent>{by}</UserContent>
      {after}
    </>
  );
}
