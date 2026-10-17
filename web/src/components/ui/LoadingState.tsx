export function LoadingState({ message }: { message: string }) {
  return (
    <div role="status" className="p-6 text-ink-muted">
      {message}
    </div>
  );
}
