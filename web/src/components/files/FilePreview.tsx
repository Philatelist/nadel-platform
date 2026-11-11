import { format } from '@nadel/shared';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { usePdfAction } from '../../hooks/usePdfAction';
import { useT } from '../../i18n';
import { UserContent } from '../UserContent';
import { Button } from '../ui';

/**
 * A file to preview: a stored one (`load` fetches it with the session) or a local one not yet saved. `key`
 * identifies the stored file across the app: it names the cached bytes, so it is stable and unique per file.
 */
export interface PreviewFile {
  key: string;
  fileName: string;
  contentType: string;
  local?: File;
  load?: () => Promise<Blob>;
}

export function isImage(file: PreviewFile): boolean {
  return file.contentType === 'image/jpeg' || file.contentType === 'image/png';
}

/** True once the element has come near the viewport; where IntersectionObserver is missing, at once. */
export function useNearViewport(ref: React.RefObject<Element | null>): boolean {
  const [near, setNear] = useState(typeof IntersectionObserver === 'undefined');
  useEffect(() => {
    const el = ref.current;
    if (near || !el) return;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) {
        setNear(true);
        observer.disconnect();
      }
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, [near, ref]);
  return near;
}

function blobQuery(file: PreviewFile) {
  return {
    queryKey: ['file-blob', file.key] as const,
    queryFn: () => (file.load ? file.load() : Promise.reject(new Error('no loader'))),
    staleTime: Infinity,
    gcTime: 60_000,
    retry: false,
    refetchOnWindowFocus: false,
  };
}

/**
 * The bytes of a file: the local File itself or the stored file, fetched once and cached under one key, so a
 * thumbnail and the viewer share it. `enabled` only says whether the caller wants it now.
 */
export function usePreviewBlob(
  file: PreviewFile,
  enabled: boolean,
): { blob: Blob | undefined; failed: boolean } {
  const query = useQuery({
    ...blobQuery(file),
    enabled: enabled && file.load !== undefined && file.local === undefined,
  });
  return { blob: file.local ?? query.data, failed: query.isError };
}

/** An object URL for a blob, revoked when the blob changes or the component unmounts. */
export function useObjectUrl(blob: Blob | undefined, type?: string): string | undefined {
  const [url, setUrl] = useState<string>();
  useEffect(() => {
    if (!blob) return;
    const created = URL.createObjectURL(type ? new Blob([blob], { type }) : blob);
    // The object URL is an external resource created here and revoked in the cleanup below.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setUrl(created);
    return () => {
      URL.revokeObjectURL(created);
      setUrl(undefined);
    };
  }, [blob, type]);
  return url;
}

/**
 * The viewer: a modal dialog (focus stays inside, Escape closes) with the image scaled to fit; left and right
 * arrows move between the images of the expense. Browsers apply the EXIF orientation to an img themselves.
 */
export function ImageViewer({
  images,
  index,
  onIndex,
  onClose,
}: {
  images: readonly PreviewFile[];
  index: number;
  onIndex: (index: number) => void;
  onClose: () => void;
}) {
  const t = useT();
  const v = t.expenses.attachments.viewer;
  const current = images[index];
  // The viewer asks for the file it shows itself, whether or not its thumbnail has scrolled into view.
  const { blob, failed } = usePreviewBlob(current ?? { key: '', fileName: '', contentType: '' }, true);
  const url = useObjectUrl(blob);
  const queryClient = useQueryClient();
  const download = usePdfAction();
  useEffect(() => {
    for (const neighbour of [images[index - 1], images[index + 1]]) {
      if (neighbour?.load && !neighbour.local) void queryClient.prefetchQuery(blobQuery(neighbour));
    }
  }, [images, index, queryClient]);
  const dialog = useRef<HTMLDialogElement | null>(null);
  const move = (delta: number) => {
    const next = index + delta;
    if (next >= 0 && next < images.length) onIndex(next);
  };
  useEffect(() => {
    const listener = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        onClose();
      } else if (e.key === 'ArrowLeft' && index > 0) {
        onIndex(index - 1);
      } else if (e.key === 'ArrowRight' && index < images.length - 1) {
        onIndex(index + 1);
      }
    };
    document.addEventListener('keydown', listener);
    return () => document.removeEventListener('keydown', listener);
  }, [index, images.length, onClose, onIndex]);
  if (!current) return null;
  return (
    <dialog
      ref={(el) => {
        dialog.current = el;
        if (el && !el.open) el.showModal();
      }}
      aria-modal="true"
      aria-label={format(v.label, { name: current.fileName })}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      className="fixed inset-0 m-0 grid h-dvh max-h-none w-dvw max-w-none grid-cols-1 grid-rows-[auto_minmax(0,1fr)] gap-3 overflow-hidden border-0 bg-black p-3 text-white backdrop:bg-black"
    >
      <div className="flex flex-wrap items-center gap-2">
        <span className="min-w-0 flex-1 break-all font-medium">
          <UserContent>{current.fileName}</UserContent>
        </span>
        {images.length > 1 && (
          <>
            <Button variant="secondary" disabled={index === 0} onClick={() => move(-1)}>
              {v.previous}
            </Button>
            <span aria-live="polite" className="text-sm text-ink-muted">
              {format(v.position, { n: index + 1, total: images.length })}
            </span>
            <Button variant="secondary" disabled={index === images.length - 1} onClick={() => move(1)}>
              {v.next}
            </Button>
          </>
        )}
        <Button variant="secondary" autoFocus onClick={onClose}>
          {v.close}
        </Button>
      </div>
      <div data-testid="viewer-stage" className="relative min-h-0 min-w-0">
        {url !== undefined ? (
          <img src={url} alt={current.fileName} className="absolute inset-0 h-full w-full object-contain" />
        ) : failed ? (
          <div role="alert" className="flex h-full flex-col items-center justify-center gap-3">
            <p>{v.failed}</p>
            <Button
              variant="secondary"
              disabled={download.pending}
              onClick={() => current.load && void download.run(current.load, current.fileName)}
            >
              {t.expenses.attachments.download}
            </Button>
          </div>
        ) : (
          <p role="status" className="flex h-full items-center justify-center">
            {v.loading}
          </p>
        )}
      </div>
    </dialog>
  );
}

/** The file-type mark on a PDF tile; a format name, not translated text. */
const pdfLabel = 'PDF';

const thumbClass =
  'flex h-28 w-40 items-center justify-center overflow-hidden rounded-control border border-line bg-accent-soft';

function Thumbnail({ file, onOpen }: { file: PreviewFile; onOpen: () => void }) {
  const t = useT();
  const holder = useRef<HTMLDivElement>(null);
  const near = useNearViewport(holder);
  const image = isImage(file);
  const { blob } = usePreviewBlob(file, near && image);
  const url = useObjectUrl(image ? blob : undefined);
  return (
    <div ref={holder}>
      <button
        type="button"
        onClick={onOpen}
        className={`${thumbClass} focus-visible:outline-2 focus-visible:outline-accent`}
        aria-label={image ? file.fileName : `${t.expenses.attachments.openPdf}: ${file.fileName}`}
      >
        {image ? (
          url !== undefined && (
            <img src={url} alt={file.fileName} className="max-h-full max-w-full object-contain" />
          )
        ) : (
          <span aria-hidden="true" className="text-3xl font-semibold text-ink-muted">
            {pdfLabel}
          </span>
        )}
      </button>
    </div>
  );
}

/** Opens a PDF in a new tab from a blob URL of type application/pdf; the URL is revoked a little later. */
export async function openPdf(file: PreviewFile): Promise<void> {
  const blob = file.local ?? (await file.load?.()) ?? new Blob();
  const url = URL.createObjectURL(new Blob([blob], { type: 'application/pdf' }));
  window.open(url, '_blank', 'noopener');
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

/**
 * A grid of previews: an image is a thumbnail that opens the viewer, a PDF a file tile that opens in a new tab.
 * `actions` renders buttons under each file (download, remove).
 */
export function FilePreviews({
  files,
  actions,
  onError,
}: {
  files: readonly PreviewFile[];
  actions: (file: PreviewFile, index: number) => ReactNode;
  onError?: (error: unknown) => void;
}) {
  const [viewing, setViewing] = useState<number>();
  const images = files.filter(isImage);
  return (
    <>
      <ul className="flex flex-wrap gap-4">
        {files.map((file, index) => (
          <li key={file.key} className="flex w-40 flex-col gap-1">
            <FileTile
              file={file}
              onOpenImage={() => setViewing(images.findIndex((i) => i.key === file.key))}
              onError={onError}
            />
            <span className="break-all text-sm">
              <UserContent>{file.fileName}</UserContent>
            </span>
            <div className="flex flex-wrap gap-2">{actions(file, index)}</div>
          </li>
        ))}
      </ul>
      {viewing !== undefined && viewing >= 0 && (
        <ImageViewer
          images={images}
          index={Math.min(viewing, images.length - 1)}
          onIndex={setViewing}
          onClose={() => setViewing(undefined)}
        />
      )}
    </>
  );
}

function FileTile({
  file,
  onOpenImage,
  onError,
}: {
  file: PreviewFile;
  onOpenImage: () => void;
  onError?: (error: unknown) => void;
}) {
  return (
    <Thumbnail
      file={file}
      onOpen={() => {
        if (isImage(file)) {
          onOpenImage();
        } else {
          openPdf(file).catch((error: unknown) => onError?.(error));
        }
      }}
    />
  );
}
