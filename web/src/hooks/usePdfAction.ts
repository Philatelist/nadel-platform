import { useState } from 'react';
import { downloadBlob } from '../downloadBlob';

/** A fetched PDF with the name it is saved under, for a file name known only once the fetch has answered. */
export interface NamedPdf {
  blob: Blob;
  fileName: string;
}

/**
 * A button action that fetches a PDF as a Blob (through a generated client function) and saves it as a file.
 * `run` takes the file name up front; `runNamed` lets the fetch name the file (for example after it has issued the
 * document). `error` holds the failure of the last run, for `problemMessage`.
 */
export function usePdfAction(): {
  run: (fetchPdf: () => Promise<Blob>, fileName: string) => Promise<void>;
  runNamed: (fetchPdf: () => Promise<NamedPdf>) => Promise<void>;
  pending: boolean;
  error: unknown;
} {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(undefined);
  const runNamed = async (fetchPdf: () => Promise<NamedPdf>): Promise<void> => {
    setPending(true);
    setError(undefined);
    try {
      const { blob, fileName } = await fetchPdf();
      downloadBlob(blob, fileName);
    } catch (caught: unknown) {
      setError(caught);
    } finally {
      setPending(false);
    }
  };
  const run = (fetchPdf: () => Promise<Blob>, fileName: string): Promise<void> =>
    runNamed(async () => ({ blob: await fetchPdf(), fileName }));
  return { run, runNamed, pending, error };
}
