# members

The people of a cooperative, their roles and the permissions of those roles (ADR 0009). Tables: `member`, `role`,
`role_permission`, `member_role`, `member_phone_index` (all under RLS, composite foreign keys keep links inside one
cooperative). The contacts of a member are stored encrypted (feature 014, see "Encrypted contacts" below).

## Public API

`MemberDirectory` and `MemberRef` in the module root: a read-only lookup for other modules, which must never read the
member tables. `find(ids)` returns the names and the archived flag of the given members (archived included);
`idsMatchingName(text)` returns the ids of members whose last, first or middle name contains the text (case-insensitive,
archived included, literal match, at most 200 ids); `activeIds()` returns the ids of the members who are not archived
(transaction mandatory; the audience «усі члени» of an announcement, feature 017); `lockActive(ids)` pins members for the caller's transaction with a
shared row lock (`FOR SHARE`, in id order, transaction mandatory) and returns those that exist, archived ones included so
the caller can refuse them (used by the change of owner, so a member cannot be archived while the change commits).
`MemberRef.shortName()` is "Last First" without the patronymic. `contacts(ids)` returns `MemberContacts` (names,
`fullName()` "Last First Middle", `shortName()`, the phones as stored, the e-mail, the archived flag) of the given
members, archived ones included, unknown ids and other cooperatives' members absent (RLS); it runs in the caller's
transaction (`MANDATORY`, 500 ids per `IN` list). It is the **only** way another module reads member contacts: feature
013 (the debtors report, which also requires `MEMBERS_VIEW`) reads owners' names and contacts through it. Since
feature 014 it opens the sealed phones and e-mail with the key of the caller's cooperative (`TenantContext`); a value
that does not open fails the call with 500 `PERSONAL_DATA_UNREADABLE`, never an empty contact. Nothing logs the values. The
implementation is `JpaMemberDirectory` (internal); `plots` uses it for owners (feature 007). Feature 005 (import) will
add more lookups here. `deliveryChannels(ids)` (feature 016) returns how each given member receives invoices as the
public enum `DeliveryChannel` (`EMAIL`, `TELEGRAM`, `VIBER`, `PAPER`; the values of `member.delivery_channel`),
archived members included, unknown ids and other cooperatives' members absent; it runs in the caller's transaction
(`MANDATORY`, 500 ids per `IN` list). The documents module prints the paper set of a month's invoices with it; feature
017 delivers by it.

`MemberChannels` (feature 017, implemented by `MemberChannelCommands`): `setPreferredChannel(memberId, channel, actor)`
is the member's own choice of channel in the account («Сповіщення», through `notifications`). The member row is locked
(`FOR UPDATE`); the rule is the member form's (`DeliveryChannel.check`: `EMAIL` needs the e-mail, `VIBER` a Viber
phone, otherwise 400 `VALIDATION_FAILED` with `CHANNEL_CONTACT_MISSING`); a change is audited as `MEMBER_UPDATED`
(field `deliveryChannel`) with the actor as author; the current channel again writes nothing. 404 for a member not of
the cooperative.

`TelegramLinkStatus` (feature 017) is an SPI implemented by `notifications`: `linkedAmong(memberIds)` returns the
members whose Telegram is linked to the cooperative's bot. `TelegramLinkFlags` reads it through an `ObjectProvider`
(no implementation: nobody is linked) and the member card carries only the flag `telegramLinked`, never the member's
Telegram account details.

`RoleHolders` (feature 016, implemented by `JpaRoleHolders`): `activeHolders(baseRoleCode)` returns the active (not
archived) members who hold the base role with the code (`CHAIRMAN`, `TREASURER`, `MEMBER`) as `MemberRef`, in name
order (last, first, middle name, then id); a custom role never counts. It runs in the caller's transaction
(`MANDATORY`, RLS). The invoice prints the chairman's and the treasurer's names with it (sealed in its snapshot).

`MemberImport` (feature 008) is what the Excel import may ask of this module: `plan(tenantId, importId, rows)` (the
transaction is mandatory, nothing is saved) returns one `MemberImportItem` per `MemberImportRow` in the same order:
`NEW` / `UPDATE` / `UNCHANGED`, the member id (an existing member, or for a new one
`UUID.nameUUIDFromBytes(importId + "/member/" + lower(externalId))`, so a re-plan equals the preview), the changed fields
and the field problems. The implementation is `MemberImportService` (internal), with bulk lookups only, in chunks of 500:
active members whose `email_bidx` is the blind index of one of the rows' e-mails, and active members with a
`member_phone_index.phone_bidx` of one of the rows' first phones (then filtered by name); the matched members' contacts
are opened to compare and overlay them:
- Only active members are matched. A row with an e-mail matches by e-mail (case ignored); a row without one matches by
  last name, first name (case ignored) and first phone (any phone of the member, compared in international format); no
  e-mail and no phone is `MEMBER_KEY_MISSING` (reported on `email`), two or more members with that name and phone is
  `MEMBER_AMBIGUOUS` (on `phones`). When the e-mail belongs to one member but the name and first phone identify another,
  the row is `EMAIL_TAKEN` (on `email`).
- Duplicates in the file are `DUPLICATE_IN_FILE` on the later row: the same `externalId`, e-mail, or last name + first
  name + first phone (case ignored), and two rows that match the same existing member.

`MemberImport.lock(plan)` takes the matched members (`UPDATE` and `UNCHANGED`) `FOR UPDATE` in id order, and
`MemberImport.apply(tenantId, rows, plan, actor)` saves a plan: `NEW` members are persisted (not merged) with the ids of
the plan, `UPDATE` members get the same overlay as the plan (blank keeps) and are updated, with `MEMBER_CREATED` /
`MEMBER_UPDATED` audit events built by `MemberAuditPublisher.event`, the helper `MemberCommands` uses too (it publishes
the same event at once). `apply` **returns** the events and publishes nothing: `imports` publishes them as `AuditBatch`es. Both need the
caller's transaction (`MANDATORY`) and never start or commit one. A plan that no longer holds, or a violation of the
e-mail index at the final flush, is 409 `IMPORT_STALE` (the import then asks for the file again).
- A blank value keeps the stored value of a matched member (notes are never touched); a filled phone cell replaces the
  phones; a blank channel keeps the stored channel (a new member gets `PAPER`). The rules are those of the member form:
  `MemberData.tryFrom` (the non-throwing form of `from`) plus the constraints of the contract's `MemberInput` (lengths,
  e-mail format) checked with the Bean Validation `Validator` and `problem.ValidationProblems`. One problem per field,
  named without an index (`phones`). `UNCHANGED` is decided with `MemberSnapshot.diff`, the same comparison the audit uses,
  so a different phone order or spelling is no change.

`MemberArchiveGuard` is a synchronous veto SPI (feature 007): `ensureCanArchive(memberId)` is called by
`MemberCommands.archive` on every guard bean of the application, inside the archive transaction, and throws a 409
`ApiProblemException` to refuse. `plots` implements it (`OwnershipArchiveGuard`, 409 `MEMBER_OWNS_PLOT` while the member
has a current ownership). A guard must only read and must take no lock that is ordered before a member lock.

`InvitationDelivery` (feature 020) is an SPI implemented by `notifications`: `emailReady(tenantId)` (the cooperative's
mailbox is set up) and `deliver(tenantId, memberId, email, link, expiresAt)`, which sends the invitation letter with the
one-time link synchronously and answers `InvitationDeliveryResult` (the channels, or a `Failure`: `NOT_CONFIGURED` or
a delivery code `SMTP_REJECTED`, `SMTP_AUTH_FAILED`, `CONNECTION_FAILED`, `SMTP_HOST_NOT_ALLOWED`). `InvitationDeliveries`
reads it through an `ObjectProvider` (no implementation: no cooperative can invite). Synchronous on purpose: the link
never enters the outbox.

## Access to the system (feature 020)

Tables `member_access` (one row per member: `state` `NONE` | `INVITED` | `ACTIVE` | `DISABLED` with `invited_at`,
`activated_at`, `disabled_at`, `last_sign_in_at`; no row = never invited) and `member_invitation` (the links: only the
SHA-256 of the token, the member's e-mail blind index at the time, `expires_at`, `sent_at`, `used_at`, `revoked_at`;
the application may set only those three times and deletes nothing; one open sent link per member,
`member_invitation_open_uq`), both under RLS (V36). `members_invitation_tenant(hash)` is the cross-tenant
`SECURITY DEFINER` function that answers only the cooperative of a token hash (ADR 0020).

- **Status** (`MemberAccessState`): `NONE` or no row is `NOT_INVITED`, except a member without a row who is linked to a
  Keycloak subject (an account made by hand before feature 020, linked by the legacy sign-in): that member is `ACTIVE`.
  V36 wrote `ACTIVE` for the members linked then; an invitation writes a row (`NONE`) before it links a subject.
- **Card** (`MemberCards`, the only place that builds `Member` for output): `access` only for a viewer with
  `MEMBERS_VIEW` (a member never sees it on their own card): `status`, `invitedAt`, `lastSignInAt`, `disabledAt` (only
  for `DISABLED`) and `inviteBlockedBy` while the status allows an invitation (not `ACTIVE`): `EMAIL_MISSING` without an
  e-mail, else `MAILBOX_NOT_CONFIGURED` when `InvitationDelivery.emailReady` is false.
- **Invite** (`POST /members/{id}/invitation`, `InvitationController`, `MEMBERS_VIEW` and `MEMBERS_INVITE`;
  `MemberInvitations.invite`), two-phase so a failed letter costs nothing: (1) a read checks 404, 409
  `MEMBER_ARCHIVED`, `INVITATION_PENDING` (`INVITED`), `ACCESS_ALREADY_ACTIVE` (`ACTIVE`), `MEMBER_EMAIL_MISSING`,
  `DELIVERY_NOT_CONFIGURED`; (2) outside any transaction `ensureAccount` with `identity.SignInAccounts`: an account of
  the e-mail in another cooperative (or in none) is 409 `EMAIL_IN_OTHER_COOPERATIVE`; an account of this cooperative is
  linked and turned on, unless another member of the cooperative holds it or this member is linked to another account
  that still exists (409 `SIGN_IN_ACCOUNT_IN_USE`); otherwise a new account (no password, e-mail not verified,
  `tenant_id`); (3) a transaction locks the member `FOR UPDATE`, checks again, sets `keycloak_subject` (the unique index
  `member_tenant_subject_uq` maps to `SIGN_IN_ACCOUNT_IN_USE`) and inserts the link unsent; (4) outside any transaction
  `InvitationDelivery.deliver` with `{nadel.web.url}/set-password?token=…`, valid `nadel.invitations.ttl` (72 h);
  (5) a transaction revokes the member's older open links, marks the new one sent, sets `INVITED` + `invited_at` and
  publishes the audit event `MEMBER_INVITED` (entity `MEMBER`, field `channels` only: `EMAIL` or `EMAIL, TELEGRAM`).
  A letter that is not sent revokes the unsent link in a compensating transaction and answers 502
  `INVITATION_NOT_SENT` with `failureCode` (`NOT_CONFIGURED` is 409 `DELIVERY_NOT_CONFIGURED`): the state stays, nothing
  is audited, an earlier link still works. Keycloak failures are 503 `SIGN_IN_SERVICE_UNAVAILABLE`.
- **Resend** (`POST /members/{id}/invitation/resend`, same permissions; `MemberInvitations.resend`): the same five steps
  for a member who is `INVITED` (otherwise 409 `NOT_INVITED`; archived 409 `MEMBER_ARCHIVED`) with a new token; only a
  sent letter revokes the older links, so a 502 `INVITATION_NOT_SENT` leaves the previous link working. Audited as
  `MEMBER_INVITE_RESENT` (field `channels`).
- **Disable** (`POST /members/{id}/access/disable`, same permissions; `MemberInvitations.disable`): under the member's
  lock, `INVITED` or `ACTIVE` (a legacy link without a row included: a `DISABLED` row is written) becomes `DISABLED` +
  `disabled_at`, every unused link is revoked (`MemberInvitationRows.revokeUnused`) and `MemberAccessRevoked(tenantId,
  memberId)` is published (`MemberAccessRevocation`); audited as `MEMBER_ACCESS_DISABLED` (no fields). Already
  `DISABLED` is 200 without an audit entry; never invited is 409 `ACCESS_NOT_GRANTED`. The answer is the `MemberAccess`.
  The actor's own access is 409 `OWN_ACCESS` (checked first, nothing read), and the access of the last active chairman
  whose access is on is 409 `LAST_CHAIRMAN` (`LastChairmanGuard.ensureNotLastWithAccess`, chairman lock before the
  member row, as the archive does), so a cooperative never locks its board out. The web card hides the button on the
  member's own card.
- **Archive and restore**: archiving a member with access or an open invitation does the same as disable (in the
  archive transaction, audited as `MEMBER_ARCHIVED` only); a member never invited gets no row. Because the archive
  turns access off, it also refuses the last active chairman whose access is on (409 `LAST_CHAIRMAN`). Restore leaves access as
  it is: `DISABLED` stays, the board invites again.
- **E-mail change** (`PUT /members/{id}`, `MemberCommands.update`): for a member with access or an open invitation
  (`INVITED`, `ACTIVE`, legacy included) whose e-mail changes to another address, `MemberLoginGuard` first asks Keycloak
  outside any transaction (`findByLogin`): a login of another cooperative is 409 `EMAIL_IN_OTHER_COOPERATIVE` on the
  field `email`, an account of this cooperative that is not the member's is 409 `SIGN_IN_ACCOUNT_IN_USE`, Keycloak down
  is 503 (nothing changed). Members without access, archived members and unchanged e-mails never ask Keycloak. Then
  the update transaction (member locked `FOR UPDATE`) stores `INVITED`, revokes the unused links and publishes
  `MemberLoginChanged(tenantId, memberId)`. Removing the e-mail of such a member does the same (the card then shows
  `inviteBlockedBy: EMAIL_MISSING`). The Excel import never changes a login, so it needs none of this: a row with an
  e-mail matches only the member who has that e-mail (case ignored; `MemberData` stores e-mails in lower case, so a
  case-only difference is `UNCHANGED`), a blank e-mail keeps the stored one, and a row with a new e-mail and the name
  and first phone of an existing member is a new member. The `email_bidx` of a matched member therefore never changes
  through `MemberImport.apply`, no Keycloak call is needed, and the access and an open link stay as they were
  (`ImportMemberLoginTests`). A change to the matching that let a row change an e-mail must route it through
  `MemberLoginGuard` and `MemberLoginChanged` as `MemberCommands.update` does.
- **`MemberAccessListener`** (ADR 0007: `@Async @TransactionalEventListener`, `TenantContext.runAs`, a read-only
  `TransactionTemplate` that re-reads the member, Keycloak calls outside any transaction). The events are internal to
  the module and carry ids only (never the e-mail or a link). `MemberAccessRevoked`: only while the access is still
  `DISABLED`, the account is turned off (`enabled: false`) and logged out; a missing account needs nothing.
  `MemberLoginChanged`: only while still `INVITED`, the account's user name and e-mail become the current e-mail with
  `emailVerified: false` and its sessions end; then, when the mailbox is set up and no open link exists for that e-mail
  yet, `MemberInvitations.sendForNewLogin` issues and sends a new link through the resend steps (no actor, no audit
  entry of its own: the change is `MEMBER_UPDATED`). Without a mailbox the member stays `INVITED` without a link until
  the board resends. Failures that may pass (503, 502) are thrown so the outbox resubmits the publication (each retry a
  new token); refusals that will not pass (409: the account belongs to another cooperative or the login is taken) are
  logged and the publication completes.
- **Tokens** (`InvitationTokens`): 32 bytes of `SecureRandom`, base64url without padding; stored as SHA-256 of the
  text. The token, the link and the password never reach the audit log, the outbox, a log line or an exception.
- **Public endpoints** (`PublicInvitationController`, `/api/v1/public/**` open in `identity`'s `SecurityConfiguration`,
  token in the body): `POST /public/invitations/inspect` answers the e-mail of a usable link; `POST
  /public/invitations/accept` (password 8..128, Bean Validation `TOO_SHORT` / `TOO_LONG`) hashes the token, finds the
  cooperative with `members_invitation_tenant`, binds it with `TenantContext.runAs` and, in one transaction, locks the
  member then the link, checks it (sent, not used, not revoked, not expired by the `Clock`, member active and `INVITED`,
  link e-mail = the member's current `email_bidx`), sets the password (`reset-password`, a refused one is 400
  `PASSWORD_POLICY` and the link stays usable) and `emailVerified: true`, `requiredActions: []`, `enabled: true`, then
  `used_at` and `ACTIVE` + `activated_at`. Every unusable link is the same 404 `INVITATION_INVALID`.
- **Rate limit of the public endpoints** (`InvitationRateLimiter`, ADR 0020): before anything else each request is
  counted by the client address (`request.getRemoteAddr()`, the client behind Caddy through
  `server.forward-headers-strategy: native`) and, when the token belongs to a link
  (`MemberInvitations.rateLimitKey`: the cooperative and the link's e-mail blind index, never the e-mail), by that
  e-mail; each endpoint counts apart. Over `nadel.invitations.rate-limit.max-requests` (5) within `window` (15 min)
  the answer is 429 `TOO_MANY_REQUESTS` and is not counted. The counters are in memory (`max-keys`, 10,000, least
  recently used dropped): with several instances each counts on its own, and a restart clears them. The tests raise
  the limit (`nadel.test.invitation-rate-limit`), `make e2e` and the CI e2e job set
  `NADEL_INVITATIONS_RATE_LIMIT_MAX_REQUESTS=1000`.
- **Sign-in** (`MemberAccessProfileSource`): `INVITED` and `DISABLED` are refused at once (403 `MEMBER_NOT_LINKED`);
  no row or `NONE` keeps the legacy rules (an account made by hand links by its verified e-mail and reads as `ACTIVE`).
- **Last sign-in** (`lastSignInAt` on the card): when a linked member's row is `ACTIVE` and `last_sign_in_at` is missing
  or older than `nadel.identity.last-sign-in-interval` (default 1 h, by the application `Clock`), `resolve` runs
  `update member_access set last_sign_in_at = :now where member_id = ? and state = 'ACTIVE' and (last_sign_in_at is
  null or last_sign_in_at < :now - interval)` in its own short transaction; the usual request writes nothing, and a
  failed write never refuses the sign-in. A member linked by the legacy path without a row gets no row and no time.

## First-chairman command (feature 020)

`java -jar nadel-backend.jar --spring.profiles.include=command --nadel.command=first-chairman
--nadel.first-chairman.cooperative="…" --nadel.first-chairman.email=… --nadel.first-chairman.last-name=…
--nadel.first-chairman.first-name=… --nadel.first-chairman.resend=true|false` (run once by
`infra/stand/first-chairman.sh` with `docker compose run --rm --no-deps backend`).

- **Profile `command`** (`application.yml`): `spring.main.web-application-type=none` (no web server; `identity`'s
  `SecurityConfiguration` is servlet-only), no JobRunr job server, `nadel.telegram.bot-enabled=false` (no bot polling,
  no delivery sweep), no resubmission of the backend's outstanding events at start. It works together with `dev` (the
  stand) or any other profile.
- **`FirstChairmanCommand`** (`ApplicationRunner`, `@ConditionalOnProperty(nadel.command=first-chairman)`, first of all
  runners, so the `dev` demo runners never run in a command): reads the arguments, runs `FirstChairman`, waits up to
  30 s until the outbox publications made since its start are processed (a count of `event_publication` rows, never
  their payload), then stops the JVM with `SpringApplication.exit`: exit code 0 done or nothing to do, 1 a step
  failed, 2 invalid arguments. Errors go to standard error as a problem code or an exception class name only.
- **`FirstChairman`** (the work): validates the arguments with the contract's `MemberInput` constraints (names 1..100,
  e-mail format and length) plus a required e-mail and a cooperative name of 1..200 characters (refusal
  `INVALID_ARGUMENTS` names the fields only). Cooperative id `UUID.nameUUIDFromBytes("nadel:cooperative:" + e-mail)`
  (trimmed, lower-cased), created by `tenancy.TenantProvisioning.ensure`. Then, with the cooperative bound, one
  transaction finds the active member with the e-mail (blind index) or creates it with `MemberCommands.create` (names,
  e-mail, `PAPER`) and gives it the Chairman role with `MemberRoleCommands.setRoles`; an active member with the e-mail
  without the Chairman role is refused (`MEMBER_NOT_CHAIRMAN`: the board gives roles, the command never does). Then the
  access: `ACTIVE` prints "already has access"; `INVITED` prints the invitation date and issues nothing unless `resend`;
  otherwise (and `INVITED` with `resend`) `MemberInvitations.issueForCommand` runs the invite (or resend) steps.
- **The link**: without a mailbox (a new cooperative has none) the link goes to the command's printer instead of a
  letter and is printed once to standard output by `println` (never a logger, the audit log or the outbox); with a
  mailbox the letter is sent as for the board. The HTTP invite never has a printer, so without a mailbox it still answers
  409 `DELIVERY_NOT_CONFIGURED`. A link printed but not marked sent (the last transaction failed) never works; the
  command then exits 1 and a re-run issues a new one.
- **Audit**: `MEMBER_CREATED`, `MEMBER_ROLES_CHANGED` and `MEMBER_INVITED` / `MEMBER_INVITE_RESENT` by the actor
  subject `system:first-chairman` without a member (the audit page shows «Система»); a printed link's entry has no
  `channels` field. A re-run that issues nothing writes nothing.

## What is here

- `MemberAccessProfileSource` implements `identity`'s `AccessProfileSource`: finds the active member for a token and
  links the Keycloak subject on the first sign-in. Order: the active member with this subject; otherwise, only when the
  token's e-mail is verified, the active member with this e-mail (case-insensitive: the blind index of the trimmed,
  lower-cased address is compared with `email_bidx`) if it has no subject yet, linked by a conditional update. A member already linked to another subject is refused (never relinked). Each step runs in its
  own short transaction; a unique-index violation (a concurrent first sign-in won the race, or an archived member still
  holds the subject) is answered by a fresh lookup by subject, so it ends as the linked member or "not linked", never
  as a 500. Logs tenant id, subject and member id only.
- `PermissionUnion`: the union of the permissions of all roles of a member. The Chairman base role always yields every
  `Permission` in code, so a permission added later needs no migration for the chairman. Unknown stored names grant
  nothing.
- `GET /members` (`MembersController`, `@PreAuthorize("hasAuthority('PERM_MEMBERS_VIEW')")`): a page of members sorted by
  last name, then first name. `q` (trimmed) is a case-insensitive substring of the last, first or middle name. The
  contacts are sealed, so they match only exactly, through their blind indexes (`MemberSearchTerms`): a `q` with `@` is
  the whole e-mail (trimmed, lower-cased; matched against `email_bidx`); a `q` that parses as a `PhoneNumber` is a full
  phone in any usual spelling (`member_phone_index.phone_bidx`); exactly four digits are the last four digits of any
  phone (`member_phone_index.last4_bidx`). A candidate the query does not stand for is bound as 32 zero bytes, which
  matches nothing; a blank `q` computes no index at all. A part of an e-mail or digits from the middle of a phone find
  nothing. Archived members only with `includeArchived=true`. One native, parameterised query (LIKE wildcards in `q`
  are escaped) plus one query for the roles of the whole page. Rows are `MemberSummary`: no notes, no postal address.
- `GET /members/{id}`: a caller always opens their own card; another card needs `MEMBERS_VIEW`, otherwise the answer is
  404 `NOT_FOUND` before any lookup, exactly like a missing id. With `MEMBERS_VIEW` the lookup runs under RLS, so a member
  of another cooperative is 404 too. `MemberDtoMapper` is the only place that shapes a member for output: `notes` is
  present only for a viewer with `MEMBERS_VIEW`, otherwise the key is absent; `telegramLinked` (feature 017) is the
  flag of `TelegramLinkFlags` on every card.

- Writes (`MemberCommands`, one transaction each, `@PreAuthorize("hasAuthority('PERM_MEMBERS_EDIT')")` on the controller):
  `POST /members` (201 with `Location`), `PUT /members/{id}`, `POST /members/{id}/archive`, `POST /members/{id}/restore`.
  A `PUT` first runs the login pre-check of feature 020 outside the transaction (above); an archive also switches access
  off and a restore leaves it (above).
  The cooperative comes from the caller's profile, never from the request. Archiving an archived member and restoring an
  active one are no-ops (the card is returned, no audit event); a `PUT` that changes nothing writes no event either.
  - `MemberData.from` validates and normalises: names trimmed and required (`REQUIRED`), phones through `PhoneNumber`
    (`PHONE_INVALID` on `phones[i]` / `viberPhone`, duplicates removed), e-mail trimmed and lower-case, blank optional
    fields become null, `DeliveryChannel.check` (`CHANNEL_CONTACT_MISSING` on `email` / `viberPhone`; since feature 017
    `TELEGRAM` needs no contact: the member links Telegram through the cooperative's bot, the username is an optional
    note; the database rule `member_channel_contact_ck` follows since V29.1).
  - E-mail is unique among active members: a pre-check by `email_bidx` plus a mapping of the unique index
    `member_tenant_email_bidx_uq` (on `(tenant_id, email_bidx)` of active members) give 409 `EMAIL_TAKEN` with
    `errors[{field: "email"}]`. Archived members free their e-mail; restore re-checks with the stored blind index.
  - Archive order of work (`MemberCommands.archive`): take the chairman lock (below), lock the member row `FOR UPDATE`
    (`MemberRepository.findByIdForUpdate`; 404 when missing or of another cooperative), and for a member who is not
    archived yet run `LastChairmanGuard.ensureNotLast`, `LastChairmanGuard.ensureNotLastWithAccess` and then every
    `MemberArchiveGuard`. The row lock serialises the
    archive with a change of owner that adds the member (it holds `FOR SHARE` through `MemberDirectory.lockActive`): the
    archive sees the committed owner and answers `MEMBER_OWNS_PLOT`, or the change finds the member archived and answers
    `MEMBER_ARCHIVED`; an archived current owner cannot result. The chairman lock comes before the row lock because
    `setMemberRoles` holds the chairman lock and then needs a share lock on the member row for a new role link; the
    opposite order would deadlock.
  - `LastChairmanGuard.ensureNotLast(tenant, member)` takes `pg_advisory_xact_lock(hashtextextended(tenant, 0))` in the
    transaction and refuses (409 `LAST_CHAIRMAN`) to take the only active chairman away. Archive and `setMemberRoles` use
    it. `ensureNotLastWithAccess` takes the same lock and refuses (409 `LAST_CHAIRMAN`) to turn off the access of the
    only active chairman whose access is on (`member_access` `INVITED` or `ACTIVE`, or linked without a row; a chairman
    who is `DISABLED` or never invited does not count). Disable and archive use it; a role change does not turn access
    off and keeps the plain rule.
  - `notes` need `MEMBERS_VIEW`: a `PUT` by an editor without it keeps the stored notes, a `POST` stores none.
  - Each change publishes an `audit.AuditEvent` (`MEMBER_CREATED`, `MEMBER_UPDATED`, `MEMBER_ARCHIVED`, `MEMBER_RESTORED`)
    built from `MemberSnapshot.diff(before, after)`; see the `audit` README.

- Roles, all `@PreAuthorize("hasAuthority('PERM_ROLES_MANAGE')")` on the controllers, one transaction each:
  `GET /permissions` (`PermissionCatalog`: the thirty-two permissions in enum order with English text), `GET /roles`
  (base roles first, then custom by name; `memberCount` counts active members only; Chairman always lists all thirty-two),
  `POST /roles`, `PUT /roles/{id}` (full replacement), `DELETE /roles/{id}`, and `PUT /members/{id}/roles` (replaces the
  member's whole role set). Permission changes apply on the next request: `AccessProfile` is resolved from the database on
  every request, nothing is cached.
  - Name: trimmed; empty is 400 `REQUIRED`, over 60 is 400 `TOO_LONG` (field `name`). Unique per cooperative ignoring case
    (`lower(name)` on both sides in the database, so Cyrillic works): a pre-check plus a mapping of the unique index
    `role_tenant_name_uq` give 409 `ROLE_NAME_TAKEN`.
  - Base roles: delete is 409 `BASE_ROLE_IMMUTABLE`; Chairman's permission set must stay all thirty-two (a PUT with the full
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

## Encrypted contacts (feature 014, ADR 0016)

- **Columns.** The names stay readable (search by part of a name, sort). The six contact fields are sealed values
  (`bytea`) of `privacy.PersonalDataCipher`: `phones_enc` (the phones as one JSON array, null when there are none),
  `email_enc`, `postal_address_enc`, `telegram_username_enc`, `viber_phone_enc`, `notes_enc`. `email_bidx` is the
  32-byte blind index of the e-mail; `member_phone_index` holds one row per distinct phone of a member: `phone_bidx`
  (the full phone) and `last4_bidx` (its last four digits), PK `(member_id, phone_bidx)`, RLS, the application may
  select, insert and delete (never update). V22 added them, the Java migration 22.1 filled them, V23 dropped the
  plaintext and moved the channel rule (`member_channel_contact_ck`) onto the ciphertext.
- **Field references.** Every sealed value is bound to `FieldRef("member", <column>, memberId)` where `<column>` is the
  `*_enc` column it is stored in (`ContactField`), the same in the migration, the demo callback and the application,
  so a value opens only in its own row and column of its own cooperative.
- **Normal forms** (`PhoneIndexValues`, shared by all three writers): e-mail `strip().toLowerCase(Locale.ROOT)`; phone
  the international form of `PhoneNumber` (a stored value that does not parse is indexed as stored); last 4 = the last
  four digits of the normalised phone. `ContactCipher` holds the shared sealing and indexing helpers over either
  cipher (owner keyring or runtime).
- **Runtime.** `Member` maps only the `byte[]` columns. `MemberCrypto` is the only class that opens (`open` →
  `OpenedContacts`, or just the phones and e-mail for the list and `MemberDirectory.contacts`), seals (`seal` re-seals
  only the fields that changed and keeps `email_bidx` in step) and rewrites `member_phone_index` (delete + insert, after
  the member row is flushed, only when the phones changed). `MemberDtoMapper` and `MemberSnapshot` work on the opened
  contacts. A value that does not open is
  `PersonalDataUnreadableException` (500 `PERSONAL_DATA_UNREADABLE`): the card and every save of that member fail
  rather than show or write back an empty field.
- **Audit values.** `MemberAuditPublisher` (the only place members build audit events of a member, for the endpoints
  and the Excel import alike) generates the event id first and seals the old and new values of `phones`, `email`,
  `postalAddress`, `telegramUsername`, `viberPhone` and `notes` at `FieldRef("audit_entry", "changes.<field>.old|new",
  eventId)` (snake case: `changes.postal_address.old`), as base64 with `FieldChange.sealed`; names, the channel and
  `archived` stay plain. So the outbox and the audit log never hold a contact in the clear; `GET /audit` shows the
  values only to readers with `MEMBERS_VIEW` (see the audit README).
- **Java migration 22.1** (`EncryptMemberContactsMigration`, a `JavaMigration` bean that Spring Boot hands to Flyway):
  on Flyway's owner connection and in its transaction, through `privacy.OwnerKeyring` (no JPA), it seals every
  member's plaintext contacts, fills `email_bidx` and `member_phone_index`, in pages of 500 members by id. Idempotent
  (a field that has its ciphertext is kept, index rows `on conflict do nothing`), fixed checksum, counts-only log
  (`Member contacts encrypted: N of M members sealed, K phone index rows written`). V23's guard refuses to drop
  plaintext that has no ciphertext, so a missing migration bean stops the upgrade instead of losing contacts.
- **Demo contacts** (`DemoContactsCallback`, a Flyway `Callback` bean registered only when `spring.flyway.locations`
  contains `db/demo`: the `dev` profile and the integration tests). `R__demo_data.sql` inserts the demo members with
  names, archive state and the `PAPER` channel only (and leaves the channel of an existing row alone); right after that
  script (`AFTER_EACH_MIGRATE` of `R__demo_data.sql`, inside its transaction) the callback reads
  `db/demo/demo-contacts.json` and, through `OwnerKeyring`, seals each listed member's contacts, sets `email_bidx`, the
  delivery channel (in the same statement as the contacts, for the channel rule) and the phone index. Every run writes
  the same contacts again; a listed member that does not exist is skipped. The demo users sign in by e-mail through
  `email_bidx`.

Depends on `identity`, `problem`, `audit`, `privacy` and `tenancy` (`TenantContext`, and `TenantProvisioning` for the
first-chairman command).
