-- Demo data for the dev profile only. Idempotent: re-running does not duplicate rows.
-- The text is Ukrainian, as a real cooperative would have it. Earlier versions of this script seeded English text;
-- rows of the tables below that this script owns (plots, members, ownership) are overwritten on every run
-- (ON CONFLICT DO UPDATE), so they turn Ukrainian by themselves. The rows a chairman can edit (cooperative name, custom
-- roles, charge types, tariffs, tariff versions, exclusions) stay DO NOTHING; for them a "legacy English" UPDATE sits
-- right after the insert and matches the exact old English value, so only text that was never edited is translated
-- and a chairman's own change is never overwritten.
-- Demo cooperative: 30 plots on two streets (a row each, on either side of a road) plus the road as common land.
-- Other cooperative: 2 plots. Coordinates are arbitrary.

INSERT INTO tenant (id, name) VALUES
    ('11111111-1111-1111-1111-111111111111', 'СТ «Надія»'),
    ('22222222-2222-2222-2222-222222222222', 'СТ «Світанок»')
ON CONFLICT (id) DO NOTHING;

-- Legacy English names of the earlier demo data become Ukrainian; an edited name does not match and is kept.
UPDATE tenant t SET name = v.new_name
FROM (VALUES
    ('11111111-1111-1111-1111-111111111111'::uuid, 'Demo Garden Cooperative', 'СТ «Надія»'),
    ('22222222-2222-2222-2222-222222222222'::uuid, 'Other Cooperative', 'СТ «Світанок»')
) AS v(id, old_name, new_name)
WHERE t.id = v.id AND t.name = v.old_name;

-- Plots 25 x 40 m (~0.00033° longitude, ~0.00036° latitude): 15 cells per street along the road.
-- "вул. Садова" is the southern row (1-14 and "12а"), "вул. Вишнева" the northern row (15-29). Areas are varied and independent
-- of the drawn polygon. About every third plot has a building; two plots carry a note; some have cadastral numbers.
-- "12а" ends in a Cyrillic letter on purpose (natural order and case-insensitive uniqueness).
INSERT INTO plot (tenant_id, number, street, kind, area_m2, cadastral_number, has_building, notes, boundary)
SELECT '11111111-1111-1111-1111-111111111111',
       v.number,
       v.street,
       'PLOT',
       v.area_m2,
       v.cadastral_number,
       v.has_building,
       v.notes,
       ST_MakeEnvelope(30.7000 + v.col * 0.00033,
                       46.6000 + v.row * 0.00044,
                       30.7000 + (v.col + 1) * 0.00033 - 0.000015,
                       46.6000 + v.row * 0.00044 + 0.00036,
                       4326)
FROM (VALUES
    ('1',   'вул. Садова',  0,  0,  600.00, '4810136300:02:001:0001', false, NULL),
    ('2',   'вул. Садова',  1,  0,  600.00, NULL,                     false, NULL),
    ('3',   'вул. Садова',  2,  0,  800.00, NULL,                     true,  NULL),
    ('4',   'вул. Садова',  3,  0, 1000.00, '4810136300:02:001:0004', false, NULL),
    ('5',   'вул. Садова',  4,  0, 1000.00, NULL,                     false, NULL),
    ('6',   'вул. Садова',  5,  0, 1200.00, NULL,                     true,  NULL),
    ('7',   'вул. Садова',  6,  0,  800.00, '4810136300:02:001:0007', false, NULL),
    ('8',   'вул. Садова',  7,  0, 1000.00, NULL,                     false, NULL),
    ('9',   'вул. Садова',  8,  0, 1500.00, NULL,                     true,  'Кутова ділянка біля в''їзних воріт'),
    ('10',  'вул. Садова',  9,  0, 1000.00, NULL,                     false, NULL),
    ('11',  'вул. Садова', 10,  0,  600.00, '4810136300:02:001:0011', false, NULL),
    ('12',  'вул. Садова', 11,  0, 1000.00, NULL,                     true,  NULL),
    ('12а', 'вул. Садова', 12,  0,  400.00, NULL,                     false, 'Виділено з ділянки 12'),
    ('13',  'вул. Садова', 13,  0,  800.00, NULL,                     false, NULL),
    ('14',  'вул. Садова', 14,  0, 1000.00, '4810136300:02:001:0014', false, NULL),
    ('15',  'вул. Вишнева', 0,  1,  600.00, NULL,                     true,  NULL),
    ('16',  'вул. Вишнева', 1,  1,  800.00, NULL,                     false, NULL),
    ('17',  'вул. Вишнева', 2,  1, 1000.00, '4810136300:02:002:0017', false, NULL),
    ('18',  'вул. Вишнева', 3,  1, 1000.00, NULL,                     true,  NULL),
    ('19',  'вул. Вишнева', 4,  1, 1200.00, NULL,                     false, NULL),
    ('20',  'вул. Вишнева', 5,  1,  600.00, NULL,                     false, NULL),
    ('21',  'вул. Вишнева', 6,  1, 1000.00, '4810136300:02:002:0021', true,  NULL),
    ('22',  'вул. Вишнева', 7,  1, 1000.00, NULL,                     false, NULL),
    ('23',  'вул. Вишнева', 8,  1,  800.00, NULL,                     false, NULL),
    ('24',  'вул. Вишнева', 9,  1, 1500.00, NULL,                     true,  NULL),
    ('25',  'вул. Вишнева', 10, 1, 1000.00, '4810136300:02:002:0025', false, NULL),
    ('26',  'вул. Вишнева', 11, 1,  600.00, NULL,                     false, NULL),
    ('27',  'вул. Вишнева', 12, 1, 1000.00, NULL,                     true,  NULL),
    ('28',  'вул. Вишнева', 13, 1,  800.00, NULL,                     false, NULL),
    ('29',  'вул. Вишнева', 14, 1, 1000.00, NULL,                     false, NULL)
) AS v(number, street, col, row, area_m2, cadastral_number, has_building, notes)
ON CONFLICT (tenant_id, lower(number)) WHERE archived_at IS NULL DO UPDATE SET
    street = EXCLUDED.street, kind = EXCLUDED.kind, area_m2 = EXCLUDED.area_m2,
    cadastral_number = EXCLUDED.cadastral_number, has_building = EXCLUDED.has_building,
    notes = EXCLUDED.notes, boundary = EXCLUDED.boundary;

-- The road between the two rows: common land, drawn on the map
INSERT INTO plot (tenant_id, number, street, kind, area_m2, boundary)
VALUES ('11111111-1111-1111-1111-111111111111', 'R-1', 'вул. Садова', 'COMMON', 2970.00,
        ST_MakeEnvelope(30.7000, 46.60036, 30.704935, 46.60044, 4326))
ON CONFLICT (tenant_id, lower(number)) WHERE archived_at IS NULL DO UPDATE SET
    street = EXCLUDED.street, kind = EXCLUDED.kind, area_m2 = EXCLUDED.area_m2,
    cadastral_number = EXCLUDED.cadastral_number, has_building = EXCLUDED.has_building,
    notes = EXCLUDED.notes, boundary = EXCLUDED.boundary;

INSERT INTO plot (tenant_id, number, street, kind, area_m2, boundary)
SELECT '22222222-2222-2222-2222-222222222222', n::text, 'вул. Лісова', 'PLOT', 600.00,
       ST_MakeEnvelope(30.8000 + n * 0.0003, 46.7000, 30.8000 + n * 0.0003 + 0.00028, 46.7003, 4326)
FROM generate_series(1, 2) AS n
ON CONFLICT (tenant_id, lower(number)) WHERE archived_at IS NULL DO UPDATE SET
    street = EXCLUDED.street, kind = EXCLUDED.kind, area_m2 = EXCLUDED.area_m2, boundary = EXCLUDED.boundary;

-- Until ownership and the ledger exist (specs 007+) every plot is GREY with no debt.
INSERT INTO plot_status (plot_id, tenant_id, status, debt)
SELECT p.id, p.tenant_id, 'GREY', 0
FROM plot p
ON CONFLICT (plot_id) DO NOTHING;

-- Members and roles (spec 002). Names only: the contacts (phones, e-mails equal to the Keycloak demo users, addresses,
-- messengers, notes) and delivery channels are encrypted personal data (spec 014) and come from demo-contacts.json,
-- written by a Java callback after this script. A new row starts with PAPER (the channel rule needs ciphertext for the
-- others); on conflict the channel is left untouched. keycloak_subject stays null (linked on first sign-in).
SELECT seed_base_roles(id) FROM tenant;

INSERT INTO member (id, tenant_id, last_name, first_name, middle_name, delivery_channel) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'Коваленко', 'Олександр', 'Петрович', 'PAPER'),
    ('aaaaaaaa-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'Шевченко', 'Ірина', 'Володимирівна', 'PAPER'),
    ('aaaaaaaa-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', 'Бондаренко', 'Андрій', 'Миколайович', 'PAPER'),
    ('aaaaaaaa-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', 'Ткаченко', 'Сергій', 'Іванович', 'PAPER'),
    ('bbbbbbbb-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', 'Мельник', 'Олена', 'Степанівна', 'PAPER'),
    ('bbbbbbbb-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222', 'Лисенко', 'Тарас', NULL, 'PAPER')
ON CONFLICT (id) DO UPDATE SET
    last_name = EXCLUDED.last_name, first_name = EXCLUDED.first_name, middle_name = EXCLUDED.middle_name;

INSERT INTO role (id, tenant_id, base_code, name, name_customized, description) VALUES
    ('cccccccc-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', NULL, 'Електрик', true, 'Роль без дозволів до появи лічильників')
ON CONFLICT (id) DO NOTHING;

-- Legacy English role text (name and description) becomes Ukrainian unless the chairman edited it.
UPDATE role r SET name = v.new_name, description = v.new_description
FROM (VALUES
    ('cccccccc-0000-0000-0000-000000000001'::uuid, 'Electrician', 'Custom role without permissions',
     'Електрик', 'Роль без дозволів до появи лічильників')
) AS v(id, old_name, old_description, new_name, new_description)
WHERE r.id = v.id AND r.name = v.old_name AND r.description = v.old_description;

INSERT INTO member_role (tenant_id, member_id, role_id)
SELECT m.tenant_id, m.id, md5(m.tenant_id::text || ':' || r.code)::uuid
FROM (VALUES
    ('aaaaaaaa-0000-0000-0000-000000000001'::uuid, 'CHAIRMAN'),
    ('aaaaaaaa-0000-0000-0000-000000000002'::uuid, 'TREASURER'),
    ('aaaaaaaa-0000-0000-0000-000000000003'::uuid, 'MEMBER'),
    ('aaaaaaaa-0000-0000-0000-000000000004'::uuid, 'MEMBER'),
    ('bbbbbbbb-0000-0000-0000-000000000001'::uuid, 'MEMBER'),
    ('bbbbbbbb-0000-0000-0000-000000000002'::uuid, 'MEMBER')
) AS r(member_id, code)
JOIN member m ON m.id = r.member_id
ON CONFLICT DO NOTHING;

INSERT INTO member_role (tenant_id, member_id, role_id) VALUES
    ('11111111-1111-1111-1111-111111111111', 'aaaaaaaa-0000-0000-0000-000000000004', 'cccccccc-0000-0000-0000-000000000001')
ON CONFLICT DO NOTHING;

-- Demo members 5..35 of the Demo cooperative (contacts and channels are in demo-contacts.json; example.com emails never
-- collide with Keycloak users).
-- Mix of channels with matching contacts; 26 has no email and no phone (PAPER); 7, 14 and 29 have two phones; 20 is archived.
INSERT INTO member (id, tenant_id, last_name, first_name, middle_name, delivery_channel, archived_at) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000005', '11111111-1111-1111-1111-111111111111', 'Марченко', 'Наталія', 'Григорівна', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000006', '11111111-1111-1111-1111-111111111111', 'Савченко', 'Віктор', 'Олексійович', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000007', '11111111-1111-1111-1111-111111111111', 'Мороз', 'Галина', 'Павлівна', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000008', '11111111-1111-1111-1111-111111111111', 'Кравченко', 'Дмитро', 'Андрійович', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000009', '11111111-1111-1111-1111-111111111111', 'Поліщук', 'Оксана', 'Юріївна', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000010', '11111111-1111-1111-1111-111111111111', 'Руденко', 'Богдан', NULL, 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000011', '11111111-1111-1111-1111-111111111111', 'Литвиненко', 'Світлана', 'Михайлівна', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000012', '11111111-1111-1111-1111-111111111111', 'Гриценко', 'Юрій', 'Васильович', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000013', '11111111-1111-1111-1111-111111111111', 'Зінченко', 'Тетяна', 'Олександрівна', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000014', '11111111-1111-1111-1111-111111111111', 'Павленко', 'Роман', 'Ігорович', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000015', '11111111-1111-1111-1111-111111111111', 'Бойко', 'Людмила', 'Петрівна', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000016', '11111111-1111-1111-1111-111111111111', 'Кузьменко', 'Василь', 'Степанович', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000017', '11111111-1111-1111-1111-111111111111', 'Олійник', 'Катерина', NULL, 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000018', '11111111-1111-1111-1111-111111111111', 'Ткачук', 'Ігор', 'Романович', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000019', '11111111-1111-1111-1111-111111111111', 'Ярошенко', 'Марина', 'Сергіївна', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000020', '11111111-1111-1111-1111-111111111111', 'Гончаренко', 'Микола', 'Дмитрович', 'PAPER', '2026-03-15 10:00:00+00'),
    ('aaaaaaaa-0000-0000-0000-000000000021', '11111111-1111-1111-1111-111111111111', 'Сидоренко', 'Валентина', 'Іванівна', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000022', '11111111-1111-1111-1111-111111111111', 'Ковальчук', 'Павло', 'Анатолійович', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000023', '11111111-1111-1111-1111-111111111111', 'Даниленко', 'Ольга', 'Вікторівна', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000024', '11111111-1111-1111-1111-111111111111', 'Білоус', 'Степан', 'Григорович', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000025', '11111111-1111-1111-1111-111111111111', 'Нестеренко', 'Алла', 'Володимирівна', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000026', '11111111-1111-1111-1111-111111111111', 'Романенко', 'Артем', NULL, 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000027', '11111111-1111-1111-1111-111111111111', 'Федоренко', 'Зоряна', 'Богданівна', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000028', '11111111-1111-1111-1111-111111111111', 'Черненко', 'Леонід', 'Миколайович', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000029', '11111111-1111-1111-1111-111111111111', 'Шаповал', 'Ірина', 'Олексіївна', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000030', '11111111-1111-1111-1111-111111111111', 'Вовк', 'Тарас', 'Ярославович', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000031', '11111111-1111-1111-1111-111111111111', 'Лук''яненко', 'Дарина', 'Андріївна', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000032', '11111111-1111-1111-1111-111111111111', 'Панченко', 'Геннадій', 'Петрович', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000033', '11111111-1111-1111-1111-111111111111', 'Довженко', 'Надія', 'Іванівна', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000034', '11111111-1111-1111-1111-111111111111', 'Харченко', 'Євген', 'Валерійович', 'PAPER', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000035', '11111111-1111-1111-1111-111111111111', 'Стеценко', 'Лариса', 'Олександрівна', 'PAPER', NULL)
ON CONFLICT (id) DO UPDATE SET
    last_name = EXCLUDED.last_name, first_name = EXCLUDED.first_name, middle_name = EXCLUDED.middle_name, archived_at = EXCLUDED.archived_at;

-- Custom role Секретар (Secretary), assigned to the regular member 9. Permissions are reset to the listed set on every run.
INSERT INTO role (id, tenant_id, base_code, name, name_customized, description) VALUES
    ('cccccccc-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', NULL, 'Секретар', true, 'Веде членів, ділянки та власність')
ON CONFLICT (id) DO NOTHING;

UPDATE role r SET name = 'Секретар', description = 'Веде членів, ділянки та власність'
WHERE r.id = 'cccccccc-0000-0000-0000-000000000002' AND r.name = 'Secretary'
  AND r.description = 'Maintains members, plots and ownership';

INSERT INTO role_permission (tenant_id, role_id, permission)
SELECT '11111111-1111-1111-1111-111111111111', 'cccccccc-0000-0000-0000-000000000002', p
FROM unnest(ARRAY['MEMBERS_VIEW', 'MEMBERS_EDIT', 'PLOTS_VIEW', 'PLOTS_EDIT', 'OWNERSHIP_EDIT']) AS p
ON CONFLICT DO NOTHING;

-- Every new demo member holds the base Member role; member 9 additionally holds the Секретар role.
INSERT INTO member_role (tenant_id, member_id, role_id)
SELECT tenant_id, id, md5(tenant_id::text || ':MEMBER')::uuid
FROM member
WHERE tenant_id = '11111111-1111-1111-1111-111111111111' AND id > 'aaaaaaaa-0000-0000-0000-000000000004'
ON CONFLICT DO NOTHING;

INSERT INTO member_role (tenant_id, member_id, role_id) VALUES
    ('11111111-1111-1111-1111-111111111111', 'aaaaaaaa-0000-0000-0000-000000000009', 'cccccccc-0000-0000-0000-000000000002')
ON CONFLICT DO NOTHING;

-- Ownership (spec 007). Plot ids are random, so plots are looked up by (tenant, number); ownership ids are fixed so the
-- script stays idempotent. Plots 25-29 and the road R-1 have no owner. The chairman (1), the treasurer (2) and the
-- electrician (4) own nothing: tests archive or re-role them, and 32-35 are left free as spare members.
--  - plot 3: 1/2 + 1/2; plot 7: 1/2 demo member (3, member@demo.local) + 1/2 Moroz (7); plot 11: 2/3 + 1/3
--  - plot 9: archived member 20 until 2025-05-31, then member 11 (the plot "sold last year", with history)
--  - the demo member also owns plot 15 alone
--  - every other plot of 1-24 and "12а" has one distinct active owner at 1/1
INSERT INTO ownership (id, tenant_id, plot_id, member_id, share_numerator, share_denominator, valid_from, valid_to, basis)
SELECT v.id, p.tenant_id, p.id, v.member_id, v.num, v.den, v.valid_from, v.valid_to, v.basis
FROM (VALUES
    ('dddddddd-0000-0000-0000-000000000001'::uuid, '1',   'aaaaaaaa-0000-0000-0000-000000000009'::uuid, 1, 1, DATE '2012-05-20', NULL::date, NULL),
    ('dddddddd-0000-0000-0000-000000000002'::uuid, '2',   'aaaaaaaa-0000-0000-0000-000000000012'::uuid, 1, 1, DATE '2013-03-11', NULL, NULL),
    ('dddddddd-0000-0000-0000-000000000003'::uuid, '3',   'aaaaaaaa-0000-0000-0000-000000000005'::uuid, 1, 2, DATE '2016-08-01', NULL, 'Спадщина, свідоцтво від 12.07.2016'),
    ('dddddddd-0000-0000-0000-000000000004'::uuid, '3',   'aaaaaaaa-0000-0000-0000-000000000006'::uuid, 1, 2, DATE '2016-08-01', NULL, 'Спадщина, свідоцтво від 12.07.2016'),
    ('dddddddd-0000-0000-0000-000000000005'::uuid, '4',   'aaaaaaaa-0000-0000-0000-000000000013'::uuid, 1, 1, DATE '2014-09-30', NULL, NULL),
    ('dddddddd-0000-0000-0000-000000000006'::uuid, '5',   'aaaaaaaa-0000-0000-0000-000000000014'::uuid, 1, 1, DATE '2011-06-15', NULL, NULL),
    ('dddddddd-0000-0000-0000-000000000007'::uuid, '6',   'aaaaaaaa-0000-0000-0000-000000000015'::uuid, 1, 1, DATE '2017-04-02', NULL, NULL),
    ('dddddddd-0000-0000-0000-000000000008'::uuid, '7',   'aaaaaaaa-0000-0000-0000-000000000003'::uuid, 1, 2, DATE '2019-04-15', NULL, 'Купівля, договір від 10.04.2019'),
    ('dddddddd-0000-0000-0000-000000000009'::uuid, '7',   'aaaaaaaa-0000-0000-0000-000000000007'::uuid, 1, 2, DATE '2019-04-15', NULL, 'Купівля, договір від 10.04.2019'),
    ('dddddddd-0000-0000-0000-000000000010'::uuid, '8',   'aaaaaaaa-0000-0000-0000-000000000016'::uuid, 1, 1, DATE '2015-10-12', NULL, NULL),
    ('dddddddd-0000-0000-0000-000000000011'::uuid, '9',   'aaaaaaaa-0000-0000-0000-000000000020'::uuid, 1, 1, DATE '2015-06-01', DATE '2025-05-31', 'Первісний розподіл'),
    ('dddddddd-0000-0000-0000-000000000012'::uuid, '9',   'aaaaaaaa-0000-0000-0000-000000000011'::uuid, 1, 1, DATE '2025-06-01', NULL, 'Договір купівлі-продажу від 20.05.2025'),
    ('dddddddd-0000-0000-0000-000000000013'::uuid, '10',  'aaaaaaaa-0000-0000-0000-000000000017'::uuid, 1, 1, DATE '2012-11-05', NULL, NULL),
    ('dddddddd-0000-0000-0000-000000000014'::uuid, '11',  'aaaaaaaa-0000-0000-0000-000000000008'::uuid, 2, 3, DATE '2018-02-19', NULL, 'Рішення суду про поділ майна'),
    ('dddddddd-0000-0000-0000-000000000015'::uuid, '11',  'aaaaaaaa-0000-0000-0000-000000000010'::uuid, 1, 3, DATE '2018-02-19', NULL, 'Рішення суду про поділ майна'),
    ('dddddddd-0000-0000-0000-000000000016'::uuid, '12',  'aaaaaaaa-0000-0000-0000-000000000018'::uuid, 1, 1, DATE '2013-07-23', NULL, NULL),
    ('dddddddd-0000-0000-0000-000000000017'::uuid, '12а', 'aaaaaaaa-0000-0000-0000-000000000019'::uuid, 1, 1, DATE '2020-01-14', NULL, 'Виділено з ділянки 12'),
    ('dddddddd-0000-0000-0000-000000000018'::uuid, '13',  'aaaaaaaa-0000-0000-0000-000000000021'::uuid, 1, 1, DATE '2016-05-09', NULL, NULL),
    ('dddddddd-0000-0000-0000-000000000019'::uuid, '14',  'aaaaaaaa-0000-0000-0000-000000000022'::uuid, 1, 1, DATE '2014-12-01', NULL, NULL),
    ('dddddddd-0000-0000-0000-000000000020'::uuid, '15',  'aaaaaaaa-0000-0000-0000-000000000003'::uuid, 1, 1, DATE '2021-03-25', NULL, 'Купівля, договір від 18.03.2021'),
    ('dddddddd-0000-0000-0000-000000000021'::uuid, '16',  'aaaaaaaa-0000-0000-0000-000000000023'::uuid, 1, 1, DATE '2010-08-17', NULL, NULL),
    ('dddddddd-0000-0000-0000-000000000022'::uuid, '17',  'aaaaaaaa-0000-0000-0000-000000000024'::uuid, 1, 1, DATE '2015-02-28', NULL, NULL),
    ('dddddddd-0000-0000-0000-000000000023'::uuid, '18',  'aaaaaaaa-0000-0000-0000-000000000025'::uuid, 1, 1, DATE '2017-09-06', NULL, NULL),
    ('dddddddd-0000-0000-0000-000000000024'::uuid, '19',  'aaaaaaaa-0000-0000-0000-000000000026'::uuid, 1, 1, DATE '2011-04-21', NULL, NULL),
    ('dddddddd-0000-0000-0000-000000000025'::uuid, '20',  'aaaaaaaa-0000-0000-0000-000000000027'::uuid, 1, 1, DATE '2019-11-11', NULL, NULL),
    ('dddddddd-0000-0000-0000-000000000026'::uuid, '21',  'aaaaaaaa-0000-0000-0000-000000000028'::uuid, 1, 1, DATE '2013-01-30', NULL, NULL),
    ('dddddddd-0000-0000-0000-000000000027'::uuid, '22',  'aaaaaaaa-0000-0000-0000-000000000029'::uuid, 1, 1, DATE '2016-06-18', NULL, NULL),
    ('dddddddd-0000-0000-0000-000000000028'::uuid, '23',  'aaaaaaaa-0000-0000-0000-000000000030'::uuid, 1, 1, DATE '2018-10-03', NULL, NULL),
    ('dddddddd-0000-0000-0000-000000000029'::uuid, '24',  'aaaaaaaa-0000-0000-0000-000000000031'::uuid, 1, 1, DATE '2012-02-14', NULL, NULL)
) AS v(id, plot_number, member_id, num, den, valid_from, valid_to, basis)
JOIN plot p ON p.tenant_id = '11111111-1111-1111-1111-111111111111' AND p.number = v.plot_number
     AND p.archived_at IS NULL
ON CONFLICT (id) DO UPDATE SET
    plot_id = EXCLUDED.plot_id, member_id = EXCLUDED.member_id,
    share_numerator = EXCLUDED.share_numerator, share_denominator = EXCLUDED.share_denominator,
    valid_from = EXCLUDED.valid_from, valid_to = EXCLUDED.valid_to, basis = EXCLUDED.basis;

-- The other cooperative: one owned plot (plot 2 stays free), for isolation checks.
INSERT INTO ownership (id, tenant_id, plot_id, member_id, share_numerator, share_denominator, valid_from)
SELECT 'dddddddd-0000-0000-0000-000000000030', p.tenant_id, p.id, 'bbbbbbbb-0000-0000-0000-000000000001', 1, 1,
       DATE '2018-01-01'
FROM plot p
WHERE p.tenant_id = '22222222-2222-2222-2222-222222222222' AND p.number = '1' AND p.archived_at IS NULL
ON CONFLICT (id) DO UPDATE SET
    plot_id = EXCLUDED.plot_id, member_id = EXCLUDED.member_id,
    share_numerator = EXCLUDED.share_numerator, share_denominator = EXCLUDED.share_denominator,
    valid_from = EXCLUDED.valid_from, valid_to = EXCLUDED.valid_to, basis = EXCLUDED.basis;

-- Until debts exist (specs 007+): a plot with a current owner is GREEN, every other plot (no owner, common land) GREY.
UPDATE plot_status s
SET status = CASE WHEN p.kind = 'PLOT' AND EXISTS (
                      SELECT 1 FROM ownership o WHERE o.plot_id = p.id AND o.valid_to IS NULL)
                  THEN 'GREEN' ELSE 'GREY' END,
    debt = 0, oldest_debt_date = NULL, updated_at = now()
FROM plot p
WHERE p.id = s.plot_id;

-- BEGIN demo tariffs
-- Charge types and tariffs (spec 009), demo cooperative only. The four starting charge types come from
-- seed_charge_types (called by seed_base_roles above); "Ремонт дороги" (road repair) is a custom one. Ids are fixed and every insert is
-- DO NOTHING, so re-running never duplicates or overwrites what the chairman changed; the legacy English text of
-- earlier versions is translated only where it still has its exact old value (UPDATEs at the end of this block). Nothing is charged yet: these
-- rows only feed the expected amounts of GET /plots/{id}/tariffs (a 600 m2 plot: membership 25.00, land tax 17.50 a month).
-- Tests that need a clean tariffs state remove these rows and put them back by running this block (see DemoTariffData).
INSERT INTO charge_type (id, tenant_id, code, name, name_customized, description, active, position)
VALUES ('eeeeeeee-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', NULL, 'Ремонт дороги', true,
        'Цільовий збір на ремонт головної дороги', true, 5)
ON CONFLICT DO NOTHING;

INSERT INTO tariff (id, tenant_id, charge_type_id, name, scope_kind)
SELECT v.id, c.tenant_id, c.id, v.name, 'ALL_PLOTS'
FROM (VALUES
    ('ffffffff-0000-0000-0000-000000000001'::uuid, md5('11111111-1111-1111-1111-111111111111:CHARGE_TYPE:MEMBERSHIP')::uuid, 'Для членів кооперативу'),
    ('ffffffff-0000-0000-0000-000000000002'::uuid, md5('11111111-1111-1111-1111-111111111111:CHARGE_TYPE:LAND_TAX')::uuid, 'Земельний податок'),
    ('ffffffff-0000-0000-0000-000000000003'::uuid, md5('11111111-1111-1111-1111-111111111111:CHARGE_TYPE:ELECTRICITY')::uuid, 'Електроенергія за лічильником'),
    ('ffffffff-0000-0000-0000-000000000004'::uuid, 'eeeeeeee-0000-0000-0000-000000000001'::uuid, 'Збір на ремонт дороги')
) AS v(id, charge_type_id, name)
JOIN charge_type c ON c.id = v.charge_type_id AND c.tenant_id = '11111111-1111-1111-1111-111111111111'
ON CONFLICT DO NOTHING;

INSERT INTO tariff_version (id, tenant_id, tariff_id, valid_from, basis, frequency, rate, due_date, note)
SELECT v.id, t.tenant_id, t.id, v.valid_from, v.basis, v.frequency, v.rate, v.due_date, v.note
FROM (VALUES
    ('ffffffff-0000-0000-0001-000000000001'::uuid, 'ffffffff-0000-0000-0000-000000000001'::uuid, DATE '2026-01-01', 'PER_AREA_SOTKA', 'YEARLY', 50.0000, NULL::date, 'Загальні збори 14.12.2025'),
    ('ffffffff-0000-0000-0001-000000000002'::uuid, 'ffffffff-0000-0000-0000-000000000002'::uuid, DATE '2026-01-01', 'PER_AREA_SOTKA', 'YEARLY', 35.0000, NULL, NULL),
    ('ffffffff-0000-0000-0001-000000000003'::uuid, 'ffffffff-0000-0000-0000-000000000003'::uuid, DATE '2026-01-01', 'PER_KWH', 'MONTHLY', 4.3200, NULL, NULL),
    ('ffffffff-0000-0000-0001-000000000004'::uuid, 'ffffffff-0000-0000-0000-000000000004'::uuid, DATE '2026-01-01', 'FIXED_ONE_TIME', 'ONE_OFF', 1200.0000, DATE '2026-12-01', 'Ремонт головної дороги')
) AS v(id, tariff_id, valid_from, basis, frequency, rate, due_date, note)
JOIN tariff t ON t.id = v.tariff_id AND t.tenant_id = '11111111-1111-1111-1111-111111111111'
ON CONFLICT DO NOTHING;

-- One exclusion on the membership tariff: plot 28 has no owner (plots 25-29 are free).
INSERT INTO tariff_scope_plot (tenant_id, tariff_id, plot_id, kind, reason)
SELECT p.tenant_id, 'ffffffff-0000-0000-0000-000000000001', p.id, 'EXCLUDED', 'Звільнено рішенням загальних зборів'
FROM plot p
WHERE p.tenant_id = '11111111-1111-1111-1111-111111111111' AND p.number = '28' AND p.archived_at IS NULL
  AND EXISTS (SELECT 1 FROM tariff t WHERE t.id = 'ffffffff-0000-0000-0000-000000000001')
ON CONFLICT DO NOTHING;

-- Legacy English text of earlier demo data becomes Ukrainian; an edited value does not match and is kept.
UPDATE charge_type c SET name = 'Ремонт дороги', description = 'Цільовий збір на ремонт головної дороги'
WHERE c.id = 'eeeeeeee-0000-0000-0000-000000000001' AND c.name = 'Road repair'
  AND c.description = 'Special-purpose fee for repairing the main road';

UPDATE tariff t SET name = v.new_name
FROM (VALUES
    ('ffffffff-0000-0000-0000-000000000001'::uuid, 'For members', 'Для членів кооперативу'),
    ('ffffffff-0000-0000-0000-000000000002'::uuid, 'Land tax', 'Земельний податок'),
    ('ffffffff-0000-0000-0000-000000000003'::uuid, 'Electricity by meter', 'Електроенергія за лічильником'),
    ('ffffffff-0000-0000-0000-000000000004'::uuid, 'Road repair fee', 'Збір на ремонт дороги')
) AS v(id, old_name, new_name)
WHERE t.id = v.id AND t.name = v.old_name;

-- Run by the owner role (tariff_version is immutable for the application role).
UPDATE tariff_version tv SET note = v.new_note
FROM (VALUES
    ('ffffffff-0000-0000-0001-000000000001'::uuid, 'General meeting of 2025-12-14', 'Загальні збори 14.12.2025'),
    ('ffffffff-0000-0000-0001-000000000004'::uuid, 'Repair of the main road', 'Ремонт головної дороги')
) AS v(id, old_note, new_note)
WHERE tv.id = v.id AND tv.note = v.old_note;

UPDATE tariff_scope_plot s SET reason = 'Звільнено рішенням загальних зборів'
WHERE s.tariff_id = 'ffffffff-0000-0000-0000-000000000001' AND s.reason = 'Exempted by the general meeting';
-- END demo tariffs

-- BEGIN demo meters
-- Meters, the electricity tree and readings (spec 010), demo cooperative plus one meter of the other cooperative.
-- Counts (demo cooperative): 38 meters = 5 network meters (substation, 2 quarters, well, street lighting) + 29 electricity
-- plot meters (28 plots; plots 25 and 28 have none; plot 9 has the replaced meter and its successor) + 4 water meters;
-- 33 tree periods (water meters are outside the tree); 186 readings = 183 approved + 1 pending + 1 rejected + 1 superseded
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

-- The day the readings are counted from. A database that loaded this script on an earlier day keeps its rows dated from
-- that day (the ids encode only the number of days back), so the anchor is read back from a stored reading (plot 20's
-- series, 75 days back); a fresh database anchors on today. New rows then share the dates of the stored ones.
DROP TABLE IF EXISTS pg_temp.demo_anchor;
CREATE TEMP TABLE demo_anchor AS
SELECT coalesce((SELECT r.taken_on + 75 FROM meter_reading r
                 WHERE r.id = md5('demo-reading:' || md5('demo-meter:11111111-1111-1111-1111-111111111111:20:ELECTRICITY')::uuid || ':75')::uuid),
                CURRENT_DATE) AS day;

-- Earlier versions of this script used other reading days (plot templates 0-2, plot 9's new meter, the network meters).
-- Remove those rows by their derived ids so existing databases converge; the days that stay are never touched.
DELETE FROM meter_reading r
USING meter m, plot p, (VALUES (0, 9), (1, 19), (1, 6), (2, 12)) AS o(tpl, d)
WHERE m.id = r.meter_id AND p.tenant_id = m.tenant_id AND p.id = m.plot_id AND r.status = 'APPROVED'
  AND m.tenant_id = '11111111-1111-1111-1111-111111111111' AND m.kind = 'PLOT' AND m.resource = 'ELECTRICITY'
  AND m.replaces_meter_id IS NULL AND p.number <> '20'
  AND o.tpl = regexp_replace(p.number, '\D', '', 'g')::int % 4
  AND r.id = md5('demo-reading:' || m.id || ':' || o.d)::uuid;
DELETE FROM meter_reading r
USING (VALUES (112), (85), (63), (30), (9)) AS o(d)
WHERE r.status = 'APPROVED' AND r.tenant_id = '11111111-1111-1111-1111-111111111111'
  AND r.meter_id::text LIKE '99999999-0000-0000-0001-00000000000%'
  AND r.id = md5('demo-reading:' || r.meter_id || ':' || o.d)::uuid;
DELETE FROM meter_reading r
USING meter m, (VALUES (18), (4)) AS o(d)
WHERE m.id = r.meter_id AND m.id = md5('demo-meter:11111111-1111-1111-1111-111111111111:9:NEW')::uuid
  AND r.status = 'APPROVED' AND r.id = md5('demo-reading:' || m.id || ':' || o.d)::uuid;

-- Approved readings. Each series is an initial value plus a daily rate over the days since installation, so values only
-- grow; the template (0-3, or 4 for plot 20) picks the reading days (days before today): gaps of 6-55 days.
-- Template 4 (plot 20) ends 75 days ago. Plot 25's meter (if the demo ledger block below created it) has a reading of
-- its own and gets none here.
INSERT INTO meter_reading (id, tenant_id, meter_id, taken_on, value, source, status, entered_by_member_id, decided_by_member_id, decided_at)
SELECT md5('demo-reading:' || s.id || ':' || t.d)::uuid, s.tenant_id, s.id, (SELECT day FROM demo_anchor) - t.d,
       s.initial_value + round(s.rate * (((SELECT day FROM demo_anchor) - t.d) - s.installed_on) + ((s.n * 7 + t.d) % 10) / 10.0, 3),
       'ENTERED', 'APPROVED', 'aaaaaaaa-0000-0000-0000-000000000004', 'aaaaaaaa-0000-0000-0000-000000000004', now()
FROM (
    SELECT m.id, m.tenant_id, m.installed_on, m.initial_value,
           regexp_replace(p.number, '\D', '', 'g')::int AS n,
           (4 + regexp_replace(p.number, '\D', '', 'g')::int % 7)::numeric AS rate,
           CASE WHEN p.number = '20' THEN 4 ELSE regexp_replace(p.number, '\D', '', 'g')::int % 4 END AS tpl
    FROM meter m JOIN plot p ON p.tenant_id = m.tenant_id AND p.id = m.plot_id
    WHERE m.tenant_id = '11111111-1111-1111-1111-111111111111' AND m.resource = 'ELECTRICITY' AND m.removed_on IS NULL
      AND m.replaces_meter_id IS NULL AND p.number <> '25'
    UNION ALL
    SELECT m.id, m.tenant_id, m.installed_on, m.initial_value, v.n, v.rate, 5
    FROM meter m
    JOIN (VALUES ('99999999-0000-0000-0001-000000000004'::uuid, 4, 25::numeric),
                 ('99999999-0000-0000-0001-000000000005'::uuid, 5, 8::numeric)) AS v(id, n, rate) ON v.id = m.id
) s
JOIN (VALUES (0, 112), (0, 85), (0, 63), (0, 30), (0, 20), (0, 5),
             (1, 118), (1, 90), (1, 41), (1, 20), (1, 5),
             (2, 100), (2, 58), (2, 27), (2, 20), (2, 5),
             (3, 105), (3, 74), (3, 20), (3, 5),
             (4, 118), (4, 96), (4, 75),
             (5, 118), (5, 100), (5, 74), (5, 41), (5, 20), (5, 5)) AS t(tpl, d) ON t.tpl = s.tpl
ON CONFLICT DO NOTHING;

-- Water: fractional cubic metres; odd plot numbers are read on 4 days, even ones on 3.
INSERT INTO meter_reading (id, tenant_id, meter_id, taken_on, value, source, status, entered_by_member_id, decided_by_member_id, decided_at)
SELECT md5('demo-reading:' || m.id || ':' || t.d)::uuid, m.tenant_id, m.id, (SELECT day FROM demo_anchor) - t.d,
       m.initial_value + round((0.25 + (regexp_replace(p.number, '\D', '', 'g')::int % 5) * 0.037)
                               * (((SELECT day FROM demo_anchor) - t.d) - m.installed_on), 3),
       'ENTERED', 'APPROVED', 'aaaaaaaa-0000-0000-0000-000000000004', 'aaaaaaaa-0000-0000-0000-000000000004', now()
FROM meter m
JOIN plot p ON p.tenant_id = m.tenant_id AND p.id = m.plot_id
JOIN (VALUES (1, 105), (1, 74), (1, 20), (1, 5), (0, 112), (0, 63), (0, 9)) AS t(tpl, d)
     ON t.tpl = regexp_replace(p.number, '\D', '', 'g')::int % 2
WHERE m.tenant_id = '11111111-1111-1111-1111-111111111111' AND m.resource = 'WATER'
ON CONFLICT DO NOTHING;

-- Plot 9: readings of the old meter (the final value is its last point) and of the new one.
INSERT INTO meter_reading (id, tenant_id, meter_id, taken_on, value, source, status, entered_by_member_id, decided_by_member_id, decided_at)
SELECT md5('demo-reading:' || m.id || ':' || v.d)::uuid, m.tenant_id, m.id, (SELECT day FROM demo_anchor) - v.d,
       v.value, 'ENTERED', 'APPROVED', 'aaaaaaaa-0000-0000-0000-000000000004', 'aaaaaaaa-0000-0000-0000-000000000004', now()
FROM (VALUES ('OLD', 118, 2233.000 + 0), ('OLD', 84, 2640.500), ('OLD', 52, 2987.250), ('OLD', 33, 3250.000),
             ('NEW', 20, 112.400), ('NEW', 5, 190.700)) AS v(which, d, value)
JOIN meter m ON m.id = md5('demo-meter:11111111-1111-1111-1111-111111111111:9:' || v.which)::uuid
ON CONFLICT DO NOTHING;

-- The node meters (substation and the two quarters) are read on the same days as the plot rounds (118, 100, 74, 41, 20
-- and 5 days ago). Each node's consumption in an interval is the children's consumption in that interval, as the tree
-- computes it (proportional to the days between the nearest readings, across the replacement on plot 9), times a loss
-- factor of 5-15 % that differs per interval. The substation's children are the two quarters and the well; the street
-- lighting hangs under quarter A. Plot 20 has no reading after 75 days ago and adds nothing in the latest rounds. Plot
-- 25's meter (the demo ledger block's) is left out, so a re-run gives the same values whether or not it exists.
INSERT INTO meter_reading (id, tenant_id, meter_id, taken_on, value, source, status, entered_by_member_id, decided_by_member_id, decided_at)
WITH dates(i, d) AS (
    VALUES (0, 118), (1, 100), (2, 74), (3, 41), (4, 20), (5, 5)
), leaf AS (
    SELECT m.id AS key,
           CASE WHEN p.street = 'вул. Садова' THEN '99999999-0000-0000-0001-000000000002'::uuid
                ELSE '99999999-0000-0000-0001-000000000003'::uuid END AS parent
    FROM meter m JOIN plot p ON p.tenant_id = m.tenant_id AND p.id = m.plot_id
    WHERE m.tenant_id = '11111111-1111-1111-1111-111111111111' AND m.kind = 'PLOT' AND m.resource = 'ELECTRICITY'
      AND m.replaces_meter_id IS NULL AND p.number <> '25'
    UNION ALL
    SELECT '99999999-0000-0000-0001-000000000005'::uuid, '99999999-0000-0000-0001-000000000002'::uuid
    UNION ALL
    SELECT '99999999-0000-0000-0001-000000000004'::uuid, '99999999-0000-0000-0001-000000000001'::uuid
), pts AS (
    SELECT l.key, r.taken_on, r.value - m.initial_value AS cum
    FROM leaf l JOIN meter m ON m.id = l.key
    JOIN meter_reading r ON r.meter_id = m.id AND r.status = 'APPROVED'
    UNION
    SELECT l.key, m.installed_on, 0 FROM leaf l JOIN meter m ON m.id = l.key
    UNION
    SELECT l.key, m.removed_on, m.final_value - m.initial_value
    FROM leaf l JOIN meter m ON m.id = l.key WHERE m.removed_on IS NOT NULL
    UNION
    SELECT l.key, r.taken_on, r.value - n.initial_value + (m.final_value - m.initial_value)
    FROM leaf l JOIN meter m ON m.id = l.key JOIN meter n ON n.replaces_meter_id = m.id
    JOIN meter_reading r ON r.meter_id = n.id AND r.status = 'APPROVED'
    UNION
    SELECT l.key, n.installed_on, m.final_value - m.initial_value
    FROM leaf l JOIN meter m ON m.id = l.key JOIN meter n ON n.replaces_meter_id = m.id
), leafcum AS (
    SELECT l.key, l.parent, dt.i,
           CASE WHEN pr.taken_on IS NULL THEN 0
                WHEN nx.taken_on IS NULL OR nx.taken_on = pr.taken_on THEN pr.cum
                ELSE pr.cum + (nx.cum - pr.cum) * (((SELECT day FROM demo_anchor) - dt.d) - pr.taken_on)::numeric
                              / (nx.taken_on - pr.taken_on) END AS cum
    FROM leaf l CROSS JOIN dates dt
    LEFT JOIN LATERAL (SELECT q.taken_on, q.cum FROM pts q WHERE q.key = l.key AND q.taken_on <= (SELECT day FROM demo_anchor) - dt.d
                       ORDER BY q.taken_on DESC LIMIT 1) pr ON true
    LEFT JOIN LATERAL (SELECT q.taken_on, q.cum FROM pts q WHERE q.key = l.key AND q.taken_on >= (SELECT day FROM demo_anchor) - dt.d
                       ORDER BY q.taken_on LIMIT 1) nx ON true
), leafinc AS (
    SELECT parent, i, cum - coalesce(lag(cum) OVER (PARTITION BY key ORDER BY i), 0) AS inc FROM leafcum
), loss(node, i, f) AS (
    VALUES ('99999999-0000-0000-0001-000000000002'::uuid, 0, 1.09), ('99999999-0000-0000-0001-000000000002'::uuid, 1, 1.07),
           ('99999999-0000-0000-0001-000000000002'::uuid, 2, 1.12), ('99999999-0000-0000-0001-000000000002'::uuid, 3, 1.08),
           ('99999999-0000-0000-0001-000000000002'::uuid, 4, 1.10), ('99999999-0000-0000-0001-000000000002'::uuid, 5, 1.07),
           ('99999999-0000-0000-0001-000000000003'::uuid, 0, 1.06), ('99999999-0000-0000-0001-000000000003'::uuid, 1, 1.09),
           ('99999999-0000-0000-0001-000000000003'::uuid, 2, 1.11), ('99999999-0000-0000-0001-000000000003'::uuid, 3, 1.07),
           ('99999999-0000-0000-0001-000000000003'::uuid, 4, 1.12), ('99999999-0000-0000-0001-000000000003'::uuid, 5, 1.08),
           ('99999999-0000-0000-0001-000000000001'::uuid, 0, 1.06), ('99999999-0000-0000-0001-000000000001'::uuid, 1, 1.08),
           ('99999999-0000-0000-0001-000000000001'::uuid, 2, 1.05), ('99999999-0000-0000-0001-000000000001'::uuid, 3, 1.09),
           ('99999999-0000-0000-0001-000000000001'::uuid, 4, 1.07), ('99999999-0000-0000-0001-000000000001'::uuid, 5, 1.10)
), quarter AS (
    SELECT li.parent AS node, li.i, round(sum(li.inc) * lo.f, 3) AS inc
    FROM leafinc li JOIN loss lo ON lo.node = li.parent AND lo.i = li.i
    WHERE li.parent <> '99999999-0000-0000-0001-000000000001'::uuid
    GROUP BY li.parent, li.i, lo.f
), substation AS (
    SELECT '99999999-0000-0000-0001-000000000001'::uuid AS node, x.i, round(sum(x.inc) * lo.f, 3) AS inc
    FROM (SELECT node, i, inc FROM quarter
          UNION ALL SELECT parent, i, inc FROM leafinc WHERE parent = '99999999-0000-0000-0001-000000000001'::uuid) x
    JOIN loss lo ON lo.node = '99999999-0000-0000-0001-000000000001'::uuid AND lo.i = x.i
    GROUP BY x.i, lo.f
), series AS (
    SELECT node, i, sum(inc) OVER (PARTITION BY node ORDER BY i) AS cum FROM (
        SELECT node, i, inc FROM quarter UNION ALL SELECT node, i, inc FROM substation) u
)
SELECT md5('demo-reading:' || m.id || ':' || dt.d)::uuid, m.tenant_id, m.id, (SELECT day FROM demo_anchor) - dt.d,
       m.initial_value + se.cum, 'ENTERED', 'APPROVED',
       'aaaaaaaa-0000-0000-0000-000000000004', 'aaaaaaaa-0000-0000-0000-000000000004', now()
FROM series se JOIN meter m ON m.id = se.node JOIN dates dt ON dt.i = se.i
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
  AND r.status = 'APPROVED' AND r.taken_on = (SELECT day FROM demo_anchor) - 58
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
DROP TABLE pg_temp.demo_anchor;
-- END demo meters

-- BEGIN demo ledger
-- The ledger of the demo cooperative (spec 011): what the board would have booked by now, so every screen of the
-- feature has something to show. Dates follow the demo meters: the anchor is the day their readings count from (read back from
-- plot 20's stored reading, as in the meters block), "the open month" is the anchor's month and "the closed month" the
-- one before it (on the first load, October and September 2026):
--  - the closed month: its month run (membership and land tax), the electricity charges of the readings taken in it,
--    plot 12's opening balance of 1 500,00 (membership, "Борг за 2025 рік") on its first day; closed by the chairman;
--  - the open month: its month run, the electricity charges of its readings, one proportional losses run and one
--    common consumers run over the latest round of readings (the 19th to the 5th day before the anchor), and one
--    reversal (plot 14's membership of the open month, "Помилкове нарахування"). Nothing is charged before the closed
--    month;
--  - the losses run books quarter А (losses of about 6.5 %, shared by consumption) and lists quarter Б with a warning:
--    plot 20's meter has no reading after 75 days ago, so quarter Б's value is unknown and is queued ("Не нараховано");
--  - the common consumers run: the well pump among the plots connected to it (by plot-days) and the street lighting
--    equally among all plots, at the "Спільні споживачі" tariff (created here, 4,3200 per kWh);
--  - water: every plot is connected to "Свердловина №1" since 01.05.2020 except 12а, 27 and 29; plot 19 only from the
--    12th day before the anchor;
--  - plot 25 gets a meter installed on 15.12.2025, before the first electricity tariff version (01.01.2026), read once,
--    5 days before the anchor: its interval cannot be charged and is the "not charged" item "Немає тарифу на всі дні";
--  - payments and allocations (spec 015, see the payments part at the end of the block): cash and bank money for 14 owned
--    plots, one prepayment, one split, one reversed payment;
--  - settings: proportional losses, the red threshold at 2 000,00 or 90 days; plot_status holds every plot's debt,
--    oldest unpaid charge and prepayment.
-- Every amount is what the application calculates from the same rows (DemoLedgerDataTests recalculates them with the
-- calculators and compares). The block assumes one version per demo tariff and real readings on the ends of the runs.
-- Re-runnable: it first removes every ledger row of the demo cooperative (charges are append-only, so as nadel_owner
-- with app.ledger_maintenance on, the owner-only bypass of the triggers), its water connections and common consumer
-- settings, then books everything again; a change the chairman made to the demo ledger is lost on a re-run. It runs only when the
-- Flyway placeholder demoledger is 'on' (the dev profile); integration tests keep it off so the demo cooperative they
-- share stays without charges, and load it on purpose (see DemoLedgerData).
DO $demo_ledger$
DECLARE
    demo         constant uuid := '11111111-1111-1111-1111-111111111111';
    chairman     constant uuid := 'aaaaaaaa-0000-0000-0000-000000000001';
    electrician  constant uuid := 'aaaaaaaa-0000-0000-0000-000000000004';
    treasurer    constant uuid := 'aaaaaaaa-0000-0000-0000-000000000002';
    demo_member  constant uuid := 'aaaaaaaa-0000-0000-0000-000000000003';
    quarter_a    constant uuid := '99999999-0000-0000-0001-000000000002';
    quarter_b    constant uuid := '99999999-0000-0000-0001-000000000003';
    well         constant uuid := '99999999-0000-0000-0001-000000000004';
    lighting     constant uuid := '99999999-0000-0000-0001-000000000005';
    el_tariff    constant uuid := 'ffffffff-0000-0000-0000-000000000003';
    cc_tariff    constant uuid := 'ffffffff-0000-0000-0000-000000000005';
    cc_version   constant uuid := 'ffffffff-0000-0000-0001-000000000005';
    electricity  uuid := md5('11111111-1111-1111-1111-111111111111:CHARGE_TYPE:ELECTRICITY')::uuid;
    membership   uuid := md5('11111111-1111-1111-1111-111111111111:CHARGE_TYPE:MEMBERSHIP')::uuid;
    common       uuid := md5('11111111-1111-1111-1111-111111111111:CHARGE_TYPE:COMMON_CONSUMERS')::uuid;
    meter25      uuid := md5('demo-meter:11111111-1111-1111-1111-111111111111:25:ELECTRICITY')::uuid;
    losses_run   uuid := md5('demo-losses-run:11111111-1111-1111-1111-111111111111')::uuid;
    cc_run       uuid := md5('demo-common-consumer-run:11111111-1111-1111-1111-111111111111')::uuid;
    anchor       date;
    open_month   date;
    closed_month date;
    open_id      uuid;
    closed_id    uuid;
    run_from     date;
    run_to       date;
    run_month    uuid;
    el_version   uuid;
    el_rate      numeric;
    cc_rate      numeric;
    memb_version constant uuid := 'ffffffff-0000-0000-0001-000000000001';
    memb_tariff  constant uuid := 'ffffffff-0000-0000-0000-000000000001';
    rec_reason   constant text := 'Виправлено ставку членського внеску';
    rec_id       uuid := md5('demo-recalculation:11111111-1111-1111-1111-111111111111')::uuid;
    rec_at       timestamptz;
    rec_plot     uuid;
    rec_closed   uuid;
    rec_open     uuid;
    rec_reversal uuid := md5('demo-recalculation-reversal:11111111-1111-1111-1111-111111111111')::uuid;
    rec_new      uuid := md5('demo-recalculation-new:11111111-1111-1111-1111-111111111111')::uuid;
    rec_adjust   uuid := md5('demo-recalculation-adjustment:11111111-1111-1111-1111-111111111111')::uuid;
    pay_base     date;
    charges_at   timestamptz;
BEGIN
    IF '${demoledger}' <> 'on' THEN
        RETURN;
    END IF;
    PERFORM set_config('app.ledger_maintenance', 'on', true);

    -- Remove the ledger of the demo cooperative (reversals before what they reverse; payments and allocations first,
    -- they point at charges and billing months).
    DELETE FROM allocation WHERE tenant_id = demo AND releases_allocation_id IS NOT NULL;
    DELETE FROM allocation WHERE tenant_id = demo;
    DELETE FROM payment WHERE tenant_id = demo AND reverses_payment_id IS NOT NULL;
    DELETE FROM payment WHERE tenant_id = demo;
    DELETE FROM receipt_counter WHERE tenant_id = demo;
    DELETE FROM meter_value_use WHERE tenant_id = demo;
    DELETE FROM charge_part WHERE tenant_id = demo;
    DELETE FROM recalculation_line WHERE tenant_id = demo;
    DELETE FROM charge WHERE tenant_id = demo AND (reverses_charge_id IS NOT NULL OR adjusts_charge_id IS NOT NULL);
    DELETE FROM charge WHERE tenant_id = demo;
    DELETE FROM recalculation WHERE tenant_id = demo;
    DELETE FROM losses_run_node WHERE tenant_id = demo;
    DELETE FROM losses_run WHERE tenant_id = demo;
    DELETE FROM common_consumer_run_line WHERE tenant_id = demo;
    DELETE FROM common_consumer_run WHERE tenant_id = demo;
    DELETE FROM not_charged_item WHERE tenant_id = demo;
    DELETE FROM billing_month WHERE tenant_id = demo;
    DELETE FROM common_consumer_billing WHERE tenant_id = demo;
    DELETE FROM common_consumer_user WHERE tenant_id = demo;
    DELETE FROM plot_water_connection WHERE tenant_id = demo;

    anchor := coalesce((SELECT r.taken_on + 75 FROM meter_reading r
                        WHERE r.id = md5('demo-reading:' || md5('demo-meter:' || demo || ':20:ELECTRICITY')::uuid
                                         || ':75')::uuid), CURRENT_DATE);
    open_month := date_trunc('month', anchor)::date;
    closed_month := (open_month - interval '1 month')::date;
    run_from := anchor - 19;
    run_to := anchor - 5;

    -- Settings: proportional losses (runs), the default split of common consumers, the red threshold.
    INSERT INTO cooperative_settings (tenant_id, losses_mode, losses_percent, common_consumer_default_split, red_amount,
                                      red_days)
    VALUES (demo, 'PROPORTIONAL', 0, 'CONNECTED_PLOTS', 2000.00, 90)
    ON CONFLICT (tenant_id) DO UPDATE SET
        losses_mode = EXCLUDED.losses_mode, losses_percent = EXCLUDED.losses_percent,
        common_consumer_default_split = EXCLUDED.common_consumer_default_split, red_amount = EXCLUDED.red_amount,
        red_days = EXCLUDED.red_days, updated_at = now();

    -- Fake requisites for the report templates (feature 013). Filled only while empty, so a chairman's own are kept.
    -- The EDRPOU, the address and the IBAN (a valid mod 97 number) belong to no real organisation.
    UPDATE cooperative_settings SET
        official_name = COALESCE(official_name, 'Садівниче товариство «Надія»'),
        legal_address = COALESCE(legal_address, '01001, м. Київ, вул. Садова, 1'),
        bank_name = COALESCE(bank_name, 'АТ «Демо-банк»'),
        iban = COALESCE(iban, 'UA213223130000026007233566001')
    WHERE tenant_id = demo;
    UPDATE tenant SET edrpou = '12345678' WHERE id = demo AND edrpou IS NULL;

    -- The tariff of the "Спільні споживачі" charge type (seeded by seed_charge_types).
    INSERT INTO tariff (id, tenant_id, charge_type_id, name, scope_kind)
    VALUES (cc_tariff, demo, common, 'Спільні споживачі за лічильником', 'ALL_PLOTS')
    ON CONFLICT DO NOTHING;
    INSERT INTO tariff_version (id, tenant_id, tariff_id, valid_from, basis, frequency, rate, note)
    VALUES (cc_version, demo, cc_tariff, DATE '2026-01-01', 'PER_KWH', 'MONTHLY', 4.3200,
            'Насос свердловини та вуличне освітлення')
    ON CONFLICT DO NOTHING;

    -- Plot 25: a meter installed before the electricity tariff, under quarter Б, read once.
    INSERT INTO meter (id, tenant_id, kind, resource, serial, plot_id, installed_on, initial_value, note)
    SELECT meter25, demo, 'PLOT', 'ELECTRICITY', 'ЕЛ-25', p.id, DATE '2025-12-15', 0.000,
           'Встановлено до появи тарифу на електроенергію'
    FROM plot p
    WHERE p.tenant_id = demo AND p.number = '25' AND p.archived_at IS NULL
    ON CONFLICT DO NOTHING;
    INSERT INTO meter_parent (id, tenant_id, meter_id, parent_meter_id, valid_from)
    SELECT md5('demo-parent:' || m.id)::uuid, demo, m.id, quarter_b, m.installed_on
    FROM meter m WHERE m.id = meter25
    ON CONFLICT DO NOTHING;
    INSERT INTO meter_reading (id, tenant_id, meter_id, taken_on, value, source, status, entered_by_member_id,
                               decided_by_member_id, decided_at)
    SELECT md5('demo-reading:' || m.id || ':5')::uuid, demo, m.id, anchor - 5,
           round(1.25 * ((anchor - 5) - m.installed_on), 3), 'ENTERED', 'APPROVED', electrician, electrician, now()
    FROM meter m WHERE m.id = meter25
    ON CONFLICT DO NOTHING;

    -- Water connections to the well.
    INSERT INTO plot_water_connection (tenant_id, plot_id, source_meter_id, valid_from)
    SELECT demo, p.id, well, CASE WHEN p.number = '19' THEN anchor - 12 ELSE DATE '2020-05-01' END
    FROM plot p
    WHERE p.tenant_id = demo AND p.kind = 'PLOT' AND p.archived_at IS NULL AND p.number NOT IN ('12а', '27', '29')
      AND EXISTS (SELECT 1 FROM meter m WHERE m.id = well);

    -- The street lighting is shared equally per plot; the well keeps the cooperative's default (connected plots).
    INSERT INTO common_consumer_billing (tenant_id, consumer_meter_id, split)
    SELECT demo, lighting, 'EQUAL_PER_PLOT' WHERE EXISTS (SELECT 1 FROM meter m WHERE m.id = lighting);

    -- The two months: the closed one and the open one, both run.
    closed_id := md5('demo-billing-month:' || demo || ':' || closed_month)::uuid;
    open_id := md5('demo-billing-month:' || demo || ':' || open_month)::uuid;
    INSERT INTO billing_month (id, tenant_id, month, status, run_at, closed_at, closed_by, created_at) VALUES
        (closed_id, demo, closed_month, 'CLOSED', least(closed_month + 1 + time '09:30', now()),
         least(open_month + 3 + time '18:00', now()), chairman, least(closed_month + 1 + time '09:30', now())),
        (open_id, demo, open_month, 'OPEN', least(open_month + 1 + time '09:30', now()), NULL, NULL,
         least(open_month + 1 + time '09:30', now()));

    -- The month runs: every chargeable plot (active, not common land) times every tariff of an active charge type
    -- with a recurring basis, or a one-time fee due in the month; a tariff charged by readings is left to them. The
    -- version is the one valid on the month's first day.
    DROP TABLE IF EXISTS pg_temp.demo_tariff_charge;
    CREATE TEMP TABLE demo_tariff_charge AS
    SELECT gen_random_uuid() AS id, x.*,
           CASE WHEN x.basis = 'FIXED_ONE_TIME' THEN round(x.rate, 2)
                ELSE round(x.rate * x.units * x.factor / 12, 2) END AS amount,
           CASE WHEN x.basis = 'FIXED_ONE_TIME' THEN 1 ELSE x.service_to - x.service_from + 1 END AS days,
           CASE WHEN x.basis <> 'PER_AREA_SOTKA' THEN trim_scale(x.units)::text
                WHEN scale(trim_scale(x.units)) < 2 THEN round(x.units, 2)::text
                ELSE trim_scale(x.units)::text END AS quantity
    FROM (
        -- plot 2's membership was booked at 60 per sotka (the rate the recalculation below corrects to 50)
        SELECT p.id AS plot_id, t.charge_type_id, t.id AS tariff_id, v.id AS version_id, v.basis, v.frequency,
               CASE WHEN p.number = '2' AND t.id = memb_tariff THEN 60.0000 ELSE v.rate END AS rate,
               mo.month, mo.billing_month_id,
               CASE WHEN v.basis = 'PER_AREA_SOTKA' THEN p.area_m2 / 100 ELSE 1 END AS units,
               CASE v.frequency WHEN 'MONTHLY' THEN 12 WHEN 'QUARTERLY' THEN 4 ELSE 1 END AS factor,
               CASE WHEN v.basis = 'FIXED_ONE_TIME' THEN v.due_date ELSE mo.month END AS service_from,
               CASE WHEN v.basis = 'FIXED_ONE_TIME' THEN v.due_date
                    ELSE (mo.month + interval '1 month' - interval '1 day')::date END AS service_to
        FROM plot p
        CROSS JOIN (VALUES (closed_month, closed_id), (open_month, open_id)) AS mo(month, billing_month_id)
        JOIN tariff t ON t.tenant_id = p.tenant_id
        JOIN charge_type c ON c.id = t.charge_type_id AND c.active AND c.archived_at IS NULL
        JOIN tariff_version v ON v.tariff_id = t.id
             AND v.valid_from = (SELECT max(w.valid_from) FROM tariff_version w
                                 WHERE w.tariff_id = t.id AND w.valid_from <= mo.month)
        WHERE p.tenant_id = demo AND p.kind = 'PLOT' AND p.archived_at IS NULL
          AND v.basis IN ('PER_PLOT', 'PER_AREA_SOTKA', 'FIXED_ONE_TIME')
          AND (v.basis <> 'FIXED_ONE_TIME' OR date_trunc('month', v.due_date) = mo.month)
          AND CASE WHEN t.scope_kind = 'ALL_PLOTS'
                   THEN NOT EXISTS (SELECT 1 FROM tariff_scope_plot s
                                    WHERE s.tariff_id = t.id AND s.plot_id = p.id AND s.kind = 'EXCLUDED')
                   ELSE EXISTS (SELECT 1 FROM tariff_scope_plot s
                                WHERE s.tariff_id = t.id AND s.plot_id = p.id AND s.kind = 'SELECTED') END
    ) x;

    INSERT INTO charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, amount, service_from,
                        service_to, accrued_on, calculation, source_key, tariff_id, created_by, created_at)
    SELECT c.id, demo, c.plot_id, c.charge_type_id, c.billing_month_id, 'REGULAR', 'TARIFF', c.amount, c.service_from,
           c.service_to, CASE WHEN c.basis = 'FIXED_ONE_TIME' THEN c.service_from ELSE c.month END,
           jsonb_build_object('amount', c.amount::text, 'readings', '[]'::jsonb, 'parts', jsonb_build_array(
               jsonb_build_object('tariffVersionId', c.version_id, 'basis', c.basis, 'frequency', c.frequency,
                                  'quantity', c.quantity,
                                  'unit', CASE WHEN c.basis = 'PER_AREA_SOTKA' THEN 'SOTKA' ELSE 'PLOT' END,
                                  'rate', c.rate::text, 'days', c.days, 'from', c.service_from, 'to', c.service_to))),
           'TARIFF:' || c.tariff_id || ':' || c.plot_id || ':' || to_char(c.month, 'YYYY-MM'), c.tariff_id, chairman,
           b.run_at
    FROM demo_tariff_charge c
    JOIN billing_month b ON b.id = c.billing_month_id
    WHERE c.amount > 0;

    INSERT INTO charge_part (tenant_id, charge_id, tariff_version_id, days, units_num, units_den, rate)
    SELECT demo, c.id, c.version_id, c.days, c.units, 1, c.rate
    FROM demo_tariff_charge c
    WHERE c.amount > 0;

    -- Electricity by readings. The points of every plot electricity meter (the initial value, the approved readings,
    -- the final value; a reading on the installation or removal day takes that point's place) and the intervals between
    -- neighbouring points that end in the closed month or later.
    SELECT v.id, v.rate INTO el_version, el_rate FROM tariff_version v WHERE v.tariff_id = el_tariff;

    DROP TABLE IF EXISTS pg_temp.demo_interval;
    CREATE TEMP TABLE demo_interval AS
    WITH point AS (
        SELECT DISTINCT ON (q.meter_id, q.on_date) q.*
        FROM (
            SELECT m.id AS meter_id, m.plot_id, m.installed_on AS on_date, m.initial_value AS value,
                   'INSTALL:' || m.id AS key, 1 AS rank
            FROM meter m
            UNION ALL
            SELECT m.id, m.plot_id, r.taken_on, r.value, r.id::text, 0
            FROM meter m JOIN meter_reading r ON r.meter_id = m.id AND r.status = 'APPROVED'
            UNION ALL
            SELECT m.id, m.plot_id, m.removed_on, m.final_value, 'REMOVAL:' || m.id, 1
            FROM meter m WHERE m.removed_on IS NOT NULL
        ) q
        JOIN meter m ON m.id = q.meter_id
        WHERE m.tenant_id = demo AND m.kind = 'PLOT' AND m.resource = 'ELECTRICITY'
        ORDER BY q.meter_id, q.on_date, q.rank
    ), step AS (
        SELECT p.*, lag(p.on_date) OVER w AS from_on, lag(p.value) OVER w AS from_value, lag(p.key) OVER w AS from_key
        FROM point p
        WINDOW w AS (PARTITION BY p.meter_id ORDER BY p.on_date)
    )
    SELECT gen_random_uuid() AS id, s.meter_id, s.plot_id, s.from_on, s.from_value, s.from_key,
           s.on_date AS to_on, s.value AS to_value, s.key AS to_key, s.value - s.from_value AS kwh,
           round((s.value - s.from_value) * 1000) AS milli,
           s.from_on + 1 >= (SELECT min(v.valid_from) FROM tariff_version v WHERE v.tariff_id = el_tariff) AS covered,
           round((s.value - s.from_value) * el_rate, 2) AS amount
    FROM step s
    WHERE s.from_on IS NOT NULL AND s.on_date >= closed_month AND s.value > s.from_value;

    INSERT INTO charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, amount, service_from,
                        service_to, accrued_on, calculation, source_key, tariff_id, meter_id, created_at)
    SELECT i.id, demo, i.plot_id, electricity, CASE WHEN i.to_on < open_month THEN closed_id ELSE open_id END,
           'REGULAR', 'CONSUMPTION', i.amount, i.from_on + 1, i.to_on, i.to_on,
           jsonb_build_object('amount', i.amount::text,
               'parts', jsonb_build_array(jsonb_build_object('tariffVersionId', el_version, 'basis', 'PER_KWH',
                   'frequency', 'MONTHLY', 'quantity', i.kwh::text, 'unit', 'KWH', 'rate', el_rate::text,
                   'days', i.to_on - i.from_on, 'from', i.from_on + 1, 'to', i.to_on)),
               'readings', jsonb_build_array(
                   jsonb_build_object('meterId', i.meter_id, 'takenOn', i.from_on, 'value', i.from_value::text),
                   jsonb_build_object('meterId', i.meter_id, 'takenOn', i.to_on, 'value', i.to_value::text))),
           'CONSUMPTION:' || i.from_key || ':' || i.to_key, el_tariff, i.meter_id,
           least(i.to_on + time '19:00', now())
    FROM demo_interval i
    WHERE i.covered AND i.amount > 0;

    INSERT INTO charge_part (tenant_id, charge_id, tariff_version_id, days, units_num, units_den, rate)
    SELECT demo, i.id, el_version, i.to_on - i.from_on, div(i.milli, gcd(i.milli, 1000)), div(1000, gcd(i.milli, 1000)),
           el_rate
    FROM demo_interval i
    WHERE i.covered AND i.amount > 0;

    INSERT INTO meter_value_use (tenant_id, meter_id, used_on, charge_id)
    SELECT demo, i.meter_id, d.used_on, i.id
    FROM demo_interval i CROSS JOIN LATERAL (VALUES (i.from_on), (i.to_on)) AS d(used_on)
    WHERE i.covered AND i.amount > 0;

    -- An interval that starts before the first electricity tariff version is not charged: the "not charged" item.
    INSERT INTO not_charged_item (tenant_id, item_key, kind, plot_id, charge_type_id, meter_id, from_date, to_date,
                                  reason, params, status, created_at, updated_at)
    SELECT demo, 'INTERVAL:' || i.meter_id || ':' || i.to_key, 'INTERVAL', i.plot_id, electricity, i.meter_id,
           i.from_on + 1, i.to_on, 'NO_TARIFF_FOR_ALL_DAYS',
           jsonb_build_object('tariffIds', jsonb_build_array(el_tariff::text), 'fromKey', i.from_key,
                              'toKey', i.to_key),
           'OPEN', least(i.to_on + time '19:00', now()), least(i.to_on + time '19:00', now())
    FROM demo_interval i
    WHERE NOT i.covered;

    -- The values the runs read: every electricity meter's value at the end of the day before the range and at the end
    -- of its last day. REAL on a point's day, CALCULATED between two points, UNKNOWN without a point after the day.
    DROP TABLE IF EXISTS pg_temp.demo_end;
    CREATE TEMP TABLE demo_end AS
    SELECT m.id AS meter_id, m.kind, m.plot_id, m.name, d.day,
           (SELECT r.value FROM meter_reading r
            WHERE r.meter_id = m.id AND r.status = 'APPROVED' AND r.taken_on = d.day) AS value,
           CASE WHEN EXISTS (SELECT 1 FROM meter_reading r
                             WHERE r.meter_id = m.id AND r.status = 'APPROVED' AND r.taken_on = d.day)
                     OR m.installed_on = d.day OR m.removed_on = d.day THEN 'REAL'
                WHEN EXISTS (SELECT 1 FROM meter_reading r
                             WHERE r.meter_id = m.id AND r.status = 'APPROVED' AND r.taken_on > d.day)
                     OR m.removed_on > d.day THEN 'CALCULATED'
                ELSE 'UNKNOWN' END AS state,
           (SELECT max(r.taken_on) FROM meter_reading r
            WHERE r.meter_id = m.id AND r.status = 'APPROVED' AND r.taken_on < d.day) AS last_reading_on
    FROM meter m CROSS JOIN (VALUES (run_from - 1), (run_to)) AS d(day)
    WHERE m.tenant_id = demo AND m.resource = 'ELECTRICITY' AND m.installed_on <= run_from - 1
      AND (m.removed_on IS NULL OR m.removed_on >= run_to);

    -- The two quarters and their children over the range (no meter moved inside it).
    DROP TABLE IF EXISTS pg_temp.demo_child;
    CREATE TEMP TABLE demo_child AS
    SELECT mp.parent_meter_id AS node, s.meter_id, s.kind, s.plot_id,
           e.value - s.value AS kwh, s.state AS start_state, e.state AS end_state, e.last_reading_on
    FROM meter_parent mp
    JOIN demo_end s ON s.meter_id = mp.meter_id AND s.day = run_from - 1
    JOIN demo_end e ON e.meter_id = mp.meter_id AND e.day = run_to
    WHERE mp.tenant_id = demo AND mp.parent_meter_id IN (quarter_a, quarter_b)
      AND mp.valid_from <= run_from AND (mp.valid_to IS NULL OR mp.valid_to >= run_to);

    IF EXISTS (SELECT 1 FROM demo_child WHERE node = quarter_a AND (start_state <> 'REAL' OR end_state <> 'REAL'))
       OR EXISTS (SELECT 1 FROM demo_end
                  WHERE meter_id IN (quarter_a, quarter_b, well, lighting) AND state <> 'REAL') THEN
        RAISE EXCEPTION 'the demo runs expect real readings on % and %', run_from - 1, run_to;
    END IF;

    -- The losses run: quarter А charged, quarter Б unknown. One charge per plot under quarter А with a share of the
    -- losses in proportion to its consumption (exact fraction milli-kWh), priced at the electricity rate, rounded once.
    run_month := CASE WHEN run_to < open_month THEN closed_id ELSE open_id END;

    DROP TABLE IF EXISTS pg_temp.demo_losses;
    CREATE TEMP TABLE demo_losses AS
    WITH node AS (
        SELECT n.meter_id AS node,
               (SELECT e.value FROM demo_end e WHERE e.meter_id = n.meter_id AND e.day = run_to)
               - (SELECT e.value FROM demo_end e WHERE e.meter_id = n.meter_id AND e.day = run_from - 1) AS consumption,
               (SELECT sum(c.kwh) FROM demo_child c WHERE c.node = n.meter_id) AS children,
               (SELECT sum(c.kwh) FROM demo_child c WHERE c.node = n.meter_id AND c.kind = 'PLOT') AS plots
        FROM (VALUES (quarter_a)) AS n(meter_id)
    )
    SELECT gen_random_uuid() AS id, c.plot_id, c.meter_id, round(c.kwh * 1000) AS used_milli,
           round((n.consumption - n.children) * 1000) AS losses_milli, round(n.plots * 1000) AS plots_milli
    FROM node n JOIN demo_child c ON c.node = n.node AND c.kind = 'PLOT' AND c.kwh > 0;

    -- share = losses x used / plots, in kWh: (losses_milli x used_milli) / (plots_milli x 1000)
    ALTER TABLE demo_losses ADD COLUMN num numeric, ADD COLUMN den numeric, ADD COLUMN amount numeric;
    UPDATE demo_losses SET num = losses_milli * used_milli, den = plots_milli * 1000;
    UPDATE demo_losses SET num = div(num, gcd(num, den)), den = div(den, gcd(num, den));
    UPDATE demo_losses SET amount = round(num * el_rate / den, 2);

    INSERT INTO losses_run (id, tenant_id, from_date, to_date, status, billing_month_id, total, created_by, created_at)
    SELECT losses_run, demo, run_from, run_to, 'BOOKED', run_month,
           coalesce((SELECT sum(l.amount) FROM demo_losses l WHERE l.amount > 0), 0), chairman,
           least(run_to + 1 + time '10:00', now());

    INSERT INTO losses_run_node (tenant_id, run_id, node_meter_id, node_kind, node_name, from_date, to_date, position,
                                 outcome, consumption, children, losses, pct, calculated, amount, warning, plots)
    SELECT demo, losses_run, quarter_a, 'QUARTER', (SELECT name FROM meter WHERE id = quarter_a), run_from, run_to, 0,
           'CHARGED', a.consumption, a.children, a.consumption - a.children,
           round((a.consumption - a.children) * 100 / a.consumption, 2), false,
           (SELECT coalesce(sum(l.amount), 0) FROM demo_losses l),
           NULL,
           coalesce((SELECT jsonb_agg(jsonb_build_object('plotId', l.plot_id,
                                                         'kwh', round(l.num / l.den, 3)::text,
                                                         'amount', l.amount::text) ORDER BY l.plot_id)
                     FROM demo_losses l), '[]'::jsonb)
    FROM (SELECT (SELECT e.value FROM demo_end e WHERE e.meter_id = quarter_a AND e.day = run_to)
                 - (SELECT e.value FROM demo_end e WHERE e.meter_id = quarter_a AND e.day = run_from - 1)
                 AS consumption,
                 (SELECT sum(c.kwh) FROM demo_child c WHERE c.node = quarter_a) AS children) a;

    INSERT INTO losses_run_node (tenant_id, run_id, node_meter_id, node_kind, node_name, from_date, to_date, position,
                                 outcome, consumption, children, losses, pct, calculated, amount, warning, plots)
    SELECT demo, losses_run, quarter_b, 'QUARTER', (SELECT name FROM meter WHERE id = quarter_b), run_from, run_to, 1,
           'UNKNOWN',
           (SELECT e.value FROM demo_end e WHERE e.meter_id = quarter_b AND e.day = run_to)
           - (SELECT e.value FROM demo_end e WHERE e.meter_id = quarter_b AND e.day = run_from - 1),
           NULL, NULL, NULL,
           EXISTS (SELECT 1 FROM demo_child c WHERE c.node = quarter_b AND c.start_state <> 'UNKNOWN'
                   AND c.end_state <> 'UNKNOWN' AND (c.start_state = 'CALCULATED' OR c.end_state = 'CALCULATED')),
           0,
           (SELECT jsonb_build_object('code', 'UNKNOWN_VALUE', 'meterId', c.meter_id, 'meterKind', c.kind,
                                      'plotId', c.plot_id, 'lastReadingOn', c.last_reading_on)
            FROM demo_child c WHERE c.node = quarter_b AND c.end_state = 'UNKNOWN' ORDER BY c.meter_id LIMIT 1),
           '[]'::jsonb;

    INSERT INTO charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, amount, service_from,
                        service_to, accrued_on, calculation, source_key, tariff_id, run_id, created_by, created_at)
    SELECT l.id, demo, l.plot_id, electricity, run_month, 'REGULAR', 'LOSSES', l.amount, run_from, run_to, run_to,
           jsonb_build_object('amount', l.amount::text, 'readings', '[]'::jsonb, 'runId', losses_run,
               'nodeMeterId', quarter_a,
               'parts', jsonb_build_array(jsonb_build_object('tariffVersionId', el_version, 'basis', 'PER_KWH',
                   'frequency', 'MONTHLY', 'quantity', round(l.num / l.den, 3)::text, 'unit', 'KWH',
                   'rate', el_rate::text, 'days', run_to - run_from + 1, 'from', run_from, 'to', run_to,
                   'nodeMeterId', quarter_a, 'calculated', false))),
           'LOSSES_RUN:' || losses_run || ':' || l.plot_id, el_tariff, losses_run, chairman,
           least(run_to + 1 + time '10:00', now())
    FROM demo_losses l
    WHERE l.amount > 0;

    INSERT INTO charge_part (tenant_id, charge_id, tariff_version_id, days, units_num, units_den, rate, node_meter_id)
    SELECT demo, l.id, el_version, run_to - run_from + 1, l.num, l.den, el_rate, quarter_a
    FROM demo_losses l
    WHERE l.amount > 0;

    -- The values the charged node read: its own and its children's, at both ends.
    INSERT INTO meter_value_use (tenant_id, meter_id, used_on, run_id)
    SELECT demo, u.meter_id, u.day, losses_run
    FROM (SELECT quarter_a AS meter_id UNION SELECT c.meter_id FROM demo_child c WHERE c.node = quarter_a) AS m
    JOIN demo_end u ON u.meter_id = m.meter_id;

    INSERT INTO not_charged_item (tenant_id, item_key, kind, charge_type_id, meter_id, from_date, to_date, reason,
                                  params, status, created_at, updated_at)
    VALUES (demo, 'LOSSES_NODE:' || quarter_b || ':' || run_from || ':' || run_to, 'LOSSES_NODE', electricity,
            quarter_b, run_from, run_to, 'UNKNOWN_NODE_VALUE', jsonb_build_object('tariffIds', '[]'::jsonb), 'OPEN',
            least(run_to + 1 + time '10:00', now()), least(run_to + 1 + time '10:00', now()));

    -- The common consumers run over the same days: the street lighting equally per plot (every chargeable plot), the
    -- well pump by plot-days among the plots connected to it.
    SELECT v.rate INTO cc_rate FROM tariff_version v WHERE v.id = cc_version;

    DROP TABLE IF EXISTS pg_temp.demo_share;
    CREATE TEMP TABLE demo_share AS
    WITH consumer AS (
        SELECT c.meter_id, c.split, c.position, m.name,
               (SELECT e.value FROM demo_end e WHERE e.meter_id = c.meter_id AND e.day = run_to)
               - (SELECT e.value FROM demo_end e WHERE e.meter_id = c.meter_id AND e.day = run_from - 1) AS consumption
        FROM (VALUES (lighting, 'EQUAL_PER_PLOT', 0), (well, 'CONNECTED_PLOTS', 1)) AS c(meter_id, split, position)
        JOIN meter m ON m.id = c.meter_id
    ), eligible AS (
        SELECT c.meter_id, p.id AS plot_id, run_from AS first_day, run_to AS last_day
        FROM consumer c JOIN plot p ON p.tenant_id = demo AND p.kind = 'PLOT' AND p.archived_at IS NULL
        WHERE c.split = 'EQUAL_PER_PLOT'
        UNION ALL
        SELECT c.meter_id, w.plot_id, greatest(w.valid_from, run_from), least(coalesce(w.valid_to, run_to), run_to)
        FROM consumer c
        JOIN plot_water_connection w ON w.tenant_id = demo AND w.source_meter_id = c.meter_id
        JOIN plot p ON p.id = w.plot_id AND p.kind = 'PLOT' AND p.archived_at IS NULL
        WHERE c.split = 'CONNECTED_PLOTS' AND w.valid_from <= run_to AND (w.valid_to IS NULL OR w.valid_to >= run_from)
    ), counted AS (
        SELECT e.*, e.last_day - e.first_day + 1 AS days,
               count(*) OVER (PARTITION BY e.meter_id) AS plots,
               sum(e.last_day - e.first_day + 1) OVER (PARTITION BY e.meter_id) AS plot_days
        FROM eligible e
    )
    SELECT gen_random_uuid() AS id, c.meter_id, c.split, c.position, c.name, c.consumption, e.plot_id, e.first_day,
           e.last_day, e.days, e.plot_days,
           round(c.consumption * 1000) * CASE WHEN c.split = 'CONNECTED_PLOTS' THEN e.days ELSE 1 END AS num,
           1000 * CASE WHEN c.split = 'CONNECTED_PLOTS' THEN e.plot_days ELSE e.plots END AS den
    FROM consumer c JOIN counted e ON e.meter_id = c.meter_id;

    ALTER TABLE demo_share ADD COLUMN amount numeric;
    UPDATE demo_share SET num = div(num, gcd(num, den)), den = div(den, gcd(num, den));
    UPDATE demo_share SET amount = round(num * cc_rate / den, 2);

    INSERT INTO common_consumer_run (id, tenant_id, from_date, to_date, status, billing_month_id, total, created_by,
                                     created_at)
    SELECT cc_run, demo, run_from, run_to, 'BOOKED', run_month,
           coalesce((SELECT sum(s.amount) FROM demo_share s WHERE s.amount > 0), 0), chairman,
           least(run_to + 1 + time '10:30', now());

    INSERT INTO common_consumer_run_line (tenant_id, run_id, consumer_meter_id, consumer_name, from_date, to_date,
                                          position, split, outcome, consumption, calculated, plot_days, amount,
                                          warning, plots)
    SELECT demo, cc_run, s.meter_id, s.name, run_from, run_to, s.position, s.split, 'CHARGED', s.consumption, false,
           sum(s.days), sum(s.amount) FILTER (WHERE s.amount > 0), NULL,
           jsonb_agg(jsonb_build_object('plotId', s.plot_id, 'days', s.days, 'kwh', round(s.num / s.den, 3)::text,
                                        'amount', s.amount::text) ORDER BY s.plot_id)
    FROM demo_share s
    GROUP BY s.meter_id, s.name, s.position, s.split, s.consumption;

    INSERT INTO charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, amount, service_from,
                        service_to, accrued_on, calculation, source_key, tariff_id, run_id, created_by, created_at)
    SELECT s.id, demo, s.plot_id, common, run_month, 'REGULAR', 'COMMON_CONSUMER', s.amount, run_from, run_to, run_to,
           jsonb_build_object('amount', s.amount::text, 'readings', '[]'::jsonb, 'runId', cc_run,
               'consumerMeterId', s.meter_id, 'consumerName', s.name, 'split', s.split,
               'parts', jsonb_build_array(jsonb_build_object('tariffVersionId', cc_version, 'basis', 'PER_KWH',
                   'frequency', 'MONTHLY', 'quantity', round(s.num / s.den, 3)::text, 'unit', 'KWH',
                   'rate', cc_rate::text, 'days', s.days, 'from', s.first_day, 'to', s.last_day,
                   'calculated', false))),
           'COMMON_CONSUMER_RUN:' || cc_run || ':' || s.meter_id || ':' || s.plot_id, cc_tariff, cc_run, chairman,
           least(run_to + 1 + time '10:30', now())
    FROM demo_share s
    WHERE s.amount > 0;

    INSERT INTO charge_part (tenant_id, charge_id, tariff_version_id, days, units_num, units_den, rate)
    SELECT demo, s.id, cc_version, s.days, s.num, s.den, cc_rate
    FROM demo_share s
    WHERE s.amount > 0;

    INSERT INTO meter_value_use (tenant_id, meter_id, used_on, run_id)
    SELECT demo, e.meter_id, e.day, cc_run
    FROM demo_end e WHERE e.meter_id IN (lighting, well);

    -- Plot 12's opening balance on the first day of the closed month.
    INSERT INTO charge (tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, amount, service_from,
                        service_to, accrued_on, calculation, reason, created_by, created_at)
    SELECT demo, p.id, membership, closed_id, 'OPENING', 'OPENING', 1500.00, closed_month, closed_month, closed_month,
           jsonb_build_object('amount', '1500.00', 'parts', '[]'::jsonb, 'readings', '[]'::jsonb), 'Борг за 2025 рік',
           chairman, least(closed_month + time '10:00', now())
    FROM plot p
    WHERE p.tenant_id = demo AND p.number = '12' AND p.archived_at IS NULL;

    -- Plot 14's membership of the open month, reversed.
    INSERT INTO charge (tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, amount, service_from,
                        service_to, accrued_on, calculation, reverses_charge_id, reason, created_by, created_at)
    SELECT demo, c.plot_id, c.charge_type_id, c.billing_month_id, 'REVERSAL', 'MANUAL_REVERSAL', -c.amount,
           c.service_from, c.service_to, c.accrued_on,
           jsonb_build_object('amount', (-c.amount)::text, 'parts', '[]'::jsonb, 'readings', '[]'::jsonb), c.id,
           'Помилкове нарахування', chairman, least(greatest(open_month + 2, anchor - 3) + time '11:00', now())
    FROM charge c
    JOIN plot p ON p.id = c.plot_id AND p.number = '14'
    WHERE c.tenant_id = demo AND c.source = 'TARIFF' AND c.charge_type_id = membership
      AND c.billing_month_id = open_id;

    -- One booked recalculation (spec 012), exactly as a manual one books it: the membership rate of plot 2 (600 m2) was
    -- 60 per sotka when both months were run (30,00 a month) and is 50 now (25,00). The closed month's 30,00 is
    -- adjusted by -5,00 booked in the open month, the open month's 30,00 is reversed and 25,00 charged again.
    SELECT p.id INTO rec_plot FROM plot p WHERE p.tenant_id = demo AND p.number = '2' AND p.archived_at IS NULL;
    SELECT c.id INTO rec_closed FROM charge c WHERE c.tenant_id = demo AND c.plot_id = rec_plot
        AND c.charge_type_id = membership AND c.source = 'TARIFF' AND c.billing_month_id = closed_id;
    SELECT c.id INTO rec_open FROM charge c WHERE c.tenant_id = demo AND c.plot_id = rec_plot
        AND c.charge_type_id = membership AND c.source = 'TARIFF' AND c.billing_month_id = open_id;
    rec_at := least(greatest(open_month + 4, anchor - 2) + time '12:00', now());

    INSERT INTO recalculation (id, tenant_id, cause, cause_ref_id, scope, reason, total, line_count, plot_count, digest,
                               created_by, created_at)
    VALUES (rec_id, demo, 'MANUAL', NULL,
            jsonb_build_object('causeType', 'MANUAL',
                               'months', jsonb_build_array(to_char(closed_month, 'YYYY-MM'),
                                                           to_char(open_month, 'YYYY-MM')),
                               'chargeTypeIds', jsonb_build_array(membership),
                               'plotIds', jsonb_build_array(rec_plot)),
            rec_reason, -10.00, 2, 1, md5('demo-recalculation-digest:' || rec_id), chairman, rec_at);

    -- the reversal of the open month's charge (in its month)
    INSERT INTO charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, amount, service_from,
                        service_to, accrued_on, calculation, reverses_charge_id, recalculation_id, reason, created_by,
                        created_at)
    SELECT rec_reversal, demo, c.plot_id, c.charge_type_id, c.billing_month_id, 'REVERSAL', 'RECALCULATION',
           -c.amount, c.service_from, c.service_to, c.accrued_on,
           jsonb_build_object('amount', (-c.amount)::text, 'parts', '[]'::jsonb, 'readings', '[]'::jsonb), c.id, rec_id,
           rec_reason, chairman, rec_at
    FROM charge c WHERE c.id = rec_open;

    -- the new regular charge at the corrected rate (the base key is taken, so the key ends in #r)
    INSERT INTO charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, amount, service_from,
                        service_to, accrued_on, calculation, source_key, tariff_id, recalculation_id, created_by,
                        created_at)
    SELECT rec_new, demo, c.plot_id, c.charge_type_id, c.billing_month_id, 'REGULAR', 'TARIFF', 25.00, c.service_from,
           c.service_to, c.accrued_on,
           jsonb_build_object('amount', '25.00', 'readings', '[]'::jsonb, 'parts', jsonb_build_array(
               jsonb_build_object('tariffVersionId', memb_version, 'basis', 'PER_AREA_SOTKA', 'frequency', 'YEARLY',
                                  'quantity', '6.00', 'unit', 'SOTKA', 'rate', '50.0000',
                                  'days', c.service_to - c.service_from + 1, 'from', c.service_from,
                                  'to', c.service_to))),
           c.source_key || '#r' || rec_id, c.tariff_id, rec_id, chairman, rec_at
    FROM charge c WHERE c.id = rec_open;
    INSERT INTO charge_part (tenant_id, charge_id, tariff_version_id, days, units_num, units_den, rate)
    SELECT demo, rec_new, memb_version, c.service_to - c.service_from + 1, 6, 1, 50.0000
    FROM charge c WHERE c.id = rec_open;

    -- the adjustment of the closed month's charge, booked in the open month with the original's period and debt date
    INSERT INTO charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, amount, service_from,
                        service_to, accrued_on, calculation, source_key, tariff_id, adjusts_charge_id,
                        recalculation_id, reason, created_by, created_at)
    SELECT rec_adjust, demo, c.plot_id, c.charge_type_id, open_id, 'ADJUSTMENT', 'RECALCULATION', -5.00,
           c.service_from, c.service_to, c.accrued_on,
           jsonb_build_object('amount', '-5.00', 'parts', '[]'::jsonb, 'readings', '[]'::jsonb),
           'ADJUSTMENT:' || rec_id || ':' || c.id, c.tariff_id, c.id, rec_id, rec_reason, chairman, rec_at
    FROM charge c WHERE c.id = rec_closed;
    INSERT INTO charge_part (tenant_id, charge_id, tariff_version_id, days, units_num, units_den, rate)
    SELECT demo, rec_adjust, memb_version, c.service_to - c.service_from + 1, 6, 1, 50.0000
    FROM charge c WHERE c.id = rec_closed;

    INSERT INTO recalculation_line (tenant_id, recalculation_id, plot_id, charge_type_id, service_from, service_to,
                                    original_charge_id, booked_amount, adjusted_so_far, expected_amount, difference,
                                    action, reasons, reversal_charge_id, new_charge_id, adjustment_charge_id, position)
    SELECT demo, rec_id, c.plot_id, c.charge_type_id, c.service_from, c.service_to, c.id, 30.00, 0.00, 25.00, -5.00,
           'ADJUST', ARRAY['TARIFF_VERSION'], NULL, NULL, rec_adjust, 1
    FROM charge c WHERE c.id = rec_closed;
    INSERT INTO recalculation_line (tenant_id, recalculation_id, plot_id, charge_type_id, service_from, service_to,
                                    original_charge_id, booked_amount, adjusted_so_far, expected_amount, difference,
                                    action, reasons, reversal_charge_id, new_charge_id, adjustment_charge_id, position)
    SELECT demo, rec_id, c.plot_id, c.charge_type_id, c.service_from, c.service_to, c.id, 30.00, 0.00, 25.00, -5.00,
           'REVERSE_AND_RECHARGE', ARRAY['TARIFF_VERSION'], rec_reversal, rec_new, NULL, 2
    FROM charge c WHERE c.id = rec_open;

    -- Payments (spec 015): cash and bank money for 14 of the 25 owned plots, placed on the open charges the way the
    -- application places a payment at entry (AllocationPlanner: accrued_on, charge type position, service_from, created_at,
    -- id as text; a charge's open amount is the charge plus its adjustments minus its allocations; only REGULAR and
    -- OPENING charges that are not reversed). One payment per plot, entered after every charge was booked, always in the
    -- open month: a payment received in a closed month counts in the earliest open month (BillingCalendar.bookingMonth),
    -- which is the open month for any received day of the two demo months. Plot 1 is never paid. The cases:
    --  - paid in full: plots 7 (cash, part of the split), 8 (bank), 10, 14, 21 (cash);
    --  - paid in part: plots 2, 4, 15, 19 (cash, received in the closed month), 5, 6, 9 (bank, payment orders);
    --  - a prepayment: plot 20 pays 300,00 more than it owes, so it ends with the prepayment and no open charge (a state
    --    automatic allocation leaves alone);
    --  - one cash payment split over plots 7 and 15 of the demo member (one group, one receipt number on both parts);
    --  - one reversed payment: plot 3 paid 1 000,00 in cash and the chairman reversed it (its allocation is released
    --    first, then the PAYMENT_REVERSAL row; the plot owes what it did before).
    -- Amounts "in part" are fixed, capped at 90 % of the debt so a plot always keeps something open.
    SELECT coalesce(max(created_at), now()) INTO charges_at FROM charge WHERE tenant_id = demo;
    pay_base := least(anchor, CURRENT_DATE);

    -- The charges that can receive money, with the open amount before payments and the amount open ahead of each in
    -- planner order.
    DROP TABLE IF EXISTS pg_temp.demo_open;
    CREATE TEMP TABLE demo_open AS
    WITH type_pos AS (
        SELECT t.id, row_number() OVER (ORDER BY t.position, t.id) AS pos FROM charge_type t WHERE t.tenant_id = demo
    ), live AS (
        SELECT c.id, c.plot_id, c.accrued_on, tp.pos, coalesce(c.service_from, c.accrued_on) AS service_start,
               c.created_at,
               c.amount + coalesce((SELECT sum(a.amount) FROM charge a
                                    WHERE a.tenant_id = demo AND a.adjusts_charge_id = c.id), 0) AS open_amount
        FROM charge c JOIN type_pos tp ON tp.id = c.charge_type_id
        WHERE c.tenant_id = demo AND c.kind IN ('REGULAR', 'OPENING')
          AND NOT EXISTS (SELECT 1 FROM charge r WHERE r.tenant_id = demo AND r.reverses_charge_id = c.id)
    )
    SELECT l.*,
           coalesce(sum(l.open_amount) OVER (PARTITION BY l.plot_id
                        ORDER BY l.accrued_on, l.pos, l.service_start, l.created_at, l.id::text COLLATE "C"
                        ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS before_open
    FROM live l
    WHERE l.open_amount > 0;

    -- The payments: k is the order of entry; mode FULL pays what the plot owes, PLUS pays that and amount_arg more,
    -- PART pays amount_arg (at most 90 % of the debt); days_ago counts back from today (null: the closed month).
    DROP TABLE IF EXISTS pg_temp.demo_payment;
    CREATE TEMP TABLE demo_payment AS
    SELECT x.*,
           md5('demo-payment:' || demo || ':' || x.plot_number)::uuid AS id,
           md5('demo-payment-group:' || demo || ':' || x.group_k)::uuid AS group_id,
           dense_rank() OVER (PARTITION BY x.source ORDER BY x.group_k) AS group_rank
    FROM (
        SELECT s.k, s.group_k, s.plot_number, s.source, s.reference, s.reversed, s.payer, p.id AS plot_id,
               o.total_open,
               CASE s.mode WHEN 'FULL' THEN o.total_open WHEN 'PLUS' THEN o.total_open + s.amount_arg
                           ELSE least(s.amount_arg, round(o.total_open * 0.9, 2)) END AS amount,
               least(CURRENT_DATE, CASE WHEN s.days_ago IS NULL THEN closed_month + 20
                                        ELSE greatest(pay_base - s.days_ago, open_month) END) AS received_on
        FROM (VALUES
            (1,  1,  '10',  'CASH', 'FULL', 0::numeric,   NULL::text,        18,   false, NULL::uuid),
            (2,  2,  '8',   'BANK', 'FULL', 0,            'ПД-2026-0407',    17,   false, NULL),
            (3,  3,  '2',   'CASH', 'PART', 800.00,       NULL,              15,   false, NULL),
            (4,  4,  '4',   'CASH', 'PART', 1200.00,      NULL,              14,   false, NULL),
            (5,  5,  '5',   'BANK', 'PART', 1000.00,      'ПД-2026-0411',    13,   false, NULL),
            (6,  6,  '14',  'CASH', 'FULL', 0,            NULL,              12,   false, NULL),
            (7,  7,  '9',   'BANK', 'PART', 1500.00,      'ПД-2026-0415',    10,   false, NULL),
            (8,  8,  '19',  'CASH', 'PART', 1000.00,      NULL,              NULL, false, NULL),
            (9,  9,  '21',  'CASH', 'FULL', 0,            NULL,              8,    false, NULL),
            (10, 10, '7',   'CASH', 'FULL', 0,            NULL,              6,    false, demo_member),
            (11, 10, '15',  'CASH', 'PART', 1000.00,      NULL,              6,    false, demo_member),
            (12, 12, '3',   'CASH', 'PART', 1000.00,      NULL,              5,    true,  NULL),
            (13, 13, '20',  'CASH', 'PLUS', 300.00,       NULL,              4,    false, NULL),
            (14, 14, '6',   'BANK', 'PART', 1200.00,      'ПД-2026-0422',    3,    false, NULL)
        ) AS s(k, group_k, plot_number, source, mode, amount_arg, reference, days_ago, reversed, payer)
        JOIN plot p ON p.tenant_id = demo AND p.number = s.plot_number AND p.archived_at IS NULL
        CROSS JOIN LATERAL (SELECT coalesce(sum(o.open_amount), 0) AS total_open
                            FROM demo_open o WHERE o.plot_id = p.id) o
    ) x;

    IF EXISTS (SELECT 1 FROM demo_payment WHERE amount <= 0) THEN
        RAISE EXCEPTION 'a demo payment has nothing to pay';
    END IF;

    INSERT INTO payment (id, tenant_id, plot_id, kind, source, amount, received_on, billing_month_id, group_id,
                         receipt_number, bank_reference, payer_member_id, created_by, created_at)
    SELECT p.id, demo, p.plot_id, 'PAYMENT', p.source, p.amount, p.received_on, open_id, p.group_id,
           CASE WHEN p.source = 'CASH' THEN p.group_rank END, p.reference,
           coalesce(p.payer, (SELECT (array_agg(o.member_id))[1] FROM ownership o
                              WHERE o.plot_id = p.plot_id AND o.valid_to IS NULL HAVING count(*) = 1)),
           treasurer,
           least(greatest(p.received_on + time '10:00' + p.k * interval '1 minute',
                          charges_at + p.k * interval '1 second'), now())
    FROM demo_payment p;

    -- Each payment fills the open charges of its plot in planner order.
    DROP TABLE IF EXISTS pg_temp.demo_alloc;
    CREATE TEMP TABLE demo_alloc AS
    SELECT md5('demo-allocation:' || p.id || ':' || o.id)::uuid AS id, p.id AS payment_id, o.id AS charge_id,
           o.plot_id, least(o.open_amount, p.amount - o.before_open) AS amount
    FROM demo_payment p JOIN demo_open o ON o.plot_id = p.plot_id
    WHERE p.amount - o.before_open > 0;

    INSERT INTO allocation (id, tenant_id, payment_id, charge_id, plot_id, amount, origin, created_by, created_at)
    SELECT a.id, demo, a.payment_id, a.charge_id, a.plot_id, a.amount, 'ENTRY', treasurer, pm.created_at
    FROM demo_alloc a JOIN payment pm ON pm.id = a.payment_id;

    -- The reversed payment: every allocation is released, then the reversal row is written (in the open month, which
    -- is the earliest open month for any received day of the demo months).
    INSERT INTO allocation (id, tenant_id, payment_id, charge_id, plot_id, amount, releases_allocation_id,
                            release_cause, created_by, created_at)
    SELECT md5('demo-allocation-release:' || a.id)::uuid, demo, a.payment_id, a.charge_id, a.plot_id, -a.amount, a.id,
           'PAYMENT_REVERSED', chairman, least(pm.created_at + interval '30 minutes', now())
    FROM demo_alloc a JOIN demo_payment dp ON dp.id = a.payment_id AND dp.reversed
         JOIN payment pm ON pm.id = a.payment_id;

    INSERT INTO payment (id, tenant_id, plot_id, kind, source, amount, received_on, billing_month_id, group_id,
                         reverses_payment_id, reason, created_by, created_at)
    SELECT md5('demo-payment-reversal:' || demo || ':' || dp.plot_number)::uuid, demo, pm.plot_id, 'PAYMENT_REVERSAL',
           pm.source, -pm.amount, pm.received_on, open_id, pm.group_id, pm.id,
           'Платіж внесено помилково, кошти повернуто платнику', chairman,
           least(pm.created_at + interval '30 minutes', now())
    FROM demo_payment dp JOIN payment pm ON pm.id = dp.id
    WHERE dp.reversed;

    INSERT INTO receipt_counter (tenant_id, last_number)
    VALUES (demo, coalesce((SELECT max(receipt_number) FROM payment WHERE tenant_id = demo), 0));

    -- The plot status cache after payments: the debt (charges with their reversals and adjustments minus the net
    -- allocations), the day the oldest REGULAR or OPENING charge that is not reversed and still has an open amount
    -- counts from, the prepayment (payments, reversals negative, minus the net allocations) and the colour as of today.
    UPDATE plot_status s
    SET debt = d.debt, oldest_debt_date = d.oldest, prepayment = d.prepayment,
        status = CASE WHEN p.kind <> 'PLOT' OR NOT EXISTS (SELECT 1 FROM ownership o
                                                           WHERE o.plot_id = p.id AND o.valid_to IS NULL) THEN 'GREY'
                      WHEN d.debt <= 0 THEN 'GREEN'
                      WHEN d.debt > 2000.00 OR CURRENT_DATE - d.oldest > 90 THEN 'RED'
                      ELSE 'YELLOW' END,
        updated_at = now()
    FROM plot p
    CROSS JOIN LATERAL (
        SELECT (SELECT coalesce(sum(c.amount), 0) FROM charge c WHERE c.tenant_id = demo AND c.plot_id = p.id)
               - (SELECT coalesce(sum(a.amount), 0) FROM allocation a WHERE a.tenant_id = demo AND a.plot_id = p.id)
                   AS debt,
               (SELECT min(c.accrued_on) FROM charge c
                WHERE c.tenant_id = demo AND c.plot_id = p.id AND c.kind IN ('REGULAR', 'OPENING')
                  AND NOT EXISTS (SELECT 1 FROM charge r WHERE r.tenant_id = demo AND r.reverses_charge_id = c.id)
                  AND c.amount + coalesce((SELECT sum(j.amount) FROM charge j
                                           WHERE j.tenant_id = demo AND j.adjusts_charge_id = c.id), 0)
                      - coalesce((SELECT sum(a.amount) FROM allocation a
                                  WHERE a.tenant_id = demo AND a.charge_id = c.id), 0) > 0) AS oldest,
               greatest(0, (SELECT coalesce(sum(y.amount), 0) FROM payment y WHERE y.tenant_id = demo AND y.plot_id = p.id)
                           - (SELECT coalesce(sum(a.amount), 0) FROM allocation a
                              WHERE a.tenant_id = demo AND a.plot_id = p.id)) AS prepayment
    ) d
    WHERE p.tenant_id = demo AND s.plot_id = p.id;

    PERFORM set_config('app.ledger_maintenance', 'off', true);
END
$demo_ledger$;
-- END demo ledger
