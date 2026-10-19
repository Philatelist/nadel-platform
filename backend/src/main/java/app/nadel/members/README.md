# members

The people of a cooperative, their roles and the permissions of those roles (ADR 0009). Tables: `member`, `role`,
`role_permission`, `member_role` (all under RLS, composite foreign keys keep links inside one cooperative).

## Public API

`MemberDirectory` and `MemberRef` in the module root: a read-only lookup for other modules, which must never read the
member tables. `find(ids)` returns the names and the archived flag of the given members (archived included);
`idsMatchingName(text)` returns the ids of members whose last, first or middle name contains the text (case-insensitive,
archived included, literal match, at most 200 ids); `lockActive(ids)` pins members for the caller's transaction with a
shared row lock (`FOR SHARE`, in id order, transaction mandatory) and returns those that exist, archived ones included so
the caller can refuse them (used by the change of owner, so a member cannot be archived while the change commits).
`MemberRef.shortName()` is "Last First" without the patronymic. The
implementation is `JpaMemberDirectory` (internal); `plots` uses it for owners (feature 007). Feature 005 (import) will
add more lookups here.

## What is here

- `MemberAccessProfileSource` implements `identity`'s `AccessProfileSource`: finds the active member for a token and
  links the Keycloak subject on the first sign-in. Order: the active member with this subject; otherwise, only when the
  token's e-mail is verified, the active member with this e-mail (case-insensitive) if it has no subject yet, linked by
  a conditional update. A member already linked to another subject is refused (never relinked). Each step runs in its
  own short transaction; a unique-index violation (a concurrent first sign-in won the race, or an archived member still
  holds the subject) is answered by a fresh lookup by subject, so it ends as the linked member or "not linked", never
  as a 500. Logs tenant id, subject and member id only.
- `PermissionUnion`: the union of the permissions of all roles of a member. The Chairman base role always yields every
  `Permission` in code, so a permission added later needs no migration for the chairman. Unknown stored names grant
  nothing.
- `GET /members` (`MembersController`, `@PreAuthorize("hasAuthority('PERM_MEMBERS_VIEW')")`): a page of members sorted by
  last name, then first name. `q` (trimmed) is a case-insensitive substring of last, first or middle name or e-mail;
  when it contains digits it also matches the digits of any phone. Archived members only with `includeArchived=true`.
  One native, parameterised query (LIKE wildcards in `q` are escaped) plus one query for the roles of the whole page.
  Rows are `MemberSummary`: no notes, no postal address.
- `GET /members/{id}`: a caller always opens their own card; another card needs `MEMBERS_VIEW`, otherwise the answer is
  404 `NOT_FOUND` before any lookup, exactly like a missing id. With `MEMBERS_VIEW` the lookup runs under RLS, so a member
  of another cooperative is 404 too. `MemberDtoMapper` is the only place that shapes a member for output: `notes` is
  present only for a viewer with `MEMBERS_VIEW`, otherwise the key is absent.

- Writes (`MemberCommands`, one transaction each, `@PreAuthorize("hasAuthority('PERM_MEMBERS_EDIT')")` on the controller):
  `POST /members` (201 with `Location`), `PUT /members/{id}`, `POST /members/{id}/archive`, `POST /members/{id}/restore`.
  The cooperative comes from the caller's profile, never from the request. Archiving an archived member and restoring an
  active one are no-ops (the card is returned, no audit event); a `PUT` that changes nothing writes no event either.
  - `MemberData.from` validates and normalises: names trimmed and required (`REQUIRED`), phones through `PhoneNumber`
    (`PHONE_INVALID` on `phones[i]` / `viberPhone`, duplicates removed), e-mail trimmed and lower-case, blank optional
    fields become null, `DeliveryChannel.check` (`CHANNEL_CONTACT_MISSING` on `email` / `telegramUsername` / `viberPhone`).
  - E-mail is unique among active members: a pre-check plus a mapping of the unique index `member_tenant_email_uq` give
    409 `EMAIL_TAKEN` with `errors[{field: "email"}]`. Archived members free their e-mail; restore re-checks.
  - `LastChairmanGuard.ensureNotLast(tenant, member)` takes `pg_advisory_xact_lock(hashtextextended(tenant, 0))` in the
    transaction and refuses (409 `LAST_CHAIRMAN`) to take the only active chairman away. Archive and `setMemberRoles` use
    it.
  - `notes` need `MEMBERS_VIEW`: a `PUT` by an editor without it keeps the stored notes, a `POST` stores none.
  - Each change publishes an `audit.AuditEvent` (`MEMBER_CREATED`, `MEMBER_UPDATED`, `MEMBER_ARCHIVED`, `MEMBER_RESTORED`)
    built from `MemberSnapshot.diff(before, after)`; see the `audit` README.

- Roles, all `@PreAuthorize("hasAuthority('PERM_ROLES_MANAGE')")` on the controllers, one transaction each:
  `GET /permissions` (`PermissionCatalog`: the nine permissions in enum order with English text), `GET /roles`
  (base roles first, then custom by name; `memberCount` counts active members only; Chairman always lists all nine),
  `POST /roles`, `PUT /roles/{id}` (full replacement), `DELETE /roles/{id}`, and `PUT /members/{id}/roles` (replaces the
  member's whole role set). Permission changes apply on the next request: `AccessProfile` is resolved from the database on
  every request, nothing is cached.
  - Name: trimmed; empty is 400 `REQUIRED`, over 60 is 400 `TOO_LONG` (field `name`). Unique per cooperative ignoring case
    (`lower(name)` on both sides in the database, so Cyrillic works): a pre-check plus a mapping of the unique index
    `role_tenant_name_uq` give 409 `ROLE_NAME_TAKEN`.
  - Base roles: delete is 409 `BASE_ROLE_IMMUTABLE`; Chairman's permission set must stay all nine (a PUT with the full
    set is accepted and changes nothing, and its name can be changed); Treasurer and Member permissions and every
    description are editable. A PUT omits `description` = clears it.
  - Renaming (feature 004): every role, base roles included, can be renamed; names stay unique per cooperative ignoring
    case. `role.name_customized` (API `nameCustomized`) is true once a base role's stored name was changed, always true for
    custom roles (`Role.isNameCustomized()` derives it); clients show the language catalog name for a base role while the
    flag is false, and the stored name once it is true. `RoleInput.useDefaultName` on a base role restores the standard
    name (the seed name from `BaseRoles.defaultName`, flag false; 409 `ROLE_NAME_TAKEN` if another role holds it); on a
    custom role it is 400 `INVALID` on `useDefaultName`. Renaming never touches permissions, which follow the base code.
  - Role descriptions (feature 005): same rule as names. `role.description_customized` (API `descriptionCustomized`) is
    false while a base role has its standard description, always true for custom roles (`Role.isDescriptionCustomized()`
    derives it); clients show the language catalog description for a base role while it is false. A PUT whose
    description text differs from the stored one (a blank or omitted description counts as `null`) sets the flag; an
    equal text keeps it, so a permission-only save in a language that shows the catalog text does not customise it.
    `RoleInput.useDefaultDescription` on a base role stores the seed description (`BaseRoles.defaultDescription`, flag
    false) and ignores the submitted `description`; on a custom role it is 400 `INVALID` on `useDefaultDescription`. The
    name and description restores are independent and can be combined. V5 flags base roles whose stored description
    already differed from the V2 seed text. A change that only flips a flag is persisted without an audit entry.
  - Audit values: a base role with its standard name (description) is written as the token `base:<CODE>` in the `name`
    (`description`) field, so the reader's client shows it in the reader's language (`RoleSnapshot.auditName`,
    `auditDescription`); the web maps the token by field.
  - Deleting a role that any member row references (active or archived) is 409 `ROLE_IN_USE` (the foreign key is the guard
    against a concurrent assignment).
  - `setMemberRoles`: unknown or other-cooperative role or member is 404. Archived members may be given or lose roles. When the
    new set lacks Chairman, `LastChairmanGuard` runs first (lock, then count), so two concurrent removals leave one chairman
    (`LastChairmanConcurrencyTests`).
  - Audit events: `ROLE_CREATED`, `ROLE_UPDATED` (changed fields only), `ROLE_DELETED` (entity type `ROLE`, label = role
    name; fields `name`, `description`, `permissions` as sorted names joined by ", ") and `MEMBER_ROLES_CHANGED` (field
    `roles`, old and new sorted role names joined by ", "). A change that changes nothing writes no event.
  - Audit name token: the server cannot hold language catalogs, so a base role that still has its standard name is written
    to the audit log as `base:<CODE>` (for example `base:TREASURER`) in the `name` field, the `ROLE_UPDATED` label and the
    `roles` values of `MEMBER_ROLES_CHANGED` (`RoleSnapshot.auditName`); the web audit page renders it as the catalog name
    in the reader's language. A customised name is written as stored. Accepted risk: a custom role literally named
    `base:MEMBER` is shown as the standard Member name in the audit view; it affects display only, never permissions.
  - `BaseRoles` is the Java copy of the base-role matrix; `BaseRolesMatrixTests` compares it with `seed_base_roles`.

Base roles are `CHAIRMAN`, `TREASURER`, `MEMBER` (seeded per cooperative by the SQL function `seed_base_roles`); custom
roles have no base code. Electrician is no longer a base role.

- `MemberActorNames` implements `audit`'s `ActorNames` SPI: current display names of the acting members shown in the
  audit log (`GET /audit`).

Depends on `identity`, `problem` and `audit`.
