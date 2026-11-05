# 020. Ready for real use: inviting members, tariff fixes, small review items

Input for `/feature-pipeline`. Requires 001–019. The cooperative «Надія» is about to work in the system for real. This
feature closes what the owner's reviews of 009–019 found and what stops real use:
- the board cannot give a member access to the system without the platform owner editing Keycloak by hand;
- a tariff created by mistake cannot be removed;
- a few screens confuse a non-technical treasurer.



## Context for the pipeline

Read together with:
- `CLAUDE.md`;
- the READMEs of `members`, `identity`, `tariffs`, `ledger` (payments), `notifications` (mail and the bot);
- ADRs 0003 (Keycloak), 0009 (permissions), 0016 (personal data), 0018 (cooperative credentials);
- `docs/features/backlog/deployment.md` section 4 (the first chairman);
- `docs/help/` (every changed screen updates its help pages and shots, 019).

General rules as in earlier features:
- contract-first;
- Flyway only;
- RLS;
- permissions, not role names;
- Problem Details;
- i18n;
- audit;
- the verification levels and the help rule of `CLAUDE.md`.

## 1. Inviting a member into the system

**Now.** A member gets access only when the platform owner creates a Keycloak user by hand over an SSH tunnel: the same
e-mail and the `tenant_id` attribute. The member's card is then linked on the first sign-in by the verified e-mail.
There is no self-registration, and that stays so.

**New.** The button «Запросити в систему» on the member card, for the new permission `MEMBERS_INVITE` (Chairman).

**Sending the invitation:**
- The backend creates the Keycloak user through the Admin API, with a service account of a confidential client that
  has only the realm roles needed to manage users of the `nadel` realm. The client secret comes from the environment.
  The user gets:
  - the member's e-mail as username and e-mail;
  - `tenant_id` of the cooperative;
  - the required action «set a password»;
  - and is enabled.
- **Delivery:**
  - by e-mail through the cooperative's own mailbox (017): a letter with a one-time link «Задайте пароль» (Keycloak
    execute-actions link, valid 72 hours);
  - if the member has no e-mail but a linked Telegram (017), by the bot, after an e-mail is added: the login needs
    one;
  - a member with neither: the button is disabled with the reason.
- **The card shows the access status:**
  - «Не запрошено»;
  - «Запрошено {дата}, чекає на пароль»;
  - «Має доступ, останній вхід {дата}»;
  - «Доступ вимкнено».

  It also offers «Надіслати ще раз» (a new link, the old one invalid) and «Вимкнути доступ» (disables the Keycloak
  user; the card and data stay).

**Rules:**
- Archiving a member disables their Keycloak user. Restoring does not enable it again; the board invites again.
- A changed e-mail on a member with access updates the Keycloak user's e-mail and marks it unverified, with a letter to
  the new address.
- Co-owners are invited one by one, each with their own e-mail.
- An e-mail already used by a Keycloak user of another cooperative: refused with `EMAIL_IN_OTHER_COOPERATIVE`, without
  saying which one.
- A user that already exists for this cooperative and the same e-mail is linked, not duplicated.
- **The first chairman of a new cooperative** uses the same code through the server command of
  `deployment.md` section 4. It is one code path, tested once.
- **The demo:** the invite flow works against the dev Keycloak, and the letters arrive in Mailpit.

**Audit:**
- `MEMBER_INVITED`;
- `MEMBER_INVITE_RESENT`;
- `MEMBER_ACCESS_DISABLED`.

Never the link and never a password.

**Security:**
- the service account cannot read or change other realms;
- calls are made only from the backend;
- the operator role stays without database access (ADR 0008).

ADR 0019 "Member invitations" records the client, its roles and the secret handling.

## 2. Tariffs

1. **Deleting a tariff.** A tariff with no charge from any of its versions can be deleted as a whole, with a
   confirmation. The delete is audited as `TARIFF_DELETED`. A tariff used by a charge gets «Архівувати»:
   - it stops applying from a chosen date (a last version end, not a new rate);
   - it disappears from the active list;
   - it stays readable with its history.

   The rule «a tariff always has a version» stays for active tariffs.
2. **The overlap warning says why.** «Тарифи одного виду нарахування не можуть діяти на ті самі ділянки. Ці ділянки
   зараз не нараховуються. Змініть ділянки одного з тарифів або оберіть інший вид нарахування.» Then the list of
   plots, as now.
3. **The new tariff form:**
   - under «Вид нарахування» it lists the active tariffs of the chosen type with their plots, so an overlap is seen
     before saving;
   - a link «Немає потрібного виду? Види нарахувань»;
   - inactive seeded types (for example «Вода») show in the list as «(неактивний — увімкнути)», which activates the
     type with `TARIFFS_MANAGE`.

## 3. Payments

1. **Payer by default.** When the plot has exactly one current owner, that owner is preselected as the payer in the
   payment form. With several owners, the field asks to choose, with the owners listed first.

   The owner confirms this at review.
2. **The allocation dialog:**
   - the total «Розподілено X з Y» stays on one line;
   - a line above the open amount says «Більше, ніж залишок {сума}».

## 4. Small items found in reviews

1. **Duplicate attachments.** The expense form warns «Цей файл уже додано» when the same file (name, size and content
   hash) is added twice.
2. **e2e 010 clean-up.** The readings e2e removes its test plot even when it fails between the submission and the
   approval (check whether 014 already fixed it; if so, close it).
3. **The electrician's demo role.** e2e specs must not leave extra permissions on the demo role «Електрик»: the
   clean-up restores the role.
4. **Flaky e2e.** `acceptance.spec.ts:513` (a member from creation to archive) passed only on a retry in the full run of
   PR #68. Make it wait for the member list to refresh after archiving before it searches for «Nothing found»; prove it
   with 10 consecutive local runs.

## 5. Help and guides

- Every changed screen updates its help pages in both languages and their shots (019 rule):
  - the member card (invitations and access);
  - the tariff pages;
  - the payment form.
- The owner manual:
  - the Keycloak service client for invitations, its secret in the server environment;
  - `infra/stand` and `deployment.md`, the new variables.
- The help «Початок роботи» for the board explains how members get access.

## 6. Feedback from the test stand

None yet. The board's feedback from the stand becomes a follow-up feature.

## 7. Out of scope

- self-registration;
- invitation by SMS;
- single sign-on with Google or Apple;
- the platform operator's screens;
- the payroll feature;
- the mobile apps.

## 8. Acceptance criteria

- [ ] Invite, resend, disable and the archive rule work against Keycloak in tests (Testcontainers Keycloak, or the
      dev realm in e2e). The status on the card follows the Keycloak user. The first-chairman command uses the same
      code. No link or password in logs or audit.
- [ ] `EMAIL_IN_OTHER_COOPERATIVE` and the linking of an existing user are tested. The service account's permissions
      are limited to the realm and tested.
- [ ] A tariff with no charges is deleted. A used tariff is archived from a date and stops charging from it (a month
      run test). The overlap warning and the form hints are as in section 2.
- [ ] The payer default and the dialog items of section 3.
- [ ] Section 4 items.
- [ ] Help pages and shots updated. The help completeness check passes.
- [ ] Permissions (`MEMBERS_INVITE`) with allowed and denied tests. Audit as in section 1.
- [ ] `make check` is green. e2e:
      - the chairman invites the demo member without access, the letter arrives in Mailpit, the member sets a
        password and signs in (`@smoke`);
      - the chairman deletes an unused tariff;
      - the treasurer enters a payment with the payer preselected.
