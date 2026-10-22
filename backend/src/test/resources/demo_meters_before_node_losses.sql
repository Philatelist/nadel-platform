-- BEGIN demo meters
-- Meters, the electricity tree and readings (spec 010), demo cooperative plus one meter of the other cooperative.
-- Counts (demo cooperative): 38 meters = 5 network meters (substation, 2 quarters, well, street lighting) + 29 electricity
-- plot meters (28 plots; plots 25 and 28 have none; plot 9 has the replaced meter and its successor) + 4 water meters;
-- 33 tree periods (water meters are outside the tree); 168 readings = 165 approved + 1 pending + 1 rejected + 1 superseded
-- (asserted by DemoMeterDataTests). The other cooperative: 1 meter, 1 reading.
-- Dates are relative to CURRENT_DATE (one anchor per statement), so the data looks fresh whenever it is first loaded:
--  - plot meters are 150 days old, the network meters 400 days; readings are spread over ~120 days with irregular gaps
--    (6-55 days), a range that starts or ends between two readings is an interpolation case;
--  - plot 9: the meter was replaced 30 days ago (old meter removed with a final value, the new one starts at 0);
--  - plot 20: the last reading is 75 days old (the "no reading for 60+ days" case);
--  - plot 7 (member@demo.local): the one PENDING member reading; plot 1: a REJECTED reading; plot 2: a
--    SUPERSEDED reading replaced by its correction.
-- Plot meters of Sadova street (plots 1-14, 12a) hang under "Квартал А", those of Vyshneva street (15-29) under "Квартал Б".
-- Every insert is DO NOTHING on fixed or derived ids, so re-running never duplicates rows or overwrites edits. Tests that
-- need a clean state remove the meters and put them back by running this block (see DemoMeterData).

INSERT INTO meter (id, tenant_id, kind, resource, serial, name, plot_id, installed_on, initial_value, note) VALUES
    ('99999999-0000-0000-0001-000000000001', '11111111-1111-1111-1111-111111111111', 'SUBSTATION', 'ELECTRICITY', 'ПС-001', 'Підстанція №1', NULL, CURRENT_DATE - 400, 50000.000, 'Головний облік кооперативу'),
    ('99999999-0000-0000-0001-000000000002', '11111111-1111-1111-1111-111111111111', 'QUARTER', 'ELECTRICITY', 'КВ-А', 'Квартал А', NULL, CURRENT_DATE - 400, 20000.000, NULL),
    ('99999999-0000-0000-0001-000000000003', '11111111-1111-1111-1111-111111111111', 'QUARTER', 'ELECTRICITY', 'КВ-Б', 'Квартал Б', NULL, CURRENT_DATE - 400, 18000.000, NULL),
    ('99999999-0000-0000-0001-000000000004', '11111111-1111-1111-1111-111111111111', 'COMMON_CONSUMER', 'ELECTRICITY', 'СВ-001', 'Свердловина №1', NULL, CURRENT_DATE - 400, 3000.000, 'Насос водопостачання'),
    ('99999999-0000-0000-0001-000000000005', '11111111-1111-1111-1111-111111111111', 'COMMON_CONSUMER', 'ELECTRICITY', 'ОС-001', 'Освітлення вул. Вишнева', NULL, CURRENT_DATE - 400, 900.000, NULL),
    ('99999999-0000-0000-0001-0000000000b1', '22222222-2222-2222-2222-222222222222', 'SUBSTATION', 'ELECTRICITY', 'ПС-Л1', 'Підстанція кооперативу «Світанок»', NULL, CURRENT_DATE - 100, 500.000, NULL)
ON CONFLICT DO NOTHING;

-- Electricity meters of the plots, except plot 9 (replaced) and the plots 25 and 28 that have none. The initial value and
-- the working period differ per plot; the number part of the plot number (12a counts as 12) drives them.
INSERT INTO meter (id, tenant_id, kind, resource, serial, plot_id, installed_on, initial_value)
SELECT md5('demo-meter:' || p.tenant_id || ':' || p.number || ':ELECTRICITY')::uuid, p.tenant_id, 'PLOT', 'ELECTRICITY',
       'ЕЛ-' || p.number, p.id, CURRENT_DATE - 150, 1000 + 137 * regexp_replace(p.number, '\D', '', 'g')::int
FROM plot p
WHERE p.tenant_id = '11111111-1111-1111-1111-111111111111' AND p.kind = 'PLOT' AND p.archived_at IS NULL
  AND p.number NOT IN ('9', '25', '28')
ON CONFLICT DO NOTHING;

-- Plot 9: the old meter was removed 30 days ago with a final value, the new one replaces it.
INSERT INTO meter (id, tenant_id, kind, resource, serial, plot_id, installed_on, initial_value, removed_on, final_value, note)
SELECT md5('demo-meter:' || p.tenant_id || ':9:OLD')::uuid, p.tenant_id, 'PLOT', 'ELECTRICITY', 'ЕЛ-9', p.id,
       CURRENT_DATE - 150, 2233.000, CURRENT_DATE - 30, 3340.000, 'Замінено через поломку лічильника'
FROM plot p
WHERE p.tenant_id = '11111111-1111-1111-1111-111111111111' AND p.number = '9' AND p.archived_at IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO meter (id, tenant_id, kind, resource, serial, plot_id, installed_on, initial_value, replaces_meter_id)
SELECT md5('demo-meter:' || p.tenant_id || ':9:NEW')::uuid, p.tenant_id, 'PLOT', 'ELECTRICITY', 'ЕЛ-9/2', p.id,
       CURRENT_DATE - 30, 0.000, md5('demo-meter:' || p.tenant_id || ':9:OLD')::uuid
FROM plot p
WHERE p.tenant_id = '11111111-1111-1111-1111-111111111111' AND p.number = '9' AND p.archived_at IS NULL
  AND EXISTS (SELECT 1 FROM meter o WHERE o.id = md5('demo-meter:' || p.tenant_id || ':9:OLD')::uuid)
ON CONFLICT DO NOTHING;

-- Water meters on four plots.
INSERT INTO meter (id, tenant_id, kind, resource, serial, plot_id, installed_on, initial_value)
SELECT md5('demo-meter:' || p.tenant_id || ':' || p.number || ':WATER')::uuid, p.tenant_id, 'PLOT', 'WATER',
       'ВД-' || p.number, p.id, CURRENT_DATE - 150, 40 + regexp_replace(p.number, '\D', '', 'g')::int
FROM plot p
WHERE p.tenant_id = '11111111-1111-1111-1111-111111111111' AND p.archived_at IS NULL
  AND p.number IN ('1', '7', '15', '22')
ON CONFLICT DO NOTHING;

-- The tree. Network meters hang under the substation or a quarter from their installation; plot meters under their quarter.
INSERT INTO meter_parent (id, tenant_id, meter_id, parent_meter_id, valid_from) VALUES
    ('99999999-0000-0000-0002-000000000002', '11111111-1111-1111-1111-111111111111', '99999999-0000-0000-0001-000000000002', '99999999-0000-0000-0001-000000000001', CURRENT_DATE - 400),
    ('99999999-0000-0000-0002-000000000003', '11111111-1111-1111-1111-111111111111', '99999999-0000-0000-0001-000000000003', '99999999-0000-0000-0001-000000000001', CURRENT_DATE - 400),
    ('99999999-0000-0000-0002-000000000004', '11111111-1111-1111-1111-111111111111', '99999999-0000-0000-0001-000000000004', '99999999-0000-0000-0001-000000000001', CURRENT_DATE - 400),
    ('99999999-0000-0000-0002-000000000005', '11111111-1111-1111-1111-111111111111', '99999999-0000-0000-0001-000000000005', '99999999-0000-0000-0001-000000000002', CURRENT_DATE - 400)
ON CONFLICT DO NOTHING;

INSERT INTO meter_parent (id, tenant_id, meter_id, parent_meter_id, valid_from, valid_to)
SELECT md5('demo-parent:' || m.id)::uuid, m.tenant_id, m.id,
       CASE WHEN p.street = 'вул. Садова' THEN '99999999-0000-0000-0001-000000000002'::uuid
            ELSE '99999999-0000-0000-0001-000000000003'::uuid END,
       m.installed_on, m.removed_on
FROM meter m
JOIN plot p ON p.tenant_id = m.tenant_id AND p.id = m.plot_id
WHERE m.tenant_id = '11111111-1111-1111-1111-111111111111' AND m.kind = 'PLOT' AND m.resource = 'ELECTRICITY'
ON CONFLICT DO NOTHING;

-- Approved readings. Each series is an initial value plus a daily rate over the days since installation, so values only
-- grow; the template (0-3, or 4 for plot 20) picks the reading days (days before today): gaps of 6-55 days.
-- Template 4 (plot 20) ends 75 days ago.
INSERT INTO meter_reading (id, tenant_id, meter_id, taken_on, value, source, status, entered_by_member_id, decided_by_member_id, decided_at)
SELECT md5('demo-reading:' || s.id || ':' || t.d)::uuid, s.tenant_id, s.id, CURRENT_DATE - t.d,
       s.initial_value + round(s.rate * ((CURRENT_DATE - t.d) - s.installed_on) + ((s.n * 7 + t.d) % 10) / 10.0, 3),
       'ENTERED', 'APPROVED', 'aaaaaaaa-0000-0000-0000-000000000004', 'aaaaaaaa-0000-0000-0000-000000000004', now()
FROM (
    SELECT m.id, m.tenant_id, m.installed_on, m.initial_value,
           regexp_replace(p.number, '\D', '', 'g')::int AS n,
           (4 + regexp_replace(p.number, '\D', '', 'g')::int % 7)::numeric AS rate,
           CASE WHEN p.number = '20' THEN 4 ELSE regexp_replace(p.number, '\D', '', 'g')::int % 4 END AS tpl
    FROM meter m JOIN plot p ON p.tenant_id = m.tenant_id AND p.id = m.plot_id
    WHERE m.tenant_id = '11111111-1111-1111-1111-111111111111' AND m.resource = 'ELECTRICITY' AND m.removed_on IS NULL
      AND m.replaces_meter_id IS NULL
    UNION ALL
    SELECT m.id, m.tenant_id, m.installed_on, m.initial_value, v.n, v.rate, 0
    FROM meter m
    JOIN (VALUES ('99999999-0000-0000-0001-000000000001'::uuid, 1, 260::numeric),
                 ('99999999-0000-0000-0001-000000000002'::uuid, 2, 120::numeric),
                 ('99999999-0000-0000-0001-000000000003'::uuid, 3, 100::numeric),
                 ('99999999-0000-0000-0001-000000000004'::uuid, 4, 25::numeric),
                 ('99999999-0000-0000-0001-000000000005'::uuid, 5, 8::numeric)) AS v(id, n, rate) ON v.id = m.id
) s
JOIN (VALUES (0, 112), (0, 85), (0, 63), (0, 30), (0, 9),
             (1, 118), (1, 90), (1, 41), (1, 19), (1, 6),
             (2, 100), (2, 58), (2, 27), (2, 12),
             (3, 105), (3, 74), (3, 20), (3, 5),
             (4, 118), (4, 96), (4, 75)) AS t(tpl, d) ON t.tpl = s.tpl
ON CONFLICT DO NOTHING;

-- Water: fractional cubic metres; odd plot numbers are read on 4 days, even ones on 3.
INSERT INTO meter_reading (id, tenant_id, meter_id, taken_on, value, source, status, entered_by_member_id, decided_by_member_id, decided_at)
SELECT md5('demo-reading:' || m.id || ':' || t.d)::uuid, m.tenant_id, m.id, CURRENT_DATE - t.d,
       m.initial_value + round((0.25 + (regexp_replace(p.number, '\D', '', 'g')::int % 5) * 0.037)
                               * ((CURRENT_DATE - t.d) - m.installed_on), 3),
       'ENTERED', 'APPROVED', 'aaaaaaaa-0000-0000-0000-000000000004', 'aaaaaaaa-0000-0000-0000-000000000004', now()
FROM meter m
JOIN plot p ON p.tenant_id = m.tenant_id AND p.id = m.plot_id
JOIN (VALUES (1, 105), (1, 74), (1, 20), (1, 5), (0, 112), (0, 63), (0, 9)) AS t(tpl, d)
     ON t.tpl = regexp_replace(p.number, '\D', '', 'g')::int % 2
WHERE m.tenant_id = '11111111-1111-1111-1111-111111111111' AND m.resource = 'WATER'
ON CONFLICT DO NOTHING;

-- Plot 9: readings of the old meter (the final value is its last point) and of the new one.
INSERT INTO meter_reading (id, tenant_id, meter_id, taken_on, value, source, status, entered_by_member_id, decided_by_member_id, decided_at)
SELECT md5('demo-reading:' || m.id || ':' || v.d)::uuid, m.tenant_id, m.id, CURRENT_DATE - v.d,
       v.value, 'ENTERED', 'APPROVED', 'aaaaaaaa-0000-0000-0000-000000000004', 'aaaaaaaa-0000-0000-0000-000000000004', now()
FROM (VALUES ('OLD', 118, 2233.000 + 0), ('OLD', 84, 2640.500), ('OLD', 52, 2987.250), ('OLD', 33, 3250.000),
             ('NEW', 18, 112.400), ('NEW', 4, 205.700)) AS v(which, d, value)
JOIN meter m ON m.id = md5('demo-meter:11111111-1111-1111-1111-111111111111:9:' || v.which)::uuid
ON CONFLICT DO NOTHING;

-- The other cooperative.
INSERT INTO meter_reading (id, tenant_id, meter_id, taken_on, value, source, status, entered_by_member_id, decided_by_member_id, decided_at)
VALUES ('99999999-0000-0000-0003-0000000000b1', '22222222-2222-2222-2222-222222222222',
        '99999999-0000-0000-0001-0000000000b1', CURRENT_DATE - 40, 560.000, 'ENTERED', 'APPROVED',
        'bbbbbbbb-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000001', now())
ON CONFLICT DO NOTHING;

-- The pending member reading (the approvals screen): plot 7 belongs to member@demo.local. An earlier version of this
-- script also loaded one on plot 15; remove that one by its fixed derived id so existing databases converge (only a PENDING row).
DELETE FROM meter_reading
WHERE status = 'PENDING'
  AND id = md5('demo-reading-pending:' || md5('demo-meter:11111111-1111-1111-1111-111111111111:15:ELECTRICITY')::uuid)::uuid;

INSERT INTO meter_reading (id, tenant_id, meter_id, taken_on, value, source, status, entered_by_member_id)
SELECT md5('demo-reading-pending:' || m.id)::uuid, m.tenant_id, m.id, CURRENT_DATE - 2,
       coalesce((SELECT max(r.value) FROM meter_reading r WHERE r.meter_id = m.id AND r.status = 'APPROVED'),
                m.initial_value) + 14.500,
       'MEMBER', 'PENDING', 'aaaaaaaa-0000-0000-0000-000000000003'
FROM meter m JOIN plot p ON p.tenant_id = m.tenant_id AND p.id = m.plot_id
WHERE m.tenant_id = '11111111-1111-1111-1111-111111111111' AND m.resource = 'ELECTRICITY' AND m.removed_on IS NULL
  AND p.number = '7'
ON CONFLICT DO NOTHING;

-- A rejected member reading on plot 1 (owner: member 9).
INSERT INTO meter_reading (id, tenant_id, meter_id, taken_on, value, source, status, entered_by_member_id, reject_reason, decided_by_member_id, decided_at)
SELECT md5('demo-reading-rejected:' || m.id)::uuid, m.tenant_id, m.id, CURRENT_DATE - 14,
       coalesce((SELECT max(r.value) FROM meter_reading r WHERE r.meter_id = m.id AND r.status = 'APPROVED'),
                m.initial_value) + 4000.000,
       'MEMBER', 'REJECTED', 'aaaaaaaa-0000-0000-0000-000000000009', 'Показ не збігається з фото лічильника',
       'aaaaaaaa-0000-0000-0000-000000000001', now()
FROM meter m JOIN plot p ON p.tenant_id = m.tenant_id AND p.id = m.plot_id
WHERE m.tenant_id = '11111111-1111-1111-1111-111111111111' AND m.resource = 'ELECTRICITY' AND p.number = '1'
ON CONFLICT DO NOTHING;

-- A corrected reading on plot 2: the first entry (18 less) is SUPERSEDED by the approved one 58 days ago.
INSERT INTO meter_reading (id, tenant_id, meter_id, taken_on, value, source, status, entered_by_member_id, superseded_by_id)
SELECT md5('demo-reading-superseded:' || r.id)::uuid, r.tenant_id, r.meter_id, r.taken_on, r.value - 18.000,
       'ENTERED', 'SUPERSEDED', 'aaaaaaaa-0000-0000-0000-000000000004', r.id
FROM meter_reading r
JOIN meter m ON m.id = r.meter_id AND m.tenant_id = r.tenant_id
JOIN plot p ON p.tenant_id = m.tenant_id AND p.id = m.plot_id
WHERE p.tenant_id = '11111111-1111-1111-1111-111111111111' AND p.number = '2' AND m.resource = 'ELECTRICITY'
  AND r.status = 'APPROVED' AND r.taken_on = CURRENT_DATE - 58
ON CONFLICT DO NOTHING;

-- The custom role Електрик reads the meters and enters readings. Legacy text: the earlier descriptions become the new one
-- unless the chairman changed it.
INSERT INTO role_permission (tenant_id, role_id, permission)
SELECT '11111111-1111-1111-1111-111111111111', 'cccccccc-0000-0000-0000-000000000001', p
FROM unnest(ARRAY['METERS_VIEW', 'READINGS_ENTER']) AS p
ON CONFLICT DO NOTHING;

UPDATE role SET description = 'Вносить покази лічильників на обході'
WHERE id = 'cccccccc-0000-0000-0000-000000000001'
  AND description IN ('Роль без дозволів до появи лічильників', 'Custom role without permissions');
