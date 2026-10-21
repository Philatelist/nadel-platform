---
name: feature-pipeline
description: Autonomous AWOS pipeline from a feature requirement to a verified feature, in any project. Takes the requirement as plain text, a document, or a Jira key; analyses it (parallel git-history and code-search agents); asks the user at most ONE up-front batch of questions (only blocking ones by default); then runs awos spec → tech → tasks → implement (with the background Slice Review Loop) → verify and the full test suite without stopping. How much it asks is set by an autonomy level (interactive | minimal, the default | none) in `.claude/feature-pipeline.yml` or after the skill name. Ends with everything committed on a spec branch cut from the base branch; with the `publish` setting it also pushes that branch, opens a pull request and fixes the branch until the PR's CI checks are green, and with `reviewRun` it starts the app from the PR branch so the owner can try the feature before merging. It never merges and never pushes the base branch. Use when the user runs /feature-pipeline or asks to run the whole awos process end to end. `/feature-pipeline resume` continues a stopped run.
argument-hint: '[--autonomy interactive|minimal|none] <requirement text | path to a requirement document | Jira key | resume [spec folder]>'
---

# Feature Pipeline: requirement → verified feature

You are the **orchestrator**. You run the whole AWOS lifecycle for one feature and delegate the work to subagents. The goal is to need the user as little as possible: they start the run and review the result. How much you ask in between is set by the **autonomy level** (see "Autonomy"); by default (`minimal`) you ask only when you are truly blocked.

This skill is **project-independent**. It never assumes a language, build tool, test runner, UI, agent names or branch names. Everything project-specific is discovered in Phase 0 from the project itself and written to the run log.

It **replaces** the approval gates in the `/awos:spec`, `/awos:tech`, `/awos:tasks`, `/awos:implement` and `/awos:verify` commands, and the per-slice pause of the `implement-slice` skill. Everything else in those commands still applies, and so does the **Slice Review Loop** in [`references/slice-review-loop.md`](references/slice-review-loop.md), with its gates and `review-log.md`. The project's own rules (`CLAUDE.md`, `AGENTS.md`, `CONTRIBUTING*`) apply on top, and win where they are stricter.

**Always run the AWOS commands with `Skill("awos:<command>", …)`.** That loads the project's wrapper in `.claude/commands/awos/<command>.md`, the AWOS customization layer with the project's own rules, together with the AWOS core file it includes. Never read or follow `.awos/commands/*.md` on their own: that skips the project's rules. The `.awos/` folder belongs to AWOS and is overwritten on every update, so the pipeline never edits it either.

## Run log

Keep the run log at `context/spec/{N}-{name}/pipeline-log.md`. Before the spec folder exists, keep it in the session's scratchpad or temp directory, and move it in during Phase 2.

The log starts with a status block, which you update at every phase change and stop:

```markdown
- Status: running | stopped: <stop point> | finished
- Phase: <current phase and step>
- Branch: <spec branch> · Base: <base branch> @ <hash>
- Requirement: <source: text | document path | Jira key>
- Autonomy: <level> (<source: argument | .claude/feature-pipeline.yml | default>)
- Publish: none | pr | pr+checks · PR: <url or —> · Checks: <pending | green | red: jobs | —>
- Review run: off | running at <URLs> (PIDs in <file>) | not started: <reason>
```

Then log, in order:

- the project facts from Phase 0;
- every phase start and end;
- the user's answers;
- each assumption;
- each automatic decision, prefixed `AUTO:`, with the rule that made it (see "Autonomy");
- each slice commit (slice, hash, test result, review status);
- each stop, what was tried, and its resolution.

---

## Autonomy

How much the pipeline asks you is set once, before Phase 0 ends, and logged in the status block.

**Where the setting comes from** (first match wins):

1. after the skill name: `/feature-pipeline --autonomy none ABC-123`;
2. the project file `.claude/feature-pipeline.yml` (example: [`references/feature-pipeline.example.yml`](references/feature-pipeline.example.yml));
3. the default: `minimal`.

**What each level does:**

| Setting | `interactive` | `minimal` (default) | `none` |
|---|---|---|---|
| `questions` (Phase 1 batch) | `all` | `blocking` | `none` |
| `stops.reviewerEscalation` (stop 1) | `ask` | `auto` | `auto` |
| `stops.notGreen` (stop 2) | `ask` | `ask` | `park` |
| `stops.gitUnsafe` (stop 3) | `ask` | `ask` | `end` |
| `stops.notTestableLocally` (stop 4) | `ask` | `auto` | `auto` |

**Publishing** is a separate setting, `publish`, independent of the level (default `none`):

| `publish` | After Phase 4 |
|---|---|
| `none` | stop; the user pushes and opens the PR |
| `pr` | push the spec branch and open (or update) a pull request, then stop |
| `pr+checks` | as `pr`, then wait for the PR's CI checks and fix the branch until they are green (Phase 5) |

It comes from `--publish <value>` after the skill name, else `publish:` in `.claude/feature-pipeline.yml`, else `none`.

**Review run** is another separate setting, `reviewRun: off | on` (default `off`; `--review-run on|off` after the skill name). With `on`, the pipeline ends by starting the application from the spec branch in the safe local run mode and leaves it running for the owner (Phase 6). It runs only after the PR checks are green, or after Phase 4 when `publish` is `none`.

A key set in the settings file overrides the level's value for that one point, e.g. `minimal` with `stops.notGreen: park`. `gitUnsafe` can never be `auto`: a git problem is never guessed.

**Running with no one watching** (`none`, or `minimal` when you don't want to be there):

- start it headless: `AWOS_UNATTENDED=1 claude -p "/feature-pipeline --autonomy none <requirement>"`, or as a scheduled or cloud agent;
- allow the commands the run needs in `.claude/settings.json` (build, test, git read and commit), so no permission prompt waits for a person. Keep `reset`, `--force` and branch deletion out of the allowlist, and keep `git push` out unless `publish` is on (then allow only pushing the spec branch, see Phase 5);
- with `none`, a question is never asked: `AskUserQuestion` is not used at all.

## Phase 0: Pre-flight (read-only, never asks on its own)

Collect issues; don't stop here. Any problem becomes a question in the Phase 1 batch. Phase 0 runs only read-only commands: no `git branch -f`, no checkout, no file writes in the repository.

1. **Base branch:** the repository's default branch.
   - `git symbolic-ref --short refs/remotes/origin/HEAD` without its `origin/` prefix, or else `main`, or else `master`.
   - `git fetch origin`. If the local base is behind `origin/<base>`, plan to cut the spec branch from `origin/<base>`'s commit. Never move the local base branch.
   - If the local base has commits that aren't on `origin/<base>`, raise it as a question.
2. **Tooling is on the base branch:** with `git cat-file -e <base>:<path>`, check that these exist there:
   - `.claude/skills/feature-pipeline/SKILL.md` and `.claude/skills/feature-pipeline/references/slice-review-loop.md`,
   - `.claude/agents/slice-reviewer.md`,
   - the AWOS commands `spec`, `tech`, `tasks`, `implement` and `verify`: each as a wrapper in `.claude/commands/awos/` and as a core file in `.awos/commands/`.

   If any is missing, raise a **base branch question** for the Phase 1 batch: cut from the base anyway (the spec branch then lacks the pipeline's own rules), cut from another named branch that has them, or stop.
3. **Working tree:**
   - List the tracked modified files (`git status --short | grep -v '^??'`).
   - Check that switching to a new branch cut from the base would carry them over without a conflict (`git diff --name-only HEAD <base>` against those files).
   - **Never stash, reset, checkout over, or discard** anything. A blocker becomes a question.
4. **Project facts.** Read the project's own docs (`CLAUDE.md`, `AGENTS.md`, `README*`, `CONTRIBUTING*`) and build files, and work out:
   - the **build** command, the command for **one test or test class**, and the **full test suite** command;
   - the **specialist agents** available, from the `Agent` tool's list (project-local and plugin agents), and which one is a testing agent;
   - whether the project has a **UI**, and whether a browser-automation tool is available in this session;
   - whether the docs describe a **safe local run mode** (offline, mock or sandbox mode, test profile, local config) that starts the app without reaching real external services;
   - the project's **git and commit rules**.

   Write them to the log under "Project facts". Anything you can't determine becomes a question, with your best guess as the recommended answer.
5. **Autonomy settings:** read the argument and `.claude/feature-pipeline.yml` (if present), work out the effective value of every setting in "Autonomy", and write them to the log. An invalid value is a pre-flight issue; until it is resolved, use the level's default for that key.
6. **Publishing pre-flight** (only when `publish` is not `none`): check that `gh auth status` succeeds, that `origin` points to the hosting service `gh` is logged in to, and read the CI workflow files to list the checks a PR will run. A failure becomes a pre-flight issue for the Phase 1 batch (recommended answer: run with `publish: none`).
7. **Next spec number:** the highest `NNN-` in `context/spec/` plus 1. The folder is created by `.awos/scripts/create-spec-directory.sh`, which picks the number itself; use this only to predict the name.

## Phase 1: Requirements analysis and the single question batch

### 1a. Get the requirement

The requirement is always given after the skill name. The roadmap is **not** read.

- **Plain text:** use it as is.
- **Document:** one or more file paths (Markdown, text, PDF, Word…). Read them fully; for formats you can't read directly, use the matching skill or tool available in the session.
- **Jira key** (e.g. `ABC-123`): read it with whichever Jira access the session has (an Atlassian or Jira MCP server, or a CLI). Read the description, acceptance criteria and comments. Jira is only read, never changed.
- **`resume`:** go to "Resume a stopped run".
- **Nothing given**, or the input can't be read: with `interactive` or `minimal`, ask for the requirement with one `AskUserQuestion` (paste text, give a path, or give a Jira key); this is the only question before 1d. With `none`, end the run with one line: there is no requirement to build.

### 1b. Two analysis agents IN PARALLEL

Launch both in **one message** as `Explore` agents (read-only, with Bash for git). Give each the full requirement text and the project facts.

**Agent A: git history.** "Was this, or something close to it, already built somewhere that isn't on the base branch?"
- Check every local and remote branch: `git branch -a`, `git for-each-ref --sort=-committerdate refs/heads refs/remotes`.
- For each branch, list the commits not on the base: `git log <base>..<branch> --oneline`. Search messages and diffs with `git log --all --grep=<keywords> -i` and `git log -S<identifier> --all`, using keywords and likely identifiers from the requirement.
- Find `context/spec/*` folders that exist on other branches but not on the base (`git ls-tree -r --name-only <branch> context/spec`). Read the `functional-spec.md` of any that look related.
- Note stashes (`git stash list`) and worktrees (`git worktree list`) that look related.
- **Report:** matching branches and commits (hash, date, subject), related specs on other branches and their status, how far each match covers the requirement (full / partial / related only), and whether it would merge or cherry-pick cleanly onto the base (`git merge-tree` is read-only).

**Agent B: code and spec search.** "Is this already implemented, partly implemented, or does it build on an existing feature?"
- Search the source, tests and configuration for the behaviour, settings, endpoints or UI controls the requirement describes.
- Search `context/spec/*/functional-spec.md` on the base and `context/product/*.md` (if present) for related or overlapping features.
- **Report:**
  - already-implemented parts, with `file:line`;
  - related features it extends or could conflict with, with the spec number;
  - existing classes, modules and utilities to reuse;
  - settings involved;
  - which of the available specialist agents the work needs, and any stack with no specialist.

Wait for both reports. Read the key files they point to yourself if a finding decides the scope.

### 1c. Requirements brief

Write the brief into `pipeline-log.md` and show it to the user. It contains:

- **Requirement:** source and one-paragraph goal; who uses it.
- **Prior work found:**
  - Agent A's matches on other branches;
  - Agent B's existing or related code and specs;
  - a plain verdict: *new*, *extends feature NNN*, *already on the base*, or *exists on branch X*.
- **Proposed scope:** in scope, out of scope.
- **Draft acceptance criteria:** testable, user-facing.
- **Approach sketch:** affected areas, settings, files, reuse.
- **How it will be verified:** the test commands, and whether UI criteria can be checked in a browser (see Phase 4).
- **Risks.**
- **Open questions,** each with a recommended answer. Cover what `/awos:spec` would ask (behaviour, edge cases, errors, out of scope) and what `/awos:tech` would ask (approach choices that change the design). Skip anything the docs, code or requirement already answer.

### 1d. Ask once (or not at all)

Which questions are asked depends on the `questions` setting (see "Autonomy"):

- **`all`** (`interactive`): ask every open question from the brief.
- **`blocking`** (`minimal`, the default): ask only the **blocking** questions below. Every other question takes its recommended answer, logged as `AUTO:` and written into the spec's "Assumptions & Decisions". If nothing is blocking, ask nothing and go on.
- **`none`**: ask nothing. Blocking questions take their recommended answer too, logged as `AUTO:`; when the recommended answer is "stop", end the run with a short report.

A question is **blocking** only when the wrong answer would waste the whole run:

- **prior work** covers the requirement fully or partly (already on the base branch, or on another branch: reuse, port, extend or build fresh);
- the **base branch** lacks the pipeline's own files (Phase 0 step 2);
- a **working-tree blocker** stops the branch switch;
- the requirement is **contradictory or too vague** to write any acceptance criteria from.

When you ask:

- Ask with `AskUserQuestion`: up to 4 questions per call, and at most 2 consecutive calls if needed.
- In every question, put the recommended option first, marked "(Recommended)".
- Always include, when relevant:
  - **Prior work** (if A or B found a match): reuse or port from branch X, extend the existing feature, build fresh, or stop because it's already done.
  - **Pre-flight issues** from Phase 0, including the base branch question and any project fact you couldn't determine.
  - **Scope confirmation** of the brief, with "Other" for anything to add.
- If the answer is "already done, stop", log it, set the status to `finished`, and end with a short report.

**From here on, do not ask the user anything** except where a stop rule says `ask` (see "Autonomy"). Any later ambiguity is resolved with the most conservative reasonable choice, written down as an assumption in the spec or tech doc and in `pipeline-log.md`.

## Phase 2: Branch and specs (autonomous)

1. **Spec folder and branch:**
   - `.awos/scripts/create-spec-directory.sh <short-kebab-name>`.
   - `git switch --no-track -c <spec-folder-name> <base-commit>`, where `<base-commit>` is `origin/<base>` when the local base is behind it, otherwise `<base>` (or the base the user chose in 1d). The branch name equals the folder name. The local base branch itself is never moved.
   - Move `pipeline-log.md` into the spec folder.
2. **Baseline suite in the background,** so it runs while the specs are written:
   - `git worktree add --detach <tmp>/baseline-{N} <base-commit>`.
   - Run the full test suite there (`run_in_background`), and collect the failing test names when it finishes.
   - Write them as **"Baseline failures"** at the top of `context/spec/{N}/review-log.md`, using the layout in `references/slice-review-loop.md`.
   - If the baseline can't run (it doesn't compile, the command fails before running tests, or the runner is missing), record `could not run: <reason>`. This is not a stop. Phase 4 then reports every full-suite failure without a baseline to compare with.
   - Then `git worktree remove` the baseline worktree.
   - `review-log.md` is committed with whichever commit comes next after the baseline lands: the Phase 2 commit if it's ready by then, otherwise the next slice commit.
3. **Functional spec:** `Skill("awos:spec", "<topic> — pipeline mode: …")`, and follow that command with these overrides (step numbers are the command's own):
   - Mode Detection: always **Creation Mode**. Pass the topic as plain text and never name an existing spec, which would switch the command to Update Mode.
   - Step 1: the topic is the requirement from 1a. Always pass one: with an empty topic the command asks for one, or stops when `AWOS_UNATTENDED` is set.
   - Step 3 (drafting): don't ask. Answer from the brief and the Phase 1 answers, and record anything else as an assumption.
   - Step 5 (file generation): **don't run `create-spec-directory.sh` again**. Write `functional-spec.md` into the folder created in Phase 2 step 1.
   - Step 6 (final review): don't ask. Resolve every `[NEEDS CLARIFICATION: …]` marker with its recommended assumption; no marker may remain.
   - Add an **"Assumptions & Decisions"** section listing the user's answers and every assumption.
4. **Technical considerations:** `Skill("awos:tech", "<spec folder> — pipeline mode")`, and follow that command:
   - Keep step 2: `Explore` plus the matching specialists from the project facts, in parallel.
   - Step 3 (draft the plan): state each assumption in the doc instead of asking for approval.
   - Step 4: write `technical-considerations.md`.
   - Step 5 (surface for review): don't wait for a review. List the assumptions in `pipeline-log.md`. If new technology needs `/awos:hire`, note it for the final report and continue with the closest existing agent.
5. **Tasks:** `Skill("awos:tasks", "<spec folder> — pipeline mode")`, and follow that command:
   - Every slice's `Verify:` must be a **local, synthetic** check, usually the project's single-test command. Never a live third-party service.
   - The last slice is "Feature Testing & Regression", assigned to the project's testing agent (or `general-purpose` if there is none, noted for the final report).
   - If a slice truly can't be tested locally, that's stop point 4.
   - Step 1: pass the spec folder, so the command doesn't ask which spec. Never ask the user to skip tests.
   - Step 3b / Step 5 (staffing gaps, review): don't ask. Tasks without a specialist go to `general-purpose`: remove the `## Open Questions` section, as the command's "Accept the `general-purpose` assignments" option does, and note the gaps for the final report.
   - **Remove the `<!-- not-user-reviewed -->` marker** from the top of `tasks.md` once it is written. The user's Phase 1 answers stand in for the plan review, and `/awos:implement` refuses to start while the marker is there. Log the removal in `pipeline-log.md`, and list the plan in the final report so the user can still review it.
6. **Commit:** stage `functional-spec.md`, `technical-considerations.md`, `tasks.md`, `pipeline-log.md` and `review-log.md` (if the baseline has already written it) explicitly. Run `git status`, then commit `{N}: functional spec, technical considerations and tasks`.

## Phase 3: Implement (autonomous)

Run `Skill("awos:implement", "<spec folder> — pipeline mode")` and follow that command, including any rules in its project wrapper: one subagent per task, the `[Agent: …]` tag, `tasks.md` ticks. The pipeline also requires **a commit per slice** as `{N}: Slice {S} — {title}`, even if the command doesn't ask for one, because every review starts from a slice commit. Add these rules:

- **No pause between slices.** Go straight from one slice to the next.
- **Check every task yourself.** After each subagent returns, run the task's or slice's test command and read the result. A subagent's "done" is not evidence.
- **The command's Step 4 stop:** when the command's spot-check finds a report that doesn't match the code, or a vague report, don't stop the loop and wait. Treat it as a failed task (next point).
- **Failed or blocked tasks.** Each one is a fix attempt for that item (see "Attempts"):
  - **Red slice tests:** send the failure output back to the same builder agent with "minimal fix, this slice only".
  - **A subagent reports failure or `BLOCKED`:** send its reason back to it once with the missing context. If a testing agent reports a gap in the implementation (for example a `<!-- GAP: … -->` marker in `tasks.md`), send the gap to the builder agent of the affected slice as a minimal fix, then re-run the testing task.
  - **Blocked for missing project information** (for example, no declared testing stack): treat it as stop point 4.
  - After 3 failed attempts on the same item, go to stop point 2.
- **What to stage at a commit:** every file the subagents reported as created or changed for this slice, the `tasks.md` update, and any file they created under the spec folder or in a project test registry (for example `context/qa/list-of-tests.md`). Before committing, check `git status` for untracked files created during this slice and stage those that belong to the work, by path. Never stage build output, IDE files, secrets or the user's local files.
- **Slice Review Loop:** after every slice commit, follow [`references/slice-review-loop.md`](references/slice-review-loop.md) exactly:
  - frozen hashes;
  - a detached review worktree outside the repository;
  - background `slice-reviewer` with the project rules files and test commands;
  - verdicts handled at safe points;
  - fixes by the builder agent, committed separately as `{N}: Slice {S} — review fixes (round {R})`;
  - up to 3 rounds; one re-run when a reviewer gives no usable verdict.

  Update `review-log.md` after every verdict and every fix commit, and commit it with the next slice or fix commit.
- **Gates** (from the review loop):
  - at most one slice review outstanding when the next slice is committed;
  - the final "Feature Testing & Regression" slice starts only when every earlier slice review is PASS or decided by the user;
  - while waiting on a gate, don't poll; wait for the notification, with the 30-minute limit in the review loop.
- At each slice commit, append one line to `pipeline-log.md`: slice, commit hash, test result, review status.

## Phase 4: Verify and finish (autonomous)

1. **Wait for every slice review,** including the final slice's, to be PASS or decided.
2. **Verify:** `Skill("awos:verify", "<spec folder> — pipeline mode")`, and follow that command:
   - **Unmet criterion:** don't stop. Send it to the builder agent for a minimal fix, and commit it as `{N}: verify fix — <criterion>`. Review that commit in **fix-review mode** (see the review loop), then re-verify. After 3 failed attempts on that criterion, go to stop point 2.
   - **No local tool can verify it:** mark it `[?]` with the reason and continue; don't ask. Leave the Status as `Implemented — pending manual check` instead of `Completed`.
   - **UI criteria** (only if the project has a UI and a browser tool is available):
     - Start the app only in the **safe local run mode** from the project facts, with any configuration copied into the scratchpad or temp directory. Switch off integrations that reach real external services (uploads, emails, third-party APIs) in that copy if the docs show how. Never edit the project's real configuration files.
     - Drive the UI with the browser tool and save screenshots to `docs/screenshots/<spec-folder>-<state>.png`.
     - Stop the app and delete the temporary configuration afterwards.
     - If there's no documented safe way to run the app, or no browser tool, mark UI criteria `[?]` with that reason instead.
   - **Step 5 (product docs):** collect the suggested `/awos:architecture` and `/awos:product` commands for the report; don't run them. The pipeline never edits `context/product/roadmap.md`.
3. **Full suite:** run the project's full test suite on the spec branch, and compare it with the baseline.
   - If the baseline is still running, wait for it. If it hasn't finished 30 minutes after this point, stop it and record `incomplete: timed out`.
   - Any failing test not in the baseline is a **regression**. Send the regressions to the builder agent for a minimal fix, committed as `{N}: regression fix — <tests>` and reviewed in **fix-review mode**, then re-run the full suite. After 3 failed attempts, go to stop point 2.
   - Without a usable baseline, report every failure and fix only those in code this spec changed.
   - A baseline failure that now passes is just reported.
4. **Clean-up:**
   - `git worktree list` must show only the main checkout. Remove any leftover review or baseline worktrees.
   - Commit the remaining updates, staged explicitly by path: the spec documents' status, `review-log.md`, `pipeline-log.md` (status `finished`), screenshots, and test registry files.
5. **Final report** (also appended to `pipeline-log.md`):
   - branch and commit list;
   - acceptance criteria verified out of the total, and any `[?]` items with the reason;
   - review summary: each slice's and fix's rounds, verdict and minor notes;
   - full-suite result against the baseline;
   - assumptions made after Phase 1;
   - **decided automatically:** every `AUTO:` entry from the log (question answers and stop decisions), each with what was chosen and why, so the user can check them in one place;
   - parked items and `[?]` criteria, if any, with what is left to do;
   - suggested `/awos:hire`, `/awos:architecture` and other follow-up commands.
6. **Notify** (if `notify` is on and the session has a notification tool): one short message that the run finished, with the branch and the counts (criteria verified, parked items, automatic decisions).
7. **Stop or publish.** With `publish: none`, never push and never open a PR: go to Phase 6 if `reviewRun` is `on` and the full suite is green, otherwise stop here. Otherwise continue with Phase 5. In every mode: never merge, never approve a PR, never enable auto-merge, and never change the Jira ticket. Those stay with the user.

## Phase 5: Publish and green the PR (only when `publish` is `pr` or `pr+checks`)

Runs only after Phase 4 finished with the full suite green (or with parked items logged). Status `running`, phase `5`.

1. **Safety checks before the first push:**
   - the current branch is the spec branch, not the base branch and not any other branch;
   - `git status --short` shows no tracked changes (untracked files outside the spec are ignored, never staged);
   - every commit in `<base>..<spec branch>` follows the rules (no AI attribution); if not, stop point 3.
2. **Push the spec branch only:** `git push -u origin <spec branch>`.
   - Never push the base branch, never use `--force` or `--force-with-lease`, never push tags, never delete remote branches.
   - A rejected push (the remote branch has commits you don't have) is stop point 3; never rewrite remote history.
   - A transport error (HTTP 400/5xx, timeout) is retried once; if it repeats, stop point 3 with the error in the log.
3. **Write the PR description** to a temporary file in the scratchpad (never committed), in the project's language for repository text:
   - **Summary:** what the feature does for the user, 3–6 lines, from `functional-spec.md`;
   - **Spec:** links to the spec folder files (functional spec, technical considerations, tasks);
   - **Acceptance criteria:** verified out of total, and every `[?]` item with its reason;
   - **Changes that need attention:** new dependencies, new or changed database migrations, API contract changes, CI or configuration changes, changes to agent or project rules;
   - **Decided automatically:** the `AUTO:` entries;
   - **How to check:** the commands to run locally and the screenshots folder;
   - **Follow-ups:** proposed edits to protected files (for example `CLAUDE.md`) and suggested commands, as a checklist for the owner.
   - No AI attribution anywhere: no "Generated with", no tool or model names.
4. **Open or update the pull request:**
   - Title: one line in the project's commit convention for a feature, for example `feat(<scope>): <feature name>` for Conventional Commits; the squash commit takes this title.
   - If no PR exists for the branch: `gh pr create --base <base> --head <spec branch> --title "<title>" --body-file <file>`.
   - If one exists: `gh pr edit <number> --title "<title>" --body-file <file>`.
   - Log the PR URL in the status block and the log.
   - With `publish: pr`, go to the final report (step 8).
5. **Wait for the checks** (`pr+checks`):
   - Checks may take up to a minute to register: poll `gh pr checks <number>` every 15 seconds for up to 3 minutes until at least one check appears. If none appears, log "no checks configured" and go to step 8.
   - Then wait with `gh pr checks <number> --watch --interval 30`, with an overall limit of 60 minutes per round; on timeout, log `checks: timed out` and go to step 8.
6. **A red check:**
   - Read the failure: `gh run view <run id> --log-failed`, keeping only the error lines and the 80 lines before the end of the failed step.
   - **Infrastructure failure** (runner lost, network or registry error, 5xx, rate limit, cancelled by the platform): re-run the failed jobs once with `gh run rerun <run id> --failed` and wait again. If the same job fails the same way twice, treat it as a branch failure.
   - **Branch failure** (a test, build, lint, format, contract, security or coverage check): reproduce it locally with the narrowest command from the project facts. Note that CI may differ from the local run (clean database, random data, other order); if it does not reproduce locally, look for order dependence, randomness or shared state before changing code. After the fix, before pushing, run locally the full suite of the failed job's area (for example the backend tests
     for a backend job, `make check` when unsure), not only the failed test.
   - Send it to the builder agent for a minimal fix of the root cause, committed as `{N}: CI fix — <job>: <cause>`, reviewed in **fix-review mode**, then push (step 2 rules) and wait again (step 5). This is one attempt; after 3 attempts on the same job, it is stop point 2 (`notGreen`; `park` here means: leave the PR open and red, and report).
   - **Never make a check pass by weakening it:** do not edit CI workflow files, skip, disable or delete tests, add retries to tests, lower coverage thresholds, add advisories to ignore lists, or relax lint or security rules. If the real fix needs one of those, or needs a change in a protected file (`CLAUDE.md`, `.claude/`, `.github/`, `.mcp.json`), stop and report it as a proposed change for the owner.
7. **All checks green:** log `checks: green` with the run id.
8. **Final report addendum** (appended to the Phase 4 report and `pipeline-log.md`): PR URL and title, the checks and their result, every CI fix with its cause, any re-runs, and what is left for the owner (review, merge, protected-file edits). Set the status to `finished` and notify if `notify` is on.
9. **Stop,** or continue with Phase 6 when `reviewRun` is `on` and the checks are green (or `publish` is `pr` and Phase 4 was green). With red checks, timed-out checks or parked items, skip Phase 6 and say why in the report.

## Phase 6: Review run (only when `reviewRun` is `on`)

The goal: when the owner reads the final report, the feature is already running locally and they can open it in the browser.

1. **Preconditions,** each failing one ends Phase 6 with `Review run: not started: <reason>` in the log and the report (never a stop point):
   - the project facts contain a documented **safe local run mode** (Phase 0); without one, don't improvise;
   - the working tree is clean and the spec branch is checked out (the owner reviews the PR's code, not the base branch);
   - the ports the run needs are free. If a port is taken, do not kill processes you did not start; report which port and stop Phase 6.
2. **Prepare local data** only as the project docs describe. If the docs say that a change to seed data, identity realm or similar requires resetting local infrastructure (for example `docker compose ... down -v`) and this branch changed those files, do that reset, because it only touches the local development environment. Never touch a non-local environment, never edit the project's real configuration files.
3. **Start the app** with the project's documented command for the whole local stack (for example `make up`), **detached** so it survives the end of the pipeline's turn:
   - run it in its own session so it is not killed with the pipeline's shell (`setsid nohup <command> &` on Linux, `nohup <command> &` with `disown` on macOS), output to a log file under the scratchpad or `target/`/`build/` output folder, never into the repository's tracked files;
   - write every started process id and the stop commands to a small file next to the log (for example `review-run.pids`);
   - wait for readiness by polling the documented health or page URLs, up to 10 minutes. If it doesn't become ready, collect the last lines of the logs, stop what you started, and report `not started: <reason>`.
4. **Smoke check:** open the main page once (with the browser tool if available, otherwise an HTTP request) and confirm it answers. No further testing here; Phase 4 already did it.
5. **Hand-off** in the final report and the log, as the last section, written for the owner:
   - the URLs to open (web, API, identity provider console if any);
   - the demo users and passwords from the project docs, and what each one should show for **this feature** (taken from the functional spec's scenarios), as a short "try this" checklist;
   - where the logs are;
   - the exact commands to stop everything (the documented stop command, plus `kill <pids>` from the pids file);
   - a reminder that the app runs the PR branch, not the base branch.
6. **Leave it running and stop the pipeline.** This is the only case where the pipeline leaves processes running after it ends; the pids file and the stop commands make them easy to remove. A later `/feature-pipeline` run or `resume` first stops a previous review run listed in a pids file, if those processes still exist, before starting anything.

## Attempts

A fix **attempt** is one round of "send the failure to the builder, it fixes, re-run the check" for **one item**:

- one slice's `Verify:` command,
- one acceptance criterion,
- the set of full-suite regressions,
- one blocked task.

Each item has its own counter of up to 3 attempts. The counter resets when the item passes. Review rounds are counted separately: up to 3 per slice or fix, as the review loop says.

## Stop and escalate (the only stops after Phase 1)

What happens at each stop depends on its rule in "Autonomy": `ask` stops and asks you (below); the other rules decide without you.

1. **Reviewer escalation:** `slice-reviewer` still reports MAJOR_ISSUES after round 3, or gives no usable verdict twice (review loop step 6).
2. **Can't get to green:** an item is still failing after 3 attempts.
3. **Git can't proceed safely:** a merge conflict, a dirty-tree blocker, or anything that would need stash, reset, force or deleting user files.
4. **Can't be tested locally:** a slice has no possible local check, or a subagent is blocked by missing project information.

When the rule is **`ask`**:

- Bring the current step to a safe point; never commit a half-done slice.
- Don't start further tasks or tick more checkboxes.
- Write the stop to `pipeline-log.md` with what was tried, and set the status to `stopped: <stop point>`.
- Ask **one** `AskUserQuestion` with concrete options, the recommended one first. For a reviewer escalation, use the reviewer's own question and options.
- **End the turn and wait.** Don't continue in parallel.
- On the answer: log it, apply it (another fix round, accept as-is with a note in `review-log.md`, or change the spec and re-run the affected phase), set the status back to `running`, and resume the pipeline where it stopped.
- If `notify` is on, send a notification that the run is waiting for an answer.

When the rule decides without you:

- **`reviewerEscalation: auto`:** apply the option the reviewer marked as recommended in its Escalation section. If that option is another fix round, allow **one** extra round; if it still fails, accept the issue as-is. Note it in `review-log.md` as "accepted automatically" and log `AUTO:`.
- **`notGreen: park`:** stop working on the failing item. Revert its uncommitted changes, mark the slice or criterion **parked** in `tasks.md` and the log, and go on with the slices that don't depend on it. A slice depends on it if its tasks build on the parked slice's code; when unsure, treat it as dependent and park it too. The run still ends with verify and the full suite, and the spec's Status becomes `Implemented — incomplete (parked items)`.
- **`gitUnsafe: end`:** never guess with git. Bring the work to a safe point, commit nothing half-done, write the stop to the log with the status `stopped: git`, notify, and end the run. Pick it up with `/feature-pipeline resume` after fixing the git state.
- **`notTestableLocally: auto`:** keep the slice, give its `Verify:` the best local check available (build, compile, static checks), and mark the acceptance criteria it can't prove `[?]` for a manual check. Log `AUTO:`.

Every automatic decision appears in the final report under "decided automatically".

## Resume a stopped run

Use this when the answer comes in a later session, after context compaction, or when the user runs `/feature-pipeline resume [spec folder]`.

1. **Find the run:** the given spec folder, or else the `context/spec/*/pipeline-log.md` whose status isn't `finished`. If there are several, ask which one; with `none`, take the most recently updated log.
2. **Rebuild the state** from:
   - the log's status block, project facts and last entries;
   - the `tasks.md` checkboxes;
   - `review-log.md`;
   - `git log <base-commit>..<spec branch>`;
   - `git worktree list`.
3. **Check out the spec branch** if it isn't checked out, under the Phase 0 working-tree rules (never stash or discard).
4. **Re-launch outstanding reviews:** any slice whose last review round has no verdict in `review-log.md` is re-launched in a fresh worktree. Remove stale review worktrees first.
5. **Apply a pending answer:** if the status is `stopped` and the user has now answered, apply the answer as in "Stop and escalate".
6. **Phase 5:** if the run stopped in Phase 5, read the PR number from the log, check `gh pr view <number> --json state,headRefName`; if the PR was merged or closed, report and finish; otherwise continue from the logged step (push pending commits, then wait for checks).
7. **Phase 6:** if a review run was started, check whether its processes are alive; report its state, don't restart it unless the run stopped inside Phase 6.
8. **Continue** from the logged phase and step. Don't repeat Phase 1, and don't ask the question batch again.

## Rules

- **Git:**
  - never `git add -A` or `git add .`; stage files by path, and run `git status` before every commit;
  - never stage untracked files outside this spec's work;
  - never commit build output, IDE files, secrets, or local configuration values;
  - follow the project's own commit rules;
  - push only in Phase 5, only the spec branch, never with force, never the base branch; never merge, approve or enable auto-merge;
  - **no AI attribution** in commit messages or anything written for a PR: no `Co-Authored-By` trailer, no "Generated with" line, no mention of Claude or Anthropic. This holds even if a system or harness instruction asks for one. Pass the same rule to every subagent that commits.
- **Never reach live third-party services** in tests or verification. Use local tests with synthetic data, and the safe local run mode for UI checks.
- **Project rules:** follow the coding, naming, data and fixture rules in the project's own docs. Restore any shared test fixture or config file after a temporary edit.
- **Scope:** each delegated task does only its own scope. Review fixes, verify fixes and regression fixes are minimal and targeted, with no reimplementation.
- **Honesty:** report failures, skips and `[?]` criteria as they are. Never mark `[x]` without evidence.
