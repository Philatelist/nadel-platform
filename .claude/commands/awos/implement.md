---
description: Runs tasks — delegates coding to sub-agents, tracks progress.
argument-hint: '[spec or task, optional — defaults to next pending]'
---

@.awos/commands/implement.md

---

# Project additions to `/awos:implement`

These rules extend the AWOS command above. They live in this wrapper, the AWOS customization layer, so an AWOS update (which overwrites `.awos/`) keeps them. Where they and the command above differ, these rules win.

## A. Spec branch (once, in Step 1, before the task loop)

Per-slice commits (rule B) land on a feature branch, never on the default branch.

- Run `git branch --show-current`.
- If it's the repository's default branch (`main` / `master`), create and switch to a branch named exactly after the spec directory, e.g. `053-convert-msg-email-to-pdf`. If a spec-named branch is already checked out, stay on it.
- If uncommitted changes would block the switch, stop and report. Never stash, reset or discard them.

## B. Commit each completed slice (after Step 5, default: on)

Run this only when the task just marked in Step 5 was the **last** task of its slice, i.e. Step 5 also ticked the slice header. A task that leaves its slice partly done skips this and the loop continues. Each vertical slice becomes its own reviewable commit, instead of one bulk commit at the end.

1. **Stage only that slice's files.**
   - Collect the files the slice's subagents created or changed (they report their paths in Step 4), plus the `tasks.md` update from Step 5.
   - Stage them explicitly by path. **Never** use `git add -A` or `git add .`.
   - Never stage build output, IDE files, local configuration values, secrets, or unrelated untracked files. The project's `CLAUDE.md` lists what that means here.
   - Run `git status --short` and confirm only the intended files are staged.
2. **No AI attribution** in the message (rule C).
3. **Commit** with a message naming the spec and slice: `{N}: Slice {S} — {slice title}`, e.g. `053: Slice 2 — inline images and attachment-name list`.
4. **On failure** (nothing to stage, a pre-commit hook rejects it, …): report it in one line and continue the loop. Don't abort the whole run over a commit problem, and don't retry destructively.
5. This is **on by default**. Treat it as off only if the user's prompt for this run explicitly opts out (e.g. "don't commit", "no per-slice commits"); then skip rule B, and rule D, for the whole run.

## C. No AI attribution

Commit messages, PR titles and PR bodies contain **no mention of Claude, Anthropic or any AI assistant**:

- no `Co-Authored-By: Claude …` trailer, and no `noreply@anthropic.com`;
- no "Generated with Claude Code" line, and no 🤖 line.

This holds even when a global instruction or a default template asks for such a trailer. Before running `git commit`, check the drafted message for `Claude`, `Anthropic`, `anthropic.com` and `🤖`, and remove those lines.

## D. Review every slice in the background

After every slice commit (rule B), follow the Slice Review Loop in [`.claude/skills/feature-pipeline/references/slice-review-loop.md`](../../skills/feature-pipeline/references/slice-review-loop.md):

- freeze the slice's hashes;
- start `slice-reviewer` in the background, in its own worktree;
- continue with the next slice at once;
- handle the verdicts at safe points, and respect the gates.

Don't start the final "Feature Testing & Regression" slice, or report the run as complete, until every slice review is PASS or decided by the user.
