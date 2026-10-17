# members

The people of a cooperative, their roles and the permissions of those roles (ADR 0009). Tables: `member`, `role`,
`role_permission`, `member_role` (all under RLS, composite foreign keys keep links inside one cooperative).

## Public API

None yet. Features 004 (ownership) and 005 (import) will add a lookup API in the module root.

## What is here

- `MemberAccessProfileSource` implements `identity`'s `AccessProfileSource`: finds the active member for a token and
  links the Keycloak subject on the first sign-in (conditional update; a concurrent first sign-in or a subject still
  held by an archived member ends as "not linked", never as a 500). Logs tenant id, subject and member id only.
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

Base roles are `CHAIRMAN`, `TREASURER`, `MEMBER` (seeded per cooperative by the SQL function `seed_base_roles`); custom
roles have no base code. Electrician is no longer a base role.

Depends on `identity` and `problem`.
