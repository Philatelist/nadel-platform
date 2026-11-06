# 0020. Member invitations: a confidential Keycloak client with three realm-management roles, a backend single-use token, the password set through the Admin API, a synchronous delivery SPI, a third cross-tenant `SECURITY DEFINER` function, stored access state, the first-chairman command

**Status:** accepted (2026-11); introduced in phase 4 by spec 020 (`context/spec/020-go-live-polish/`).

## Context
Until feature 020 a member could sign in only after the platform owner created an account in the Keycloak admin
console by hand (owner manual, chapter 3) and the first chairman of a cooperative was added with SQL. Both do not
scale and do not fit a board that has no access to Keycloak. The board must invite a member from the member card, the
member must set a password without any Keycloak page, and a new cooperative must get its first chairman from one
command on the server.

Constraints from earlier decisions: Keycloak keeps identity only and the `tenant_id` claim (ADR 0003, ADR 0009); every
cooperative has its own mailbox and the platform has no mail service (ADR 0018); the application connects without
`BYPASSRLS` and a cross-tenant read needs a `SECURITY DEFINER` function with its own ADR (ADR 0018 allowed two and said
any further one needs one); cross-module side effects go through the outbox (ADR 0007), which persists the event.

## Decision

### Keycloak client and secret
- The realm has a **confidential** client `nadel-backend-admin` with only the service account enabled (no browser flow,
  no redirect URIs). Its service account has exactly three roles of the `realm-management` client: `manage-users`,
  `view-users`, `query-users`. Nothing else (no realm administration, no client management).
- The backend gets a token with the client-credentials grant (`nadel.keycloak.admin.*`). The secret comes only from the
  environment (`NADEL_KEYCLOAK_ADMIN_CLIENT_SECRET`). The realm file holds a dev value; on the stand `stand-init.sh`
  rotates the secret and writes it to `.env` (mode 600), after which the backend is restarted.
- The backend creates users with the `tenant_id` attribute, sets the password, and disables or enables them. The
  public clients and the PKCE rules of the realm do not change.

### Invitation by a backend token
- An invitation is a row in `member_invitation` (RLS `tenant_isolation`). The token is 32 random bytes (URL-safe
  Base64); only its SHA-256 hash is stored. Valid for 72 hours (`nadel.invitations.ttl`), single use, and a newer
  invitation of the same member revokes older open ones. `UPDATE` is granted only on `sent_at`, `used_at`, `revoked_at`.
- The link opens the **set-password page of the app** (`nadel.web.url`), which is public. The backend checks the token
  and sets the password through the Keycloak Admin API; the password stays in memory for that call and is never logged,
  stored or put in an event. The member then signs in on the normal Keycloak page. Keycloak never sends mail.
- The first sign-in links the account to the member as before (subject, then verified e-mail; ADR 0009).

### Delivery: a synchronous SPI, not the outbox
- The invitation link is delivered by a synchronous port, `members.InvitationDelivery`, implemented by `notifications`
  with the cooperative's own mailbox (ADR 0018). It is called after the invitation row is written; on failure a
  compensating transaction revokes the row and the API answers 502 `INVITATION_NOT_SENT`.
- The outbox is not used because it persists the event payload, and the link (a bearer secret) would sit in
  `event_publication` readable. With the SPI the link exists only in memory, in the mail and as a hash.
- Without a usable mailbox (the first chairman of a new cooperative) the sink is the command's stdout.

### The third cross-tenant function
- The set-password page has no token with `tenant_id`, so the backend cannot set the tenant before it finds the
  invitation. `members_invitation_tenant(bytea) RETURNS uuid` is `SECURITY DEFINER` (owner `nadel_owner`), fixed
  `search_path`, `EXECUTE` only for `nadel_app`, `REVOKE` from `PUBLIC`. It takes the token hash and returns the
  cooperative id or null, never a row. Afterwards everything runs under the normal tenant transaction. This is the
  third such function (ADR 0018 has two); each has an isolation test.

### Access state
- `member_access` (one row per member, RLS) stores `state`: `NONE`, `INVITED`, `ACTIVE`, `DISABLED`, with the dates of
  the invitation, activation and disabling. It is the board's view of the account and is shown on the member card;
  Keycloak is not asked on every read.
- Archiving a member disables the Keycloak user and sets `DISABLED`; restoring does not re-enable it, the board
  invites again. A disabled or invited member is refused in `MemberAuthenticationConverter`.
- Invite, resend, disable and enable need the permission `MEMBERS_INVITE` (the thirty-second, Chairman only by
  default) and are audited without the link or the password.
- A cooperative never locks its board out: nobody turns off their own access (409 `OWN_ACCESS`), and neither the
  disable action nor the archive turns off the access of the last active chairman whose access is on (`INVITED` or
  `ACTIVE`; 409 `LAST_CHAIRMAN`, under the same per-cooperative advisory lock as the other last-chairman checks).

### Rate limit of the public endpoints
- `inspect` and `accept` are public, so each is limited: at most 5 requests within 15 minutes from one client address
  and for the e-mail of one invitation (`nadel.invitations.rate-limit.*`; the e-mail is found from the token hash and
  counted by the cooperative and its blind index; a token of no link meets only the address limit). Over a limit the
  answer is 429 `TOO_MANY_REQUESTS` before anything else runs, and the refused request is not counted.
- The client address is `request.getRemoteAddr()`. Behind Caddy, `server.forward-headers-strategy: native` lets
  Tomcat's `RemoteIpValve` take it from `X-Forwarded-For`, but only from a proxy on a private or loopback address; the
  application never reads that header itself.
- The counters are a bounded sliding log in the memory of the instance (at most `max-keys` keys, the least recently
  used dropped first). No new dependency and no table: one backend instance runs per stand. With several instances
  each counts on its own, so the effective limit is multiplied by the number of instances; a shared store (the
  database or a cache) is the change to make then. A restart clears the counters.
- A per-e-mail limit lets whoever holds a link delay that member for one window by repeating its requests; links
  cannot be guessed (256-bit tokens), so the address limit is the main guard against floods.

### First chairman
- `infra/stand/first-chairman.sh` runs the backend jar once (`docker compose run --rm --no-deps`, profile `command`,
  no web server, no published port) with `--nadel.command=first-chairman`. The cooperative id is derived
  deterministically from the e-mail, so the command is idempotent. It creates the cooperative, seeds the base roles,
  creates the chairman and invites them; the link is printed to stdout once, because the new cooperative has no mailbox.
  `--resend` issues a new link. A non-zero exit code reports any failure. It is not tied to the `prod`/`staging`
  profiles: the safety is that it is an explicit operator action with no HTTP surface.

### Tests
- Keycloak behaviour is tested against a real Keycloak started as a Testcontainers `GenericContainer` with the realm
  file. No new dependency (no Keycloak Testcontainers module).

## Consequences
- A board invites members itself; the platform owner no longer creates accounts in the Keycloak console.
- The backend holds a credential that can create and disable users in the realm; it is a secret in the environment,
  rotated by `stand-init.sh`, limited to three roles. A leak of it exposes accounts of all cooperatives, so it is never
  logged and never given to clients.
- A mail failure at invitation time is shown to the board at once and nothing is left half-done.
- A link that is lost cannot be recovered (only its hash exists): resend issues a new one.
- A third function is an exception to "RLS everywhere"; any further one needs its own ADR.
- Operators must know the state of the `Keycloak` account is a copy: a user disabled by hand in Keycloak is refused at
  sign-in by Keycloak itself, while the card shows the stored state.

## Alternatives considered
- **Keycloak `execute-actions-email` through the realm SMTP:** one platform-wide sender instead of the cooperative's own
  mailbox (ADR 0018), realm SMTP settings per environment, no link for a cooperative without a mailbox.
- **A Keycloak SPI extension (a custom authenticator or REST endpoint):** a Java module to build, version and deploy
  with Keycloak upgrades, for what the Admin API already does.
- **The tenant inside the token (a signed JWT instead of a random token):** needs a signing key and revocation list, and
  single use would still need a table; a random token with a hash and one narrow function is smaller.
- **Sending the invitation through the outbox:** persists the link in `event_publication` (see above).
- **Reusing the Keycloak admin user for the backend:** far wider rights than creating users.

## Non-goals
- Self sign-up, social login, two-factor set-up from the app.
- Invitation by Telegram or Viber (e-mail and the command's stdout only).
