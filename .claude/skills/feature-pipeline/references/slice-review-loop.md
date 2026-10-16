# Slice Review Loop

Mandatory after every slice commit. It applies to `/feature-pipeline`, `/awos:implement`, the `implement-slice` skill, and any other flow that commits an AWOS slice. These rules are project-independent: the project supplies its own test commands, agents and coding rules (see "Project facts" below).

The review of slice S runs **in the background, in its own git worktree**, while the main session goes on with slice S+1. A review never blocks the next slice; only the gates (step 5) and an escalation (step 6) do.

## Project facts the loop needs

Read these once, from the project's own docs (`CLAUDE.md`, `AGENTS.md`, `README*`, `CONTRIBUTING*`, build files), and pass them to every reviewer:

- **Test command for a slice:** the command in the slice's `Verify:` task in `tasks.md`.
- **Compile / build check:** the project's documented build command, if there is one.
- **Project rules files:** the paths of the files above that exist, so the reviewer reads the project's own rules.
- **Builder agents:** whatever agent the task's `[Agent: ...]` tag names. Never assume a fixed list.

## Steps

1. **Freeze the slice.** Right after the slice commit, record `SLICE_BASE = <slice-commit>^` and `SLICE_HEAD = <slice-commit>` as **hashes**, never `HEAD`, which moves on as the next slice is committed.
2. **Review worktree.** Create a detached worktree at the frozen commit, outside the repository (in the session's scratchpad or temp directory):
   `git worktree add --detach <tmp>/review-{N}-s{S}-r{R} <commit-under-review>`
   The reviewer compiles and tests there, so it never sees the next slice's half-written code and never shares build output with the main session.
3. **Start the review in the background.** Call `Agent(subagent_type="slice-reviewer")` with:
   - the spec folder, the slice number and title;
   - the worktree path;
   - the range `SLICE_BASE..SLICE_HEAD`;
   - the slice's `Verify:` test command, the build check, and the project rules files;
   - `Round 1`.

   Then **continue immediately** with the next slice. In the slice report, say the review is running.
4. **When the verdict arrives** (task notification), handle it at the next safe point: after the current task or delegated agent finishes, never in the middle of an edit. Then remove that review's worktree (`git worktree remove <path>`) and update `review-log.md` (layout below).
   - **PASS:** record the verdict, rounds and minor notes, and include them in the next slice report.
   - **MAJOR_ISSUES (round < 3):** send only the major issues to the agent that built slice S (its `[Agent: ...]` tag). Tell it to make **minimal targeted fixes only, no reimplementation or scope expansion**, to touch only slice S's code, and to re-run slice S's tests.
     - If none of the fixed files has uncommitted changes from the slice in progress, commit the fixes right away as `{N}: Slice {S} — review fixes (round {R})`. Stage the fixed files explicitly, and run `git status` first to confirm nothing from the slice in progress is staged.
     - If a fixed file **overlaps** with uncommitted work of the slice in progress, don't mix them. Finish and commit the current slice first, then apply and commit the review fixes as a separate commit right after it.
     - Start the next round as in steps 2–3: a new worktree at the **fix commit**, the original range `SLICE_BASE..SLICE_HEAD` **plus the list of fix-commit hashes**, the next round number, and the previous round's major issues. Tell the reviewer to judge only slice S's code and those fix commits, and to ignore later slices' code present in the worktree.
   - **No usable verdict** (the reviewer errors, crashes, or ends without the required output format): re-run the same round once, in a fresh worktree. A second failure is an escalation (step 6) with the reason "reviewer unavailable".
   - Minor issues are never sent for fixing unless the user asks.
5. **Gates.** The main session may run ahead, but:
   - at most **one** slice's review may be outstanding when the next slice is committed. Before committing slice S+2, wait for slice S's review to reach PASS (or for the user's decision);
   - the final "Feature Testing & Regression" slice, `/awos:verify`, any merge and any PR wait until **every** slice review is PASS or decided by the user.
   - While waiting on a gate, don't poll: wait for the task notification. If a review you are blocked on has run for more than **30 minutes** with no notification, stop it (`TaskStop`) and treat it as "no usable verdict" (step 4).
6. **Still MAJOR_ISSUES after round 3, or reviewer unavailable twice:** **escalate and stop.** Finish the current task to a safe point but don't commit a half-done slice. Don't start further tasks or mark more tasks `[x]`. Show the reviewer's Escalation section (or the failure reason) and ask its question with `AskUserQuestion`, using the reviewer's options with the recommended option first. End the turn and wait. Continue only on the user's answer: apply their decision (another fix round, accepting the issue as-is with a note in `review-log.md`, or changing the spec), then re-run the reviewer if code changed.
7. The reviewer never edits code. If a reviewer finding looks wrong, don't argue it away silently: tell the user in the slice report.
8. **Clean-up.** No review worktree may survive the end of the spec: `git worktree list` must show only the main checkout once all reviews are done.

## Fix reviews (verify fixes and regression fixes)

A fix made after all slices are done (a verify fix or a full-suite regression fix) has no slice section in `tasks.md`. Review it with the same worktree rules, but start the reviewer in **fix-review mode**:

- `Mode: fix review`, the fix commit range, the round number;
- instead of a slice: the acceptance criterion it fixes (quoted from `functional-spec.md`) or the names of the failing tests and their failure output;
- the command that proves the fix (the criterion's check or the failing tests).

The reviewer judges only the fix diff against that criterion or those tests, plus the general MAJOR categories. Rounds, escalation and clean-up are the same as for slices.

## `review-log.md` layout

One file per spec, `context/spec/{N}-{name}/review-log.md`, committed with the next slice or fix commit after each change:

```markdown
# Review log — {N}-{name}

## Baseline failures

- Base commit: `<hash>` (`<base branch>`), command: `<full test command>`
- Result: <k> failing | could not run: <reason> | incomplete: <reason>
- `<TestClass.testMethod>`
- …

## Slice 1 — <title>

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `<base>..<head>` | MAJOR_ISSUES | M1 <title> | |
| 2 | + `<fix hash>` | PASS | M1 resolved | minor: … |

## Fix — <criterion or test names>

| Round | Commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
```
