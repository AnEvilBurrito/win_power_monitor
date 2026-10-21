---
name: commit
description: Create or amend repository commits using feat, fix, docs, or dev subjects and 1–2 summary bullets. Use whenever asked to commit, prepare a commit message, or rewrite commit messages; strip all co-author attribution and verify the stored message.
---

# Repository commits

## Message format

Every commit message must contain one subject line, one blank line, and exactly
one or two concise summary bullets. Use this structure:

```text
feat: add battery sampling

- Sample battery discharge rate and remaining capacity
- Expose readings in the tray dashboard
```

Choose exactly one lowercase prefix:

- `feat`: new user-facing functionality.
- `fix`: corrections to existing behavior.
- `docs`: documentation-only changes.
- `dev`: development tooling, tests, CI, dependencies, refactoring, or maintenance
  without a user-facing feature or bug fix.

Use `<type>: <imperative summary>` without scopes or quotation marks. Prefer a
subject of at most 72 characters. Each bullet starts with `- ` and occupies one
line. Summarize the actual staged changes; do not invent features or successful
test results. Do not add other paragraphs, footers, or trailers.

## Attribution policy

- Always strip every `Co-authored-by:` line, case-insensitively, regardless of
  identity, spelling of the name, or whether the email uses angle brackets.
  Remove folded continuation lines belonging to that trailer as well.
- Remove injected attribution labels such as `Copilot (Co-author)` and any
  assistant credit, generated-by footer, or equivalent attribution. Never add
  co-authors, including Copilot, when drafting or amending a message.
- Build the final message afresh from the required subject and 1–2 bullets;
  do not reuse an unsanitized message, template, or `.git/COMMIT_EDITMSG`.
- Keep the user's configured Git author and committer identity. Removing
  co-author trailers does not require changing identity or repository history.

## Workflow

1. Confirm the repository with `pwd` and `git status --short`. Inspect the
   staged diff with `git diff --cached` and relevant unstaged changes with
   `git diff`. Respect unrelated user work and pre-existing staged changes;
   stage only intended paths, never blindly run `git add .` or `git add -A`.
   If the requested commit scope is unclear, ask before staging or committing.
2. Review applicable repository instructions and run relevant checks. Report
   failures or unavailable checks honestly; do not claim an unverified pass.
3. Draft and validate the message against the format and attribution policy.
   For message-only requests, return the message without staging or committing.
4. When authorized to commit, record the current HEAD and commit through the
   Git CLI with an explicit message, not the IDE commit action or an old message
   file. For example, in Bash:

   ```bash
   git commit -m "feat: add battery sampling" -m $'- Sample battery discharge rate and remaining capacity\n- Expose readings in the tray dashboard'
   ```

   For one summary bullet, pass just that bullet as the second `-m` argument.
   Explicit messages avoid stale templates and VS Code's Git UI attribution
   path, but hooks can still alter messages, so verification is mandatory.
5. After Git reports success, inspect `git log -1 --format=fuller`,
   `git log -1 --format=%B`, and `git status --short`. Verify the stored message
   has an allowed subject, exactly 1–2 bullets, no extra text, and no co-author
   or assistant attribution. Check author and committer identity as well.
6. If an injection changed the commit just created by this workflow, verify
   HEAD is still that commit and correct only its message with
   `git commit --amend --only -m "<clean subject>" -m "<clean bullet body>"`.
   The `--only` option prevents newly staged content from entering the amendment.
   Recheck the stored message and confirm its tree is unchanged. Do not amend
   a pre-existing, shared, or unrelated commit without explicit authorization.
7. If a hook or integration re-injects attribution after that correction, stop
   and report the exact stored message and suspected source. Do not silently
   disable hooks, bypass checks with `--no-verify`, or loop through amendments.
   Inspect `git config --show-origin --get core.hooksPath`, active hooks, and
   applicable editor settings. VS Code's `git.addAICoAuthor` can append Copilot
   trailers; recommend `"git.addAICoAuthor": "off"` when relevant, but do not
   change editor settings or install hooks without permission.
8. Report the resulting commit hash, subject, and checks performed. Never push
   or force-push unless the user explicitly requests it.

This skill governs commits made while following these instructions. It is not a
Git hook and cannot prevent other tools from adding attribution independently.
