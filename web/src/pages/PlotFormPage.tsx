import { ApiError, useGetPlot, type PlotDetails } from '@nadel/api-client';
import { format } from '@nadel/shared';
import { Link, useNavigate, useParams } from 'react-router';
import { PlotForm } from '../components/PlotForm';
import { QueryBoundary } from '../components/QueryBoundary';
import { PageHeader } from '../components/ui';
import { useT } from '../i18n';
import { useDocumentTitle } from '../useDocumentTitle';
import { NotFoundPage } from './NotFoundPage';

/** `/plots/new` and `/plots/:id/edit`: save, then open the plot card. */
export function PlotFormPage() {
  const { id } = useParams();
  return id === undefined ? <PlotFormView /> : <EditPlot id={id} />;
}

function EditPlot({ id }: { id: string }) {
  const plot = useGetPlot(id);
  if (plot.error instanceof ApiError && plot.error.status === 404) return <NotFoundPage />;
  return <QueryBoundary query={plot}>{(data) => <PlotFormView key={data.id} plot={data} />}</QueryBoundary>;
}

function PlotFormView({ plot }: { plot?: PlotDetails }) {
  const t = useT();
  const navigate = useNavigate();
  const title = plot
    ? format(t.plot.editTitle, { kind: t.plot.kind[plot.kind], number: plot.number })
    : t.plot.addTitle;
  useDocumentTitle(title);
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <Link to="/plots" className="text-ink-muted hover:text-ink">
        {t.plot.back}
      </Link>
      <PageHeader title={title} />
      <PlotForm
        plot={plot}
        onSaved={(saved) => void navigate(`/plots/${saved.id}`)}
        onCancel={() => void navigate(-1)}
      />
    </div>
  );
}
