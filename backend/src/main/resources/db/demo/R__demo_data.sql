-- Demo data for the dev profile only. Idempotent: re-running does not duplicate rows.
-- Demo cooperative: 12 plots along a street plus a road (common land). Other cooperative: 2 plots.
-- Coordinates are arbitrary.

INSERT INTO tenant (id, name) VALUES
    ('11111111-1111-1111-1111-111111111111', 'Demo Garden Cooperative'),
    ('22222222-2222-2222-2222-222222222222', 'Other Cooperative')
ON CONFLICT (id) DO NOTHING;

-- Plots 25 x 40 m (~0.00033° longitude, ~0.00036° latitude), two rows of 6 across the road
INSERT INTO plot (tenant_id, number, street, kind, area_m2, boundary)
SELECT '11111111-1111-1111-1111-111111111111',
       (r * 6 + c + 1)::text,
       'Garden St',
       'PLOT',
       1000.00,
       ST_MakeEnvelope(30.7000 + c * 0.00033,
                       46.6000 + r * 0.00044,
                       30.7000 + (c + 1) * 0.00033 - 0.000015,
                       46.6000 + r * 0.00044 + 0.00036,
                       4326)
FROM generate_series(0, 1) AS r, generate_series(0, 5) AS c
ON CONFLICT (tenant_id, number) DO NOTHING;

INSERT INTO plot (tenant_id, number, street, kind, area_m2, boundary)
VALUES ('11111111-1111-1111-1111-111111111111', 'R-1', 'Garden St', 'COMMON', 1584.00,
        ST_MakeEnvelope(30.7000, 46.60036, 30.70198, 46.60044, 4326))
ON CONFLICT (tenant_id, number) DO NOTHING;

INSERT INTO plot_status (plot_id, tenant_id, status, debt, oldest_debt_date)
SELECT p.id, p.tenant_id, s.status, s.debt::numeric(14,2), s.since
FROM plot p
JOIN (VALUES
    ('1', 'GREEN', 0, NULL::date),     ('2', 'GREEN', 0, NULL),     ('3', 'YELLOW', 640, current_date - 25),
    ('4', 'GREEN', 0, NULL),           ('5', 'RED', 4820, current_date - 140), ('6', 'GREEN', 0, NULL),
    ('7', 'YELLOW', 1180, current_date - 40), ('8', 'GREEN', 0, NULL), ('9', 'GREEN', 0, NULL),
    ('10', 'RED', 3360, current_date - 95),   ('11', 'GREEN', 0, NULL), ('12', 'YELLOW', 300, current_date - 12)
) AS s(number, status, debt, since) ON s.number = p.number
WHERE p.tenant_id = '11111111-1111-1111-1111-111111111111'
ON CONFLICT (plot_id) DO NOTHING;

INSERT INTO plot (tenant_id, number, street, kind, area_m2, boundary)
SELECT '22222222-2222-2222-2222-222222222222', n::text, 'Forest St', 'PLOT', 600.00,
       ST_MakeEnvelope(30.8000 + n * 0.0003, 46.7000, 30.8000 + n * 0.0003 + 0.00028, 46.7003, 4326)
FROM generate_series(1, 2) AS n
ON CONFLICT (tenant_id, number) DO NOTHING;
