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
- `GET /members/{id}` (`MembersController`): in this slice only the caller's own card; any other id is 404 `NOT_FOUND`,
  exactly like a missing one. `MemberDtoMapper` is the only place that shapes a member for output: `notes` is present
  only for a viewer with `MEMBERS_VIEW`, otherwise the key is absent.

Base roles are `CHAIRMAN`, `TREASURER`, `MEMBER` (seeded per cooperative by the SQL function `seed_base_roles`); custom
roles have no base code. Electrician is no longer a base role.

Depends on `identity` and `problem`.
