-- Demo data for the dev profile only. Idempotent: re-running does not duplicate rows.
-- Demo cooperative: 30 plots on two streets (a row each, on either side of a road) plus the road as common land.
-- Other cooperative: 2 plots. Coordinates are arbitrary.

INSERT INTO tenant (id, name) VALUES
    ('11111111-1111-1111-1111-111111111111', 'Demo Garden Cooperative'),
    ('22222222-2222-2222-2222-222222222222', 'Other Cooperative')
ON CONFLICT (id) DO NOTHING;

-- Plots 25 x 40 m (~0.00033° longitude, ~0.00036° latitude): 15 cells per street along the road.
-- Garden St is the southern row (1-14 and "12а"), Orchard St the northern row (15-29). Areas are varied and independent
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
    ('1',   'Garden St',  0,  0,  600.00, '4810136300:02:001:0001', false, NULL),
    ('2',   'Garden St',  1,  0,  600.00, NULL,                     false, NULL),
    ('3',   'Garden St',  2,  0,  800.00, NULL,                     true,  NULL),
    ('4',   'Garden St',  3,  0, 1000.00, '4810136300:02:001:0004', false, NULL),
    ('5',   'Garden St',  4,  0, 1000.00, NULL,                     false, NULL),
    ('6',   'Garden St',  5,  0, 1200.00, NULL,                     true,  NULL),
    ('7',   'Garden St',  6,  0,  800.00, '4810136300:02:001:0007', false, NULL),
    ('8',   'Garden St',  7,  0, 1000.00, NULL,                     false, NULL),
    ('9',   'Garden St',  8,  0, 1500.00, NULL,                     true,  'Corner plot next to the entrance gate'),
    ('10',  'Garden St',  9,  0, 1000.00, NULL,                     false, NULL),
    ('11',  'Garden St', 10,  0,  600.00, '4810136300:02:001:0011', false, NULL),
    ('12',  'Garden St', 11,  0, 1000.00, NULL,                     true,  NULL),
    ('12а', 'Garden St', 12,  0,  400.00, NULL,                     false, 'Split off from plot 12'),
    ('13',  'Garden St', 13,  0,  800.00, NULL,                     false, NULL),
    ('14',  'Garden St', 14,  0, 1000.00, '4810136300:02:001:0014', false, NULL),
    ('15',  'Orchard St', 0,  1,  600.00, NULL,                     true,  NULL),
    ('16',  'Orchard St', 1,  1,  800.00, NULL,                     false, NULL),
    ('17',  'Orchard St', 2,  1, 1000.00, '4810136300:02:002:0017', false, NULL),
    ('18',  'Orchard St', 3,  1, 1000.00, NULL,                     true,  NULL),
    ('19',  'Orchard St', 4,  1, 1200.00, NULL,                     false, NULL),
    ('20',  'Orchard St', 5,  1,  600.00, NULL,                     false, NULL),
    ('21',  'Orchard St', 6,  1, 1000.00, '4810136300:02:002:0021', true,  NULL),
    ('22',  'Orchard St', 7,  1, 1000.00, NULL,                     false, NULL),
    ('23',  'Orchard St', 8,  1,  800.00, NULL,                     false, NULL),
    ('24',  'Orchard St', 9,  1, 1500.00, NULL,                     true,  NULL),
    ('25',  'Orchard St', 10, 1, 1000.00, '4810136300:02:002:0025', false, NULL),
    ('26',  'Orchard St', 11, 1,  600.00, NULL,                     false, NULL),
    ('27',  'Orchard St', 12, 1, 1000.00, NULL,                     true,  NULL),
    ('28',  'Orchard St', 13, 1,  800.00, NULL,                     false, NULL),
    ('29',  'Orchard St', 14, 1, 1000.00, NULL,                     false, NULL)
) AS v(number, street, col, row, area_m2, cadastral_number, has_building, notes)
ON CONFLICT (tenant_id, lower(number)) WHERE archived_at IS NULL DO UPDATE SET
    street = EXCLUDED.street, kind = EXCLUDED.kind, area_m2 = EXCLUDED.area_m2,
    cadastral_number = EXCLUDED.cadastral_number, has_building = EXCLUDED.has_building,
    notes = EXCLUDED.notes, boundary = EXCLUDED.boundary;

-- The road between the two rows: common land, drawn on the map
INSERT INTO plot (tenant_id, number, street, kind, area_m2, boundary)
VALUES ('11111111-1111-1111-1111-111111111111', 'R-1', 'Garden St', 'COMMON', 2970.00,
        ST_MakeEnvelope(30.7000, 46.60036, 30.704935, 46.60044, 4326))
ON CONFLICT (tenant_id, lower(number)) WHERE archived_at IS NULL DO UPDATE SET
    street = EXCLUDED.street, kind = EXCLUDED.kind, area_m2 = EXCLUDED.area_m2,
    cadastral_number = EXCLUDED.cadastral_number, has_building = EXCLUDED.has_building,
    notes = EXCLUDED.notes, boundary = EXCLUDED.boundary;

INSERT INTO plot (tenant_id, number, street, kind, area_m2, boundary)
SELECT '22222222-2222-2222-2222-222222222222', n::text, 'Forest St', 'PLOT', 600.00,
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
    ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'Kovalenko', 'Oleksandr', 'Petrovych', '{+380501110001}', 'chairman@demo.local', 'EMAIL'),
    ('aaaaaaaa-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'Shevchenko', 'Iryna', 'Volodymyrivna', '{+380501110002}', 'treasurer@demo.local', 'EMAIL'),
    ('aaaaaaaa-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', 'Bondarenko', 'Andrii', 'Mykolaiovych', '{+380501110003}', 'member@demo.local', 'EMAIL'),
    ('aaaaaaaa-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', 'Tkachenko', 'Serhii', 'Ivanovych', '{+380501110004}', 'electrician@demo.local', 'EMAIL'),
    ('bbbbbbbb-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', 'Melnyk', 'Olena', 'Stepanivna', '{+380502220001}', 'member@other.local', 'EMAIL'),
    ('bbbbbbbb-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222', 'Lysenko', 'Taras', NULL, '{}', NULL, 'PAPER')
ON CONFLICT (id) DO UPDATE SET
    last_name = EXCLUDED.last_name, first_name = EXCLUDED.first_name, middle_name = EXCLUDED.middle_name,
    phones = EXCLUDED.phones, email = EXCLUDED.email, delivery_channel = EXCLUDED.delivery_channel;

INSERT INTO role (id, tenant_id, base_code, name, name_customized, description) VALUES
    ('cccccccc-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', NULL, 'Electrician', true, 'Custom role without permissions')
ON CONFLICT (id) DO NOTHING;

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
    ('aaaaaaaa-0000-0000-0000-000000000005', '11111111-1111-1111-1111-111111111111', 'Marchenko', 'Natalia', 'Hryhorivna', '{+380672000005}', 'natalia.marchenko@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000006', '11111111-1111-1111-1111-111111111111', 'Savchenko', 'Viktor', 'Oleksiiovych', '{+380672000006}', 'viktor.savchenko@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000007', '11111111-1111-1111-1111-111111111111', 'Moroz', 'Halyna', 'Pavlivna', '{+380672000007,+380933000007}', 'halyna.moroz@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000008', '11111111-1111-1111-1111-111111111111', 'Kravchenko', 'Dmytro', 'Andriiovych', '{+380672000008}', 'dmytro.kravchenko@example.com', NULL, 'TELEGRAM', '@dmytro_kravchenko', NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000009', '11111111-1111-1111-1111-111111111111', 'Polishchuk', 'Oksana', 'Yuriivna', '{+380672000009}', 'oksana.polishchuk@example.com', NULL, 'EMAIL', NULL, NULL, 'Secretary of the board', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000010', '11111111-1111-1111-1111-111111111111', 'Rudenko', 'Bohdan', NULL, '{+380672000010}', 'bohdan.rudenko@example.com', NULL, 'VIBER', NULL, '+380672000010', NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000011', '11111111-1111-1111-1111-111111111111', 'Lytvynenko', 'Svitlana', 'Mykhailivna', '{+380672000011}', 'svitlana.lytvynenko@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000012', '11111111-1111-1111-1111-111111111111', 'Hrytsenko', 'Yurii', 'Vasylovych', '{+380672000012}', 'yurii.hrytsenko@example.com', '12 Garden St, Kyiv', 'PAPER', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000013', '11111111-1111-1111-1111-111111111111', 'Zinchenko', 'Tetiana', 'Oleksandrivna', '{+380672000013}', 'tetiana.zinchenko@example.com', NULL, 'TELEGRAM', '@tetiana_zinchenko', NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000014', '11111111-1111-1111-1111-111111111111', 'Pavlenko', 'Roman', 'Ihorovych', '{+380672000014,+380933000014}', 'roman.pavlenko@example.com', NULL, 'EMAIL', NULL, NULL, 'Prefers phone calls in the evening', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000015', '11111111-1111-1111-1111-111111111111', 'Boiko', 'Liudmyla', 'Petrivna', '{+380672000015}', 'liudmyla.boiko@example.com', NULL, 'VIBER', NULL, '+380672000015', NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000016', '11111111-1111-1111-1111-111111111111', 'Kuzmenko', 'Vasyl', 'Stepanovych', '{+380672000016}', 'vasyl.kuzmenko@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000017', '11111111-1111-1111-1111-111111111111', 'Oliinyk', 'Kateryna', NULL, '{+380672000017}', 'kateryna.oliinyk@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000018', '11111111-1111-1111-1111-111111111111', 'Tkachuk', 'Ihor', 'Romanovych', '{+380672000018}', 'ihor.tkachuk@example.com', NULL, 'TELEGRAM', '@ihor_tkachuk', NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000019', '11111111-1111-1111-1111-111111111111', 'Yaroshenko', 'Maryna', 'Serhiivna', '{+380672000019}', NULL, '19 Garden St, Kyiv', 'PAPER', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000020', '11111111-1111-1111-1111-111111111111', 'Honcharenko', 'Mykola', 'Dmytrovych', '{+380672000020}', 'mykola.honcharenko@example.com', NULL, 'EMAIL', NULL, NULL, 'Sold the plot, kept for history', '2026-03-15 10:00:00+00'),
    ('aaaaaaaa-0000-0000-0000-000000000021', '11111111-1111-1111-1111-111111111111', 'Sydorenko', 'Valentyna', 'Ivanivna', '{+380672000021}', 'valentyna.sydorenko@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000022', '11111111-1111-1111-1111-111111111111', 'Kovalchuk', 'Pavlo', 'Anatoliiovych', '{+380672000022}', 'pavlo.kovalchuk@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000023', '11111111-1111-1111-1111-111111111111', 'Danylenko', 'Olha', 'Viktorivna', '{+380672000023}', 'olha.danylenko@example.com', NULL, 'TELEGRAM', '@olha_danylenko', NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000024', '11111111-1111-1111-1111-111111111111', 'Bilous', 'Stepan', 'Hryhorovych', '{+380672000024}', 'stepan.bilous@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000025', '11111111-1111-1111-1111-111111111111', 'Nesterenko', 'Alla', 'Volodymyrivna', '{+380672000025}', 'alla.nesterenko@example.com', NULL, 'VIBER', NULL, '+380672000025', NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000026', '11111111-1111-1111-1111-111111111111', 'Romanenko', 'Artem', NULL, '{}', NULL, '26 Garden St, Kyiv', 'PAPER', NULL, NULL, 'No phone or email; letters only', NULL),
    ('aaaaaaaa-0000-0000-0000-000000000027', '11111111-1111-1111-1111-111111111111', 'Fedorenko', 'Zoriana', 'Bohdanivna', '{+380672000027}', 'zoriana.fedorenko@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000028', '11111111-1111-1111-1111-111111111111', 'Chernenko', 'Leonid', 'Mykolaiovych', '{+380672000028}', 'leonid.chernenko@example.com', NULL, 'TELEGRAM', '@leonid_chernenko', NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000029', '11111111-1111-1111-1111-111111111111', 'Shapoval', 'Iryna', 'Oleksiivna', '{+380672000029,+380933000029}', 'iryna.shapoval@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000030', '11111111-1111-1111-1111-111111111111', 'Vovk', 'Taras', 'Yaroslavovych', '{+380672000030}', 'taras.vovk@example.com', NULL, 'VIBER', NULL, '+380672000030', NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000031', '11111111-1111-1111-1111-111111111111', 'Lukianenko', 'Daryna', 'Andriivna', '{+380672000031}', 'daryna.lukianenko@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000032', '11111111-1111-1111-1111-111111111111', 'Panchenko', 'Hennadii', 'Petrovych', '{+380672000032}', NULL, '32 Garden St, Kyiv', 'PAPER', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000033', '11111111-1111-1111-1111-111111111111', 'Dovzhenko', 'Nadiia', 'Ivanivna', '{+380672000033}', 'nadiia.dovzhenko@example.com', NULL, 'TELEGRAM', '@nadiia_dovzhenko', NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000034', '11111111-1111-1111-1111-111111111111', 'Kharchenko', 'Yevhen', 'Valeriiovych', '{+380672000034}', 'yevhen.kharchenko@example.com', NULL, 'EMAIL', NULL, NULL, NULL, NULL),
    ('aaaaaaaa-0000-0000-0000-000000000035', '11111111-1111-1111-1111-111111111111', 'Stetsenko', 'Larysa', 'Oleksandrivna', '{+380672000035}', 'larysa.stetsenko@example.com', NULL, 'VIBER', NULL, '+380672000035', NULL, NULL)
ON CONFLICT (id) DO UPDATE SET
    last_name = EXCLUDED.last_name, first_name = EXCLUDED.first_name, middle_name = EXCLUDED.middle_name,
    phones = EXCLUDED.phones, email = EXCLUDED.email, postal_address = EXCLUDED.postal_address,
    delivery_channel = EXCLUDED.delivery_channel, telegram_username = EXCLUDED.telegram_username,
    viber_phone = EXCLUDED.viber_phone, notes = EXCLUDED.notes, archived_at = EXCLUDED.archived_at;

-- Custom role Secretary, assigned to the regular member 9. Permissions are reset to the listed set on every run.
INSERT INTO role (id, tenant_id, base_code, name, name_customized, description) VALUES
    ('cccccccc-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', NULL, 'Secretary', true, 'Maintains members, plots and ownership')
ON CONFLICT (id) DO NOTHING;

INSERT INTO role_permission (tenant_id, role_id, permission)
SELECT '11111111-1111-1111-1111-111111111111', 'cccccccc-0000-0000-0000-000000000002', p
FROM unnest(ARRAY['MEMBERS_VIEW', 'MEMBERS_EDIT', 'PLOTS_VIEW', 'PLOTS_EDIT', 'OWNERSHIP_EDIT']) AS p
ON CONFLICT DO NOTHING;

-- Every new demo member holds the base Member role; member 9 additionally holds Secretary.
INSERT INTO member_role (tenant_id, member_id, role_id)
SELECT tenant_id, id, md5(tenant_id::text || ':MEMBER')::uuid
FROM member
WHERE tenant_id = '11111111-1111-1111-1111-111111111111' AND id > 'aaaaaaaa-0000-0000-0000-000000000004'
ON CONFLICT DO NOTHING;

INSERT INTO member_role (tenant_id, member_id, role_id) VALUES
    ('11111111-1111-1111-1111-111111111111', 'aaaaaaaa-0000-0000-0000-000000000009', 'cccccccc-0000-0000-0000-000000000002')
ON CONFLICT DO NOTHING;
