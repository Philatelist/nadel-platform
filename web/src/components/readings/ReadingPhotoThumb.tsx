import { getReadingPhoto } from '@nadel/api-client';
import { format, formatDay } from '@nadel/shared';
import { useMemo, useRef, useState } from 'react';
import { useLocale, useT } from '../../i18n';
import {
  ImageViewer,
  useNearViewport,
  useObjectUrl,
  usePreviewBlob,
  type PreviewFile,
} from '../files/FilePreview';

/**
 * The photo a member attached to a reading: a 72 px thumbnail fetched once it nears the viewport (with the
 * session, no stored URL), which opens the viewer. There is no delete action: the photo stays with the reading.
 */
export function ReadingPhotoThumb({ readingId, takenOn }: { readingId: string; takenOn: string }) {
  const t = useT();
  const { locale } = useLocale();
  const [viewing, setViewing] = useState(false);
  const holder = useRef<HTMLDivElement>(null);
  const near = useNearViewport(holder);
  const file: PreviewFile = useMemo(
    () => ({
      key: `reading-photo-${readingId}`,
      fileName: format(t.readings.photo.fileName, { date: formatDay(takenOn, locale) }),
      contentType: 'image/jpeg',
      load: () => getReadingPhoto(readingId),
    }),
    [readingId, takenOn, t, locale],
  );
  const { blob } = usePreviewBlob(file, near);
  const url = useObjectUrl(blob);
  return (
    <div ref={holder}>
      <button
        type="button"
        onClick={() => setViewing(true)}
        aria-label={t.readings.photo.open}
        className="flex size-[72px] items-center justify-center overflow-hidden rounded-control border border-line bg-accent-soft focus-visible:outline-2 focus-visible:outline-accent"
      >
        {url !== undefined && (
          <img
            src={url}
            alt={t.readings.photo.thumbnailAlt}
            className="max-h-full max-w-full object-contain"
          />
        )}
      </button>
      {viewing && (
        <ImageViewer images={[file]} index={0} onIndex={() => undefined} onClose={() => setViewing(false)} />
      )}
    </div>
  );
}
