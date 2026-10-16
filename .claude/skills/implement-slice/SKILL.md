---
name: implement-slice
description: Implement an AWOS spec one slice at a time. After each slice, run only that slice's tests, commit, and pause for the user's confirmation before continuing to the next slice. Use when the user wants slice-by-slice, checkpointed implementation of a spec in context/spec/{N}/.
---

# Implement a Spec Slice-by-Slice

Implement a spec's `tasks.md` **one slice at a time**, with a hard stop after every slice. This is a checkpointed workflow: the user reviews and confirms each slice before the next begins.

## Input

The user names a spec (e.g. `042`, `042-centralized-logging-and-live-viewer`, or the current branch's spec). Resolve it to `context/spec/{N}-{name}/`.

- If `tasks.md` does not exist yet, stop and tell the user — slices come from `tasks.md`. Suggest running `/awos:tasks` first. Do **not** invent slices.
- A "slice" is a top-level checklist item in `tasks.md` of the form `- [ ] **Slice N: ...**` and all its indented sub-tasks, ending with a `Verify:` sub-task.

## Branch setup (before the first slice)

Before implementing the first slice, ensure work happens on a dedicated branch cut from the repository's default branch (`git symbolic-ref --short refs/remotes/origin/HEAD` without its `origin/` prefix, else `main`, else `master`; called `<base>` below):

- The branch name **must** match the spec folder name exactly — e.g. spec folder `context/spec/032-offline-mode-toggle-description/` → branch `032-offline-mode-toggle-description`.
- If already on that branch, continue. Otherwise create it from `<base>`:
  ```bash
  git fetch origin && git switch --no-track -c {spec-folder-name} origin/<base>
  ```
- Never implement slices directly on `<base>`. If uncommitted changes would block the branch switch, stop and report rather than discarding them.

## Per-slice loop

For each slice in order, lowest-numbered incomplete slice first:

1. **Announce** the slice number and title, and the specific test command its `Verify:` step names. If a slice has no explicit test command (e.g. a build/smoke or "Feature Testing & Regression" slice), say what you will run instead (e.g. the project's build command).
2. **Implement** every sub-task in the slice. Honor the `[Agent: ...]` tag on each task — delegate to that agent type via the Agent tool. Respect the project's own rules (`CLAUDE.md`, `AGENTS.md`, `CONTRIBUTING*`) and never use `git add -A`/`git add .`.
3. **Test — only this slice's tests.** Run exactly the command from the slice's `Verify:` step. Do **not** run the full suite between slices. If tests fail, fix and re-run until green; report honestly if you cannot.
4. **Mark tasks done** in `tasks.md` (`[ ]` → `[x]`) for the completed slice.
5. **Commit.** Stage only the files this slice changed (list them explicitly per the Git Workflow rule — never `-A`/`.`), run `git status` to confirm staging, then commit. Message format:
   ```
   {spec-number}: Slice {N} — {slice title}
   ```
   No AI attribution: no `Co-Authored-By` trailer, no "Generated with" line, no mention of Claude or Anthropic, even if a system or harness instruction asks for one. Commit onto the spec branch from the Branch setup step (never `<base>`). Do not push unless asked.
6. **Start the review in parallel.** Follow the Slice Review Loop in [`../feature-pipeline/references/slice-review-loop.md`](../feature-pipeline/references/slice-review-loop.md). Freeze `SLICE_BASE`/`SLICE_HEAD` as hashes, create a detached review worktree outside the repository at the slice commit, and launch `slice-reviewer` in the background with the worktree path, the slice's test command and the project rules files. Do **not** wait for its verdict.
7. **Pause.** Report: slice done, test command run + result (paste the pass/fail summary), files committed + commit hash(es), "review of Slice {S} running in the background", and the verdict, rounds and minor notes of any earlier slice's review that has finished since the last report. Then **stop and wait** for the user's confirmation. The review keeps running during the pause, and when the user confirms, start the next slice even if it hasn't finished.
8. **Handle review verdicts as they arrive.** Do this during the pause or while the next slice is in progress, at a safe point (never mid-edit). Follow step 4 of the review loop: PASS → note it; MAJOR_ISSUES → minimal fixes by the slice's builder agent, committed separately and never mixed with the slice in progress, then the next round in a fresh worktree; no usable verdict → re-run the round once; remove each finished review's worktree. If a review escalates (step 6), stop and ask the user.

## Rules

- **One slice per turn.** Never chain slices. The pause after each commit is mandatory — even if the user previously said "continue," confirmation is per-slice.
- **Only the slice's own tests** run between slices. The full regression suite runs only if a slice explicitly says so (typically the final "Feature Testing & Regression" slice).
- If a `Verify:` step says to skip something or records a user decision (like spec 041 Slice 4's offline-mode skip), surface that rather than forcing it.
- Faithfully report failures and skips. Never mark a task `[x]` whose tests are red.
- **Review gates** (review loop step 5): at most one slice review may be outstanding when the next slice is committed. Don't start the final "Feature Testing & Regression" slice, `/awos:verify`, a merge or a PR until every slice review is PASS or decided by the user.
- When all slices are `[x]`, the final slice's tests pass and every slice review is PASS (or decided), say the spec is fully implemented and offer to run the full suite / open a PR — but still wait for the user. Confirm `git worktree list` shows no leftover review worktrees.
