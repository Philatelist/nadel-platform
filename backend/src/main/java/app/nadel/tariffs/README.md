# tariffs

What the cooperative charges for and how much (feature 009). Slice 1 delivers **charge types**; tariffs, versions, scopes
and the expected amount of a plot follow in later slices.

Tables (all under RLS `tenant_isolation`, an index on `tenant_id`, composite foreign keys on `(tenant_id, id)`; V10):

| Table | What it holds |
|---|---|
| `charge_type` | What is charged for: the four seeded types (`code` MEMBERSHIP, ELECTRICITY, LAND_TAX, WATER) and custom ones |
| `tariff` | A tariff of one charge type, with its `scope_kind` (`ALL_PLOTS` or `SELECTED_PLOTS`). Not built yet |
| `tariff_version` | Append-only rates by date (`valid_from`, basis, frequency, `rate numeric(14,4)`, due date, note). Not built yet |
| `tariff_scope_plot` | Selected plots, or exclusions with a reason; cascades when a plot is deleted. Not built yet |

`tariff`, `tariff_version` and `tariff_scope_plot` exist in V10 already (one migration, one isolation test), but nothing
reads or writes them until the next slices. `nadel_app` has no `DELETE`/`TRUNCATE` on `charge_type` and `tariff`, and
no `UPDATE`/`DELETE`/`TRUNCATE` on `tariff_version`.

## Charge types

- `seed_charge_types(tenant)` writes the four types with deterministic ids `md5(tenant || ':CHARGE_TYPE:' || code)::uuid`,
  positions 1 to 4, English names, Water inactive, `name_customized = false`, `ON CONFLICT DO NOTHING` (never
  overwrites what a chairman changed). `seed_base_roles(tenant)` calls it, so a new cooperative gets the types and the
  roles from one call; V10 seeds every existing cooperative.
- The stored name of a seeded type is the English default. While `nameCustomized` is false clients show their own
  localised default name (the pattern of the base role names); renaming sets the flag for good. A custom type is always
  customised.
- A type is archived, never deleted: `archived_at` hides it from the list, frees its name and blocks changes
  (409 `CHARGE_TYPE_ARCHIVED`, also for archiving it again). An inactive type stays in the list.
- The list is ordered by `(position, id)`; `position` has no unique constraint. A new type goes to `max(position) + 1`
  over all types, archived ones included.

## Endpoints

| Endpoint | Operation | Who |
|---|---|---|
| `GET /charge-types` | `listChargeTypes` | `TARIFFS_VIEW` |
| `POST /charge-types` | `createChargeType` | `TARIFFS_VIEW` and `TARIFFS_MANAGE` |
| `PUT /charge-types/{id}` | `updateChargeType` | `TARIFFS_VIEW` and `TARIFFS_MANAGE` |
| `POST /charge-types/{id}/archive` | `archiveChargeType` | `TARIFFS_VIEW` and `TARIFFS_MANAGE` |
| `PUT /charge-types/order` | `reorderChargeTypes` | `TARIFFS_VIEW` and `TARIFFS_MANAGE` |

The platform operator has no permissions and gets 403 `PERMISSION_DENIED`; a member without the permissions gets 403.
The cooperative always comes from the token. A type of another cooperative or an unknown id is 404 (RLS).

Writes (`ChargeTypeCommands`, one transaction each, the audit event is published in the same transaction):

- Names are trimmed (1 to 100 characters, the contract's `maxLength` applies to the raw text) and unique among
  non-archived types ignoring letter case: the unique index `charge_type_tenant_name_uq` is the guarantee, and a
  pre-check gives the friendly 409 `CHARGE_TYPE_NAME_TAKEN` with `errors[{field: name}]`. The pre-check also refuses a
  name equal (ignoring case) to the English or Ukrainian default of a non-archived seeded type that was never renamed,
  because clients show that default to the user. Once a seeded type is renamed its defaults are free again. The Ukrainian
  defaults can race with a concurrent request (only the index is race-free); that is accepted.
- `PUT` replaces name, description and `active`. A blank description is cleared. Sending back the default name a client
  displays for a never-renamed seeded type (either language) is not a rename: the stored name and `nameCustomized` stay.
  A `PUT` that changes nothing is accepted, writes no audit entry and keeps `updated_at`.
- `PUT /charge-types/order` takes `{ids}`: exactly the non-archived types, each once. The rows are locked
  `FOR UPDATE` in list order first, so the check cannot race with an archive or another reorder; any other set is 409
  `CHARGE_TYPE_ORDER_STALE` and nothing changes. Positions are renumbered from 1; the current order again changes
  nothing. Changing or archiving one type locks its row `FOR UPDATE`.

## Audit

Entity type `CHARGE_TYPE` (the entity type `TARIFF` is in the contract for the next slices), label = the stored name:
`CHARGE_TYPE_CREATED` (`name`, `description`, `active`), `CHARGE_TYPE_UPDATED` (changed fields among `name`,
`description`, `active`), `CHARGE_TYPE_REORDERED` (one entry per type whose position changed, field `position` with the
old and new number), `CHARGE_TYPE_ARCHIVED` (`archived` false to true). Published through `TariffAuditPublisher`.

## Default names: an exception to the English-only rule

The Ukrainian default names of the four seeded types ("Членський внесок", "Електроенергія", "Земельний податок",
"Вода") are needed on the server for the name check above. They live in one UTF-8 resource file,
`src/main/resources/tariffs/charge-type-default-names.properties` (next to the English ones), read by
`DefaultChargeTypeNames`. The file mirrors `chargeTypes.defaultNames` in the web catalog
(`packages/shared/src/i18n/uk.ts`), which is the source of the texts users see; keep the two in step. This is a
narrow exception to "everything in the repository is English", of the same kind as the import vocabulary (ADR 0012).

## Dependencies

`identity` (`CurrentUser`, `AccessProfile`), `audit` (events) and `problem`. Nothing depends on this module yet.
