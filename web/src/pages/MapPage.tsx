import { useGetPlotMap, type PlotFeatureCollection, type PlotKind, type PlotStatus } from '@nadel/api-client';
import { formatMoney, formatSotky, isZeroMoney } from '@nadel/shared';
import { LngLatBounds, Map as MapLibreMap, NavigationControl, type GeoJSONSource } from 'maplibre-gl';
import { useEffect, useRef, useState } from 'react';
import { ErrorState } from '../components/ui';
import { useT } from '../i18n';
import { plotStatusLabel } from '../plotStatus';

const LEGEND: { status: PlotStatus; kind: PlotKind }[] = [
  { status: 'RED', kind: 'PLOT' },
  { status: 'YELLOW', kind: 'PLOT' },
  { status: 'GREEN', kind: 'PLOT' },
  { status: 'GREY', kind: 'PLOT' },
  { status: 'GREY', kind: 'COMMON' },
];

function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function statusColors(): Record<PlotStatus, string> {
  return {
    RED: cssVar('--color-status-red'),
    YELLOW: cssVar('--color-status-yellow'),
    GREEN: cssVar('--color-status-green'),
    GREY: cssVar('--color-status-grey'),
  };
}

function bounds(fc: PlotFeatureCollection): LngLatBounds | null {
  const b = new LngLatBounds();
  let any = false;
  for (const f of fc.features) {
    for (const ring of f.geometry.coordinates) {
      for (const point of ring) {
        const [lng, lat] = point;
        if (lng !== undefined && lat !== undefined) {
          b.extend([lng, lat]);
          any = true;
        }
      }
    }
  }
  return any ? b : null;
}

export function MapPage() {
  const t = useT();
  const { data, isLoading, isError, refetch } = useGetPlotMap();
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<MapLibreMap | null>(null);
  const [selected, setSelected] = useState<string | null>(null);

  const collection = data;

  // No basemap yet, only the plots. An OSM basemap arrives in phase 1.
  useEffect(() => {
    if (!container.current || map.current) return;
    const m = new MapLibreMap({
      container: container.current,
      style: {
        version: 8,
        sources: {},
        layers: [
          {
            id: 'background',
            type: 'background',
            paint: { 'background-color': cssVar('--color-background') },
          },
        ],
      },
      center: [30.7, 46.6],
      zoom: 15,
      attributionControl: false,
    });
    m.addControl(new NavigationControl({ showCompass: false }), 'top-right');
    map.current = m;
    return () => {
      m.remove();
      map.current = null;
    };
  }, []);

  useEffect(() => {
    const m = map.current;
    if (!m || !collection) return;

    const draw = () => {
      const colors = statusColors();
      const source = m.getSource('plots') as GeoJSONSource | undefined;
      // Contract types match GeoJSON; MapLibre expects its own type
      const geojson = collection as unknown as Parameters<GeoJSONSource['setData']>[0];
      if (source) {
        source.setData(geojson);
      } else {
        m.addSource('plots', { type: 'geojson', data: geojson, promoteId: 'id' });
        m.addLayer({
          id: 'plots-fill',
          type: 'fill',
          source: 'plots',
          paint: {
            'fill-color': [
              'match',
              ['get', 'status'],
              'RED',
              colors.RED,
              'YELLOW',
              colors.YELLOW,
              'GREEN',
              colors.GREEN,
              colors.GREY,
            ],
            'fill-opacity': 0.85,
          },
        });
        m.addLayer({
          id: 'plots-outline',
          type: 'line',
          source: 'plots',
          paint: { 'line-color': cssVar('--color-surface'), 'line-width': 1.5 },
        });
        m.on('click', 'plots-fill', (e) => {
          const id = e.features?.[0]?.id;
          setSelected(id === undefined ? null : String(id));
        });
        m.on('mouseenter', 'plots-fill', () => (m.getCanvas().style.cursor = 'pointer'));
        m.on('mouseleave', 'plots-fill', () => (m.getCanvas().style.cursor = ''));
      }
      const b = bounds(collection);
      if (b) m.fitBounds(b, { padding: 48, duration: 0 });
    };

    if (m.isStyleLoaded()) draw();
    else m.once('load', draw);
  }, [collection]);

  const plot = collection?.features.find((f) => f.id === selected)?.properties;

  return (
    <div className="relative h-full">
      <div ref={container} className="absolute inset-0" aria-label={t.map.title} />

      {isError && (
        <div className="absolute inset-x-4 top-4 rounded-xl border border-line bg-surface">
          <ErrorState message={t.errors.generic} onRetry={() => void refetch()} />
        </div>
      )}
      {!isLoading && collection && collection.features.length === 0 && (
        <div className="absolute inset-x-4 top-4 max-w-md rounded-xl border border-line bg-surface p-4 text-ink-muted">
          {t.map.empty}
        </div>
      )}

      <div className="absolute bottom-4 left-4 flex flex-wrap gap-3 rounded-xl border border-line bg-surface px-3 py-2 text-xs text-ink-muted">
        {LEGEND.map(({ status, kind }) => (
          <span key={`${status}-${kind}`} className="flex items-center gap-1.5">
            <i
              className="inline-block h-2.5 w-2.5 rounded-sm"
              style={{ background: `var(--color-status-${status.toLowerCase()})` }}
            />
            {plotStatusLabel(t, { status, kind })}
          </span>
        ))}
      </div>

      {plot && (
        <aside className="absolute right-4 top-4 w-64 rounded-xl border border-line bg-surface p-4">
          <div className="flex items-start justify-between gap-2">
            <h2 className="font-serif text-lg font-semibold">
              {plot.kind === 'COMMON' ? t.map.commonLand : `${t.map.plot} ${plot.number}`}
            </h2>
            <button type="button" onClick={() => setSelected(null)} className="text-ink-muted" aria-label="×">
              ×
            </button>
          </div>
          <dl className="mt-3 grid grid-cols-2 gap-y-1.5 text-sm">
            {plot.areaM2 !== undefined && (
              <>
                <dt className="text-ink-muted">{t.map.area}</dt>
                <dd className="tabular-nums">
                  {formatSotky(plot.areaM2)} {t.map.sotky}
                </dd>
              </>
            )}
            {plot.debt && !isZeroMoney(plot.debt) && (
              <>
                <dt className="text-ink-muted">{t.map.debt}</dt>
                <dd className="tabular-nums">{formatMoney(plot.debt)}</dd>
              </>
            )}
          </dl>
          <p className="mt-3 text-sm">{plotStatusLabel(t, plot)}</p>
        </aside>
      )}
    </div>
  );
}
