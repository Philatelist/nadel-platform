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

-- Members and roles (spec 002). Emails equal the Keycloak demo users; keycloak_subject stays null (linked on first sign-in).
SELECT seed_base_roles(id) FROM tenant;

INSERT INTO member (id, tenant_id, last_name, first_name, middle_name, phones, email, delivery_channel) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'Коваленко', 'Олександр', 'Петрович', '{+380501110001}', 'chairman@demo.local', 'EMAIL'),
    ('aaaaaaaa-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'Шевченко', 'Ірина', 'Володимирівна', '{+380501110002}', 'treasurer@demo.local', 'EMAIL'),
    ('aaaaaaaa-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', 'Бондаренко', 'Андрій', 'Миколайович', '{+380501110003}', 'member@demo.local', 'EMAIL'),
    ('aaaaaaaa-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', 'Ткаченко', 'Сергій', 'Іванович', '{+380501110004}', 'electrician@demo.local', 'EMAIL'),
    ('bbbbbbbb-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', 'Мельник', 'Олена', 'Степанівна', '{+380502220001}', 'member@other.local', 'EMAIL'),
    ('bbbbbbbb-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222', 'Лисенко', 'Тарас', NULL, '{}', NULL, 'PAPER')
ON CONFLICT (id) DO UPDATE SET
    last_name = EXCLUDED.last_name, first_name = EXCLUDED.first_name, middle_name = EXCLUDED.middle_name,
    phones = EXCLUDED.phones, email = EXCLUDED.email, delivery_channel = EXCLUDED.delivery_channel;

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

-- Demo members 5..35 of the Demo cooperative (example.com emails never collide with Keycloak users).
-- Mix of channels with matching contacts; 26 has no email and no phone (PAPER); 7, 14 and 29 have two phones; 20 is archived.
INSERT INTO member (id, tenant_id, last_name, first_name, middle_name, phones, email, postal_address, delivery_channel, telegram_username, viber_phone, notes, archived_at) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000005', '11111111-1111-1111-1111-111111111111', 'Марченко', 'Наталія', 'Григорівна', '{+380672000005}', 'natalia.marchenko@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000006', '11111111-1111-1111-1111-111111111111', 'Савченко', 'Віктор', 'Олексійович', '{+380672000006}', 'viktor.savchenko@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000007', '11111111-1111-1111-1111-111111111111', 'Мороз', 'Галина', 'Павлівна', '{+380672000007,+380933000007}', 'halyna.moroz@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000008', '11111111-1111-1111-1111-111111111111', 'Кравченко', 'Дмитро', 'Андрійович', '{+380672000008}', 'dmytro.kravchenko@example.com', NULL, 'TELEGRAM', '@dmytro_kravchenko', NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000009', '11111111-1111-1111-1111-111111111111', 'Поліщук', 'Оксана', 'Юріївна', '{+380672000009}', 'oksana.polishchuk@example.com', NULL, 'EMAIL', NULL, NULL, 'Секретар правління', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000010', '11111111-1111-1111-1111-111111111111', 'Руденко', 'Богдан', NULL, '{+380672000010}', 'bohdan.rudenko@example.com', NULL, 'VIBER', NULL, '+380672000010', NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000011', '11111111-1111-1111-1111-111111111111', 'Литвиненко', 'Світлана', 'Михайлівна', '{+380672000011}', 'svitlana.lytvynenko@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000012', '11111111-1111-1111-1111-111111111111', 'Гриценко', 'Юрій', 'Васильович', '{+380672000012}', 'yurii.hrytsenko@example.com', 'вул. Садова, 12, Київ', 'PAPER', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000013', '11111111-1111-1111-1111-111111111111', 'Зінченко', 'Тетяна', 'Олександрівна', '{+380672000013}', 'tetiana.zinchenko@example.com', NULL, 'TELEGRAM', '@tetiana_zinchenko', NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000014', '11111111-1111-1111-1111-111111111111', 'Павленко', 'Роман', 'Ігорович', '{+380672000014,+380933000014}', 'roman.pavlenko@example.com', NULL, 'EMAIL', NULL, NULL, 'Краще телефонувати ввечері', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000015', '11111111-1111-1111-1111-111111111111', 'Бойко', 'Людмила', 'Петрівна', '{+380672000015}', 'liudmyla.boiko@example.com', NULL, 'VIBER', NULL, '+380672000015', NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000016', '11111111-1111-1111-1111-111111111111', 'Кузьменко', 'Василь', 'Степанович', '{+380672000016}', 'vasyl.kuzmenko@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000017', '11111111-1111-1111-1111-111111111111', 'Олійник', 'Катерина', NULL, '{+380672000017}', 'kateryna.oliinyk@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000018', '11111111-1111-1111-1111-111111111111', 'Ткачук', 'Ігор', 'Романович', '{+380672000018}', 'ihor.tkachuk@example.com', NULL, 'TELEGRAM', '@ihor_tkachuk', NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000019', '11111111-1111-1111-1111-111111111111', 'Ярошенко', 'Марина', 'Сергіївна', '{+380672000019}', NULL, 'вул. Садова, 19, Київ', 'PAPER', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000020', '11111111-1111-1111-1111-111111111111', 'Гончаренко', 'Микола', 'Дмитрович', '{+380672000020}', 'mykola.honcharenko@example.com', NULL, 'EMAIL', NULL, NULL, 'Продав ділянку, збережено для історії', '2026-03-15 10:00:00+00'),
    ('aaaaaaaa-0000-0000-0000-000000000021', '11111111-1111-1111-1111-111111111111', 'Сидоренко', 'Валентина', 'Іванівна', '{+380672000021}', 'valentyna.sydorenko@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000022', '11111111-1111-1111-1111-111111111111', 'Ковальчук', 'Павло', 'Анатолійович', '{+380672000022}', 'pavlo.kovalchuk@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000023', '11111111-1111-1111-1111-111111111111', 'Даниленко', 'Ольга', 'Вікторівна', '{+380672000023}', 'olha.danylenko@example.com', NULL, 'TELEGRAM', '@olha_danylenko', NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000024', '11111111-1111-1111-1111-111111111111', 'Білоус', 'Степан', 'Григорович', '{+380672000024}', 'stepan.bilous@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000025', '11111111-1111-1111-1111-111111111111', 'Нестеренко', 'Алла', 'Володимирівна', '{+380672000025}', 'alla.nesterenko@example.com', NULL, 'VIBER', NULL, '+380672000025', NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000026', '11111111-1111-1111-1111-111111111111', 'Романенко', 'Артем', NULL, '{}', NULL, 'вул. Садова, 26, Київ', 'PAPER', NULL, NULL, 'Без телефону та пошти; лише листи', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000027', '11111111-1111-1111-1111-111111111111', 'Федоренко', 'Зоряна', 'Богданівна', '{+380672000027}', 'zoriana.fedorenko@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000028', '11111111-1111-1111-1111-111111111111', 'Черненко', 'Леонід', 'Миколайович', '{+380672000028}', 'leonid.chernenko@example.com', NULL, 'TELEGRAM', '@leonid_chernenko', NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000029', '11111111-1111-1111-1111-111111111111', 'Шаповал', 'Ірина', 'Олексіївна', '{+380672000029,+380933000029}', 'iryna.shapoval@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000030', '11111111-1111-1111-1111-111111111111', 'Вовк', 'Тарас', 'Ярославович', '{+380672000030}', 'taras.vovk@example.com', NULL, 'VIBER', NULL, '+380672000030', NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000031', '11111111-1111-1111-1111-111111111111', 'Лук''яненко', 'Дарина', 'Андріївна', '{+380672000031}', 'daryna.lukianenko@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000032', '11111111-1111-1111-1111-111111111111', 'Панченко', 'Геннадій', 'Петрович', '{+380672000032}', NULL, 'вул. Садова, 32, Київ', 'PAPER', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000033', '11111111-1111-1111-1111-111111111111', 'Довженко', 'Надія', 'Іванівна', '{+380672000033}', 'nadiia.dovzhenko@example.com', NULL, 'TELEGRAM', '@nadiia_dovzhenko', NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000034', '11111111-1111-1111-1111-111111111111', 'Харченко', 'Євген', 'Валерійович', '{+380672000034}', 'yevhen.kharchenko@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000035', '11111111-1111-1111-1111-111111111111', 'Стеценко', 'Лариса', 'Олександрівна', '{+380672000035}', 'larysa.stetsenko@example.com', NULL, 'VIBER', NULL, '+380672000035', NULL, NULL)
ON CONFLICT (id) DO UPDATE SET
    last_name = EXCLUDED.last_name, first_name = EXCLUDED.first_name, middle_name = EXCLUDED.middle_name,
    phones = EXCLUDED.phones, email = EXCLUDED.email, postal_address = EXCLUDED.postal_address,
    delivery_channel = EXCLUDED.delivery_channel, telegram_username = EXCLUDED.telegram_username,
    viber_phone = EXCLUDED.viber_phone, notes = EXCLUDED.notes, archived_at = EXCLUDED.archived_at;

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
