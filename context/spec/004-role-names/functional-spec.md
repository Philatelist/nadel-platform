# Functional Specification: Role Names

- **Topic:** The chairman can rename every role of the cooperative, the three base roles included, and return a renamed base role to its standard name.
- **Status:** Draft
- **Author:** Alex Usov
- **Source:** `docs/features/004-role-names.md` (follow-up to 002 Members and access control)

---

## 1. Overview and Rationale (The "Why")

Cooperatives use different words for the same job: one calls its money keeper "Скарбник", another "Казначей", a
third "Бухгалтер". Today the cooperative can change everything about a role except the names of the three base roles
(Chairman, Treasurer, Member), so the screens do not speak the cooperative's own language.

This feature lets the person who manages roles rename any role in the same place where permissions are edited, and
put a base role's standard name back with one button. Renaming never changes what a role is allowed to do. The
standard Ukrainian name of the Treasurer role becomes "Казначей".

**Success looks like:** a chairman renames "Казначей" to "Бухгалтер" in a few seconds; every screen that lists roles
shows "Бухгалтер" from then on; the audit log records the rename; and nobody gains or loses any permission.

---

## 2. Functional Requirements (The "What")

### 2.1 Renaming any role

- A person allowed to manage roles can rename any role: the three base roles and the cooperative's own roles. The
  name is trimmed of spaces at the ends, must have 1 to 60 characters, and must differ from every other role's name in
  the cooperative regardless of letter case.
  - **Acceptance Criteria:**
    - [ ] Given the chairman opens the Roles page, when they edit the Treasurer role, then the name field can be changed and saved, and when they save "Бухгалтер", then the role is shown as "Бухгалтер" in Ukrainian and in English.
    - [ ] When the chairman renames the Member role to "Садівник" while the cooperative already has its own role "садівник", then the save is refused and they see the "a role with this name already exists" message under the name field.
    - [ ] When the chairman saves the cooperative's role "Електрик" as "Електрик " (with a trailing space), then it is saved as "Електрик" and no change is recorded in the audit log.
    - [ ] When the chairman saves an empty name or a name longer than 60 characters, then the save is refused and they see the matching message under the name field.

### 2.2 Base roles keep their rules

- Renaming changes only the name. Base roles still cannot be deleted, the Chairman role always has every permission,
  and what each role allows stays exactly as before.
  - **Acceptance Criteria:**
    - [ ] Given all three base roles have been renamed, when each demo user signs in, then each sees exactly the same screens and actions as before the renames.
    - [ ] When the chairman edits the renamed Chairman role, then the name can be saved but the permissions remain all ticked and cannot be changed, and no Delete button is offered for any base role.

### 2.3 Standard names and restoring them

- A base role that was never renamed shows its standard name in the language the person uses ("Казначей" in
  Ukrainian, "Treasurer" in English). Once renamed, it shows the new name in every language.
- Under the name field of a base role, a hint shows "Base role: <standard name>". When the role has been renamed, a
  "Restore standard name" button appears; pressing it returns the role to its standard name.
  - **Acceptance Criteria:**
    - [ ] Given the Treasurer role was never renamed, when a person views it in Ukrainian, then it is called "Казначей", and when they switch to English, then it is called "Treasurer".
    - [ ] Given the Treasurer role was renamed to "Бухгалтер", when the chairman presses "Restore standard name", then the role is shown as "Казначей" in Ukrainian and "Treasurer" in English, and the button disappears.
    - [ ] When the chairman edits a base role that was never renamed, then the hint under the name shows its standard name and no restore button is shown.

### 2.4 Warning about names shown on screen

- Because standard names depend on the language, the Roles form warns before saving when the new name equals the name
  another role is currently shown under in the person's language (for example renaming a custom role to "Казначей"
  while the Treasurer role still has its standard name).
  - **Acceptance Criteria:**
    - [ ] Given the Treasurer role has its standard name, when the chairman, using Ukrainian, types "Казначей" as the new name of another role, then a warning under the name field says another role is already shown under this name, before they save.

### 2.5 Who may rename

- Only people allowed to manage roles (the chairman by default) can rename roles or restore standard names.
  - **Acceptance Criteria:**
    - [ ] When the treasurer, who may not manage roles, tries to rename a role, then the change is refused with the "You don't have access" message and the name stays the same.

### 2.6 New names everywhere and in the audit log

- Every place that lists roles shows the current name: the header, the members list, a member's page and role
  editor, the account page and the audit log.
- Every rename and every restore is recorded in the audit log with the old and the new name as the person reading the
  log would see them.
  - **Acceptance Criteria:**
    - [ ] Given the Treasurer role was renamed to "Бухгалтер", when the chairman opens the members list, the treasurer's member page, the treasurer's account page and the header after the treasurer signs in, then each shows "Бухгалтер".
    - [ ] When the chairman, reading the audit log in Ukrainian, opens the entry for renaming the Treasurer role to "Бухгалтер", then it shows the name changing from "Казначей" to "Бухгалтер"; in English it shows "Treasurer" to "Бухгалтер".
    - [ ] When the chairman restores the standard name, then the audit log shows a new entry with the name changing from "Бухгалтер" back to the standard name.

### 2.7 New standard Ukrainian name

- The standard Ukrainian name of the Treasurer role is "Казначей" (English stays "Treasurer").
  - **Acceptance Criteria:**
    - [ ] When any person using Ukrainian sees the Treasurer role that was never renamed, then it is called "Казначей".

---

## 3. Scope and Boundaries

### In-Scope

- Renaming base and custom roles on the Roles page; restoring a base role's standard name.
- The hint, the restore button and the on-screen-name warning on the Roles page.
- Showing current names on every web screen that lists roles, and in the audit log.
- The new Ukrainian standard name "Казначей".

### Out-of-Scope

- The mobile app (it shows no role names yet).
- Any change to what roles allow, to deleting roles, or to assigning roles to members.
- Translating custom names: a renamed role shows the same name in every language.

---

## Assumptions & Decisions

- No question was asked before the run (pipeline autonomy `minimal`; nothing was blocking).
- The audit log shows a base role's standard name in the reader's language when the role had (or got back) its
  standard name, and the stored name otherwise.
- Restoring the standard name also resets the name that is checked for uniqueness, so after a restore another role may
  not take the base role's standard stored name.
- Saving the same name after trimming changes nothing and adds no audit entry; a change of letter case only counts as a
  rename.
- The Chairman role's form can now be saved (for its name); its permissions stay locked.
- The "already exists" check compares against the names the cooperative has saved; the on-screen-name warning (2.4)
  covers the standard names that are only shown in a language.

---

## Change Log

- (none yet)
