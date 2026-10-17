# Functional Specification: Members and Access Control

- **Topic:** A cooperative's list of members, the admin roles that decide who may do what, linking each person's sign-in to their member record, and a record of every admin change.
- **Status:** Draft
- **Author:** Alex Usov
- **Source:** `docs/features/002-members-access.md` (roadmap phase 1, feature 1 of 4)

---

## 1. Overview and Rationale (The "Why")

Today a cooperative in Nadel has no people in it: anyone who signs in is trusted only by a label attached to
their sign-in account, and there is no way to record who the members are, how to reach them, or who on the
board may change what. Every later feature — plots and their owners, charges, payments, invoices — needs a
reliable list of members and a reliable answer to "is this person allowed to do this?".

This feature gives each cooperative:

- **Its people.** The chairman (or anyone they trust with it) keeps the member list: names, phones, email,
  postal address and the channel each member prefers for invoices.
- **Configurable admin roles.** Besides the three base roles — Chairman, Treasurer and Member — the cooperative
  can create its own roles, such as "Secretary", from a fixed list of permissions, and give several roles to one
  person. Board duties in real cooperatives rarely match a fixed set of job titles.
- **Sign-in tied to a member.** When a person signs in, Nadel recognises them as a specific member of their
  cooperative and gives them exactly what that member's roles allow.
- **Accountability.** Every change an admin makes to members, roles or role assignments is recorded with who did
  it, when, and what changed — the first step towards the product's promise that no dispute is unanswerable.

**Success looks like:** a chairman can set up the cooperative's people and roles in one evening; every person
who signs in sees only what their roles allow; and for any change to a member or a role, the audit log answers
"who changed this, when, and from what to what".

---

## 2. Functional Requirements (The "What")

### 2.1 Permissions

- The product offers a fixed list of eight permissions. Each permission unlocks a group of actions:

  | Permission (as shown on screen) | Allows |
  |---|---|
  | View members | See all members and their contact details and notes |
  | Edit members | Add, edit, archive and restore members |
  | Manage roles | Create, edit and delete custom roles; change permissions of base roles; assign roles to members |
  | View audit log | Read the audit log |
  | View plots | See all plots with details (used from the plots feature) |
  | Edit plots | Add, edit and archive plots (used from the plots feature) |
  | Edit ownership | Add, change and correct plot ownership (used from the ownership feature) |
  | Run import | Run the Excel import (used from the import feature) |

  All eight exist from now on, so roles can be configured once even though the last four have no screens yet.
  - **Acceptance Criteria:**
    - [ ] When a person with "Manage roles" opens the Roles screen, then they see exactly these eight permissions, each with its name and a one-line description, in Ukrainian by default.
    - [ ] When a person without "Manage roles" tries to open the Roles screen by its address, then they see a "You don't have access to this page" message instead of the screen.

### 2.2 Base roles

- Every cooperative has three base roles from the start: **Chairman**, **Treasurer** and **Member**. Base roles
  cannot be deleted or renamed.
  - Chairman always has every permission; this cannot be reduced.
  - Treasurer starts with View members, View plots and View audit log.
  - Member starts with no permissions and sees only their own data.
- A person with "Manage roles" may change the permissions of Treasurer and Member (and of any custom role).
- At least one active (not archived) member must always hold Chairman. The product refuses to take Chairman away
  from, or archive, the last such member.
  - **Acceptance Criteria:**
    - [ ] When the chairman opens the Roles screen of a new cooperative, then they see Chairman, Treasurer and Member marked as base roles, with no delete button and no editable name.
    - [ ] When the chairman views the Chairman role, then all eight permissions are shown ticked and cannot be unticked.
    - [ ] When the chairman unticks "View audit log" for Treasurer and saves, then a treasurer who signs in afterwards no longer sees the Audit log item in navigation.
    - [ ] Given only one active member holds Chairman, when someone tries to remove Chairman from that member, then the change is refused with the message "The cooperative must keep at least one chairman."
    - [ ] Given only one active member holds Chairman, when someone tries to archive that member, then the archive is refused with the message "The cooperative must keep at least one chairman."
    - [ ] Given two active members hold Chairman, when Chairman is removed from one of them, then the change succeeds.

### 2.3 Custom roles

- A person with "Manage roles" can create a role with a name, an optional description and any set of
  permissions, for example "Secretary" with View members, Edit members, View plots, Edit plots and Edit ownership.
- Role names are unique within the cooperative, ignoring upper/lower case, and are 1 to 60 characters long.
- A member may hold several roles; they get every permission of every role they hold.
- A custom role can be edited (name, description, permissions) and deleted. Deleting a role that is still given
  to at least one member is refused.
  - **Acceptance Criteria:**
    - [ ] When a person with "Manage roles" creates "Secretary" with View members and Edit members, then the role appears in the roles list with those two permissions.
    - [ ] When someone creates a role named "treasurer" (or the name of any existing role in any letter case), then they see: "A role with this name already exists."
    - [ ] When someone saves a role with an empty name, then they see: "Name is required."
    - [ ] Given a member holds Treasurer and Secretary, when they sign in, then they can both view the audit log (from Treasurer) and edit members (from Secretary).
    - [ ] Given "Secretary" is given to one member, when someone tries to delete it, then they see: "This role is assigned to members and cannot be deleted." and the role stays.
    - [ ] Given "Secretary" is given to no one, when someone deletes it, then it disappears from the roles list.

### 2.4 Signing in as a member

- People keep signing in through the existing sign-in page. When a signed-in person of a cooperative uses Nadel,
  the product finds the active member of that cooperative whose email matches the sign-in email (ignoring
  upper/lower case) and from then on treats the person as that member, with the permissions of that member's roles.
- Once a person has been matched to a member, they stay matched to that member even if the member's email is
  later edited.
- A signed-in person who matches no active member (no member with that email, or the member is archived) is
  not let in and sees a clear message.
- The platform operator is not a member of any cooperative and never sees cooperative data; this feature does not
  change that.
- Creating sign-in accounts and sending invitations are not part of this feature.
- The demo cooperative's test users each match a demo member: the chairman, treasurer and member test users hold
  Chairman, Treasurer and Member; the electrician test user holds Member plus a demo custom role "Electrician"
  that has no permissions yet. The other cooperative's test user matches a member of that other cooperative.
  - **Acceptance Criteria:**
    - [ ] Given a member with email "Treasurer@Demo.local" holds Treasurer, when the person signed in as treasurer@demo.local opens Nadel, then they see the member list.
    - [ ] When a person signs in whose email matches no member of their cooperative, then they see: "Your account is not linked to a member of this cooperative. Please contact your chairman." and no cooperative data.
    - [ ] Given the member matching a person's email is archived, when that person signs in, then they see the same "not linked" message.
    - [ ] Given a person has signed in once and been matched, when an admin changes that member's email and the person signs in again, then they are still recognised as the same member.
    - [ ] When the platform operator signs in, then they see that they belong to no cooperative and see no members.

### 2.5 What an ordinary member sees

- A person whose roles give no "View members" permission sees only their own member card, read-only, and
  nothing about other members. Opening another member's card by its address shows "not found", exactly as for a
  member that does not exist, so they cannot tell other members exist. The member list is not available to them.
- Notes about a member are visible only to people with "View members", including on the member's own card.
  - **Acceptance Criteria:**
    - [ ] Given a person holds only Member, when they open My account, then they see their own name, phones, email, postal address and preferred channel, with no edit buttons and no notes.
    - [ ] Given a person holds only Member, when they open another member's card by its address, then they see "Not found".
    - [ ] Given a person holds only Member, when they open the member list by its address, then they see "You don't have access to this page" and no members' names.
    - [ ] Given a person holds only Member, when they look at navigation, then there are no Members, Roles or Audit log items.

### 2.6 Member records

- A member record holds:
  - last name and first name (required, up to 100 characters each), middle name (optional);
  - any number of phone numbers, including none;
  - email (optional);
  - postal address (optional free text, up to 500 characters);
  - preferred invoice channel: Email, Telegram, Viber or Paper (default Paper);
  - Telegram username and Viber phone (optional; used by later invoice delivery);
  - notes (optional, up to 2000 characters).
- Phone numbers are stored in international format. A Ukrainian number typed as `0XXXXXXXXX` (10 digits) or
  `380XXXXXXXXX` is turned into `+380XXXXXXXXX`; a number already in international format (`+` and 8 to 15 digits)
  is kept; spaces, dashes and brackets are ignored. Anything else is rejected. The Viber phone follows the same rules.
- Two active members of the same cooperative cannot share an email (ignoring upper/lower case). An archived
  member's email can be reused.
- The preferred channel needs its matching contact: Email needs an email, Telegram needs a Telegram username,
  Viber needs a Viber phone. Paper needs nothing.
- Tax numbers, passport data and dates of birth are never asked for or stored.
- Members are archived, never deleted. Archived members are hidden from the list by default, can be shown with a
  filter, cannot sign in, and keep their history. An archived member can be restored, unless an active member now
  uses the same email.
  - **Acceptance Criteria:**
    - [ ] When an editor saves a member with phone "067 123 45 67", then the card shows "+380671234567".
    - [ ] When an editor saves a member with phone "12345", then they see next to the phone field: "Enter a phone number like +380671234567 or 0671234567."
    - [ ] When an editor saves a member without a last name, then they see next to the field: "Last name is required." and nothing is saved.
    - [ ] Given an active member already has email ivan@example.com, when an editor saves another member with "IVAN@example.com", then they see: "Another active member already uses this email."
    - [ ] When an editor sets the preferred channel to Telegram without a Telegram username, then they see: "Telegram needs a Telegram username." (and likewise "Email needs an email." and "Viber needs a Viber phone.")
    - [ ] When an editor creates a member without choosing a channel, then the card shows the preferred channel "Paper".
    - [ ] When an editor archives a member, then the member disappears from the default list and appears when the "Show archived" filter is on, marked as archived.
    - [ ] When an editor restores an archived member, then the member is back in the default list.
    - [ ] Given an archived member's email is now used by an active member, when an editor tries to restore the archived member, then they see: "Another active member already uses this email."
    - [ ] When an editor looks at the member form, then there are no fields for tax number, passport or date of birth.

### 2.7 Members list

- People with "View members" see a list of members showing name, phones, email, preferred channel and roles.
- They can search by part of a name, a phone or an email; switch "Show archived" on and off; and move between
  pages of 20 members, sorted by last name then first name.
- The list shows a loading state, an empty state ("No members yet" or "Nothing found") and an error state with a
  retry button.
  - **Acceptance Criteria:**
    - [ ] Given the demo cooperative, when the treasurer opens Members, then they see the first 20 members sorted by last name, each with phones, email, preferred channel and roles.
    - [ ] When the treasurer types "067" in search, then only members with a phone containing those digits are shown.
    - [ ] When the treasurer searches for a name that no member has, then they see "Nothing found".
    - [ ] When the treasurer moves to the next page, then they see the next members in order and the page indicator changes.
    - [ ] Given the list cannot be loaded, when the treasurer opens Members, then they see an error message with a "Try again" button.

### 2.8 Member card

- Anyone with "View members" can open a member's card; with "Edit members" they can edit it and archive or
  restore the member; with "Manage roles" they can change which roles the member holds.
- People without "Edit members" see the card read-only; people without "Manage roles" see the roles but cannot
  change them.
  - **Acceptance Criteria:**
    - [ ] Given the treasurer (View members only), when they open a member card, then they see all fields including notes, with no Edit, Archive or role-change controls.
    - [ ] Given a person with Edit members, when they change a member's postal address and save, then the card shows the new address and a "Saved" confirmation.
    - [ ] Given a person with Manage roles, when they give a member the Secretary role and save, then the card lists Secretary among the member's roles.
    - [ ] Given a person with Edit members but not Manage roles, when they open a card, then they can edit fields but cannot change roles.

### 2.9 Audit log

- Every add, edit, archive and restore of a member, every create, edit and delete of a role, and every change of
  a member's roles is recorded with: who did it (their member name), when, what was done, what it was done to,
  and for each changed field its old and new value.
- Entries can never be changed or removed by anyone.
- People with "View audit log" see the log newest first, 50 entries per page, filtered by kind of record (member,
  role), a specific record, the person who made the change, and a date range.
- An entry appears in the log shortly after the change (within a few seconds); a change is never lost from the
  log even if recording it fails at first.
  - **Acceptance Criteria:**
    - [ ] When an editor changes a member's phone from +380671234567 to +380501112233, then within 5 seconds the audit log shows an entry with the editor's name, the time, "Member updated", the member's name, and the phone's old and new values.
    - [ ] When someone gives a member the Secretary role, then the audit log shows a "Roles changed" entry for that member with the old and new list of roles.
    - [ ] When someone creates, edits or deletes a custom role, then the audit log shows a matching "Role created", "Role updated" or "Role deleted" entry.
    - [ ] When someone archives or restores a member, then the audit log shows "Member archived" or "Member restored".
    - [ ] When the treasurer filters the log by one member, then only entries about that member are shown.
    - [ ] When the treasurer filters the log by a date range, then only entries made within that range (inclusive) are shown.
    - [ ] Given a person holds only Member, when they open the audit log by its address, then they see "You don't have access to this page".
    - [ ] When a person views the audit log, then there is no way to edit or delete an entry.

### 2.10 My account

- Every signed-in member has a My account page: their own member card (read-only), their roles, and the
  permissions those roles give them in plain words.
- My account is the first page after sign-in. The demo map is no longer in navigation (it returns with the map
  feature).
  - **Acceptance Criteria:**
    - [ ] When the treasurer signs in, then they land on My account and see their name, the role "Treasurer" and the permissions "View members", "View plots" and "View audit log".
    - [ ] When any person looks at navigation, then there is no Map item.

### 2.11 Navigation and language

- Navigation shows only the items the person's permissions allow: Members (View members), Roles (Manage roles),
  Audit log (View audit log), and My account (everyone). Hiding an item is a convenience; the product refuses the
  action itself regardless of what the screen shows.
- All new screens are in Ukrainian by default with English available, and each has loading, empty and error states.
  - **Acceptance Criteria:**
    - [ ] When the chairman signs in, then navigation shows My account, Members, Roles and Audit log.
    - [ ] When the treasurer signs in, then navigation shows My account, Members and Audit log, but not Roles.
    - [ ] When a person switches the language to English, then every label, message and permission name on the new screens is in English.
    - [ ] Given a person without "Edit members", when they try to save a change to a member by any means, then the change is refused with "You don't have permission to do this." and the member is unchanged.

### 2.12 Isolation between cooperatives

- Members, roles, role assignments and audit entries of one cooperative are never visible to, or changeable by,
  people of another cooperative.
  - **Acceptance Criteria:**
    - [ ] Given a person of the other cooperative with "View members", when they open the member list, then they see only their own cooperative's members.
    - [ ] When a person of the other cooperative opens the address of a demo cooperative member's card, then they see "Not found".

### 2.13 Demo data

- The demo cooperative has about 35 members with realistic Ukrainian names and contacts, the base roles given to
  the demo users, and a custom role "Secretary" given to one member, so every screen has something to show.
  - **Acceptance Criteria:**
    - [ ] When the chairman of the demo cooperative opens Members, then they see between 30 and 40 members across two pages.
    - [ ] When the chairman opens Roles, then they see Chairman, Treasurer, Member, Secretary and Electrician.

---

## 3. Scope and Boundaries

### In-Scope

- Member list, member card, adding, editing, archiving and restoring members.
- The eight permissions, the three base roles, custom roles, giving roles to members.
- Recognising a signed-in person as a member of their cooperative by email.
- The audit log for members, roles and role assignments, and its screen.
- My account, permission-based navigation, removing the map from navigation.
- Demo data and matching demo sign-in users.
- An automated browser check: the treasurer opens the member list and a member card; an ordinary member cannot
  reach the member list.

### Out-of-Scope

- Plots and the plots registry (feature 003), ownership (feature 004), Excel import (feature 005) — only their
  permissions are created now.
- Members editing their own details (self-service).
- Creating sign-in accounts and sending invitations.
- One person being a member of several cooperatives.
- Delivering notifications or invoices by email, Telegram or Viber.
- The Electrician base role and meter permissions (dropped from base roles; meters come in a later phase).
- Mobile app changes.

---

## 4. Assumptions & Decisions

User answers (Phase 1):

- **Base roles are Chairman, Treasurer and Member only.** Electrician is not a base role (the feature document's
  table is followed over older documents). Product and design documents that listed Electrician as a base role are
  updated to match. The electrician demo user holds Member plus a demo custom role "Electrician" with no
  permissions yet.
- Testing runs locally against real local infrastructure (Docker available).

Assumptions made by the pipeline (recommended answers, not asked):

1. The record and screens are called "Member" throughout.
2. A person without "View members" gets "You don't have access to this page" for the member list, and "Not found"
   for any other member's card; their own card is readable through My account and by its address.
3. "Manage roles" is what allows changing the permissions of the Treasurer and Member base roles; Chairman's
   permissions are fixed to all.
4. Phone numbers: Ukrainian local forms are converted to `+380…`; other countries' numbers are accepted only in
   international form; no other formats are guessed.
5. Email uniqueness ignores letter case and applies only among active members.
6. Page sizes: 20 members per page, 50 audit entries per page.
7. Field length limits: names 100, postal address 500, notes 2000, role name 60, role description 500 characters.
8. Once a person is matched to a member, the match follows the member (not the email); an archived member's
   person is refused even if matched earlier.
9. An audit entry names the actor by member; changes made by the system itself (demo data, setup) are not audited.
10. Seeding base roles for cooperatives that will be created later is prepared now and used when cooperative
    onboarding is built; there is no "create cooperative" screen in this feature.
11. My account replaces the map as the first page after sign-in.
12. Edits to the agent rules file (`CLAUDE.md`) that this decision implies (base role list, test users) are proposed
    to the repository owner, not made by this feature.

---

## Change Log

_Dated amendments made after the spec was first written. Leave empty until the first amendment._
