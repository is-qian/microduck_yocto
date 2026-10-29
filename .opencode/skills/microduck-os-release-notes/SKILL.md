---
name: microduck-os-release-notes
description: Generate microduck OS (microduck_yocto) stable release notes from cached PR JSON files. Use when asked to draft release notes from PR files.
---

# microduck OS release notes

## Overview

Write the notes of a stable microduck OS release: the Yocto image for the microduck
robot (RK3566), updated over the air with RAUC. Readers are the people who flash or
update robots, so lead with what changes on a robot, not with how the build changed.

**Output directory:** the prompt gives it as `<output_dir>`. All paths below use it.
The prompt also gives the release tag, the board (`microduck-zero3`, the Radxa Zero 3W
stand-in, or `microduck-beta`, the custom PCB), and the commit it was built from.

## Workflow

### 1. Read PR data

Read every `<output_dir>/tmp/pr_*.json`:

```json
{
  "number": 64,
  "title": "...",
  "author": "username",
  "merged_at": "2026-10-28T10:30:00Z",
  "body": "...",
  "labels": [],
  "url": "https://github.com/pollen-robotics/microduck_yocto/pull/64",
  "doc_diffs": [
    {"filename": "docs/agents/rauc.md", "status": "modified", "patch": "@@ ..."}
  ]
}
```

`doc_diffs` holds the diffs of the Markdown files the PR changed (README.md, AGENTS.md,
docs/agents/*.md); `[]` when it changed none. PR bodies here are long and precise:
read them, they usually say what changed on the robot and why.

### 2. Identify highlights

Highlight a PR when it has the `highlight` label, or when you judge it a change a
robot owner would notice: a new capability, a visibly different behaviour, a
camera/audio/connectivity improvement, a new board. Not every feature needs one.

Each highlight:

1. **Emoji header**, e.g. `## 📷 Brighter camera preview`
2. **Prose summary**, 2-5 sentences, user-visible effect first. Flowing text, not bullets.
3. **Commands** only if the PR introduces one a user runs on the robot (a `systemctl`,
   `rauc`, `duckctl`, or `robotctl` line); take it from the PR body or doc diffs.
4. **Attribution lines**, one per contributing PR: `- Summary by @author in #64`

### 3. Classify the rest

Use `references/sections.md`, on labels, title, and body.

### 4. Flag what an update cannot carry

The OS reaches robots as a RAUC bundle that replaces one rootfs slot (kernel
included). It does **not** rewrite the bootloader, the partition table, or the data
partition. If a PR changes U-Boot, the partition layout (`.wks`), or anything the PR
body says needs a reflash, put it under `💔 Needs a reflash` and say so plainly.
Do not guess: only when the PR says so or clearly touches those parts.

### 5. Doc links

When a PR's `doc_diffs` documents what it changed, link the file at the release's
commit: `https://github.com/pollen-robotics/microduck_yocto/blob/<commit>/<filename>`.
In a highlight: `📚 **Docs:** [rauc.md](...)`; in a bullet, after the attribution:
`— [docs](...)`. Link only files that appear in a `doc_diffs` entry. Never invent a URL.

### 6. Write the file

Output `<output_dir>/RELEASE_NOTES_<version>.md` following
`references/release-notes-template.md`:

- First line exactly as the prompt gives it: `# <title>: <tagline>`, the tagline
  summarising the main highlights in a few words.
- Highlight sections, then the standard sections that have items. No empty sections.

### 7. Bullet style (every bullet)

The text before `by @author in #64` is your summary, not the PR title pasted:

- Start with a capital letter, never with a number.
- Drop conventional-commit and branch prefixes (`feat:`, `fix(x):`, `docs/…`) and the
  branch-name style of titles (`wq9201s-failed-roam` → `Wi-Fi roaming after a failed scan`).
- Don't restate the section: no "Fix" inside `🐛 Bug fixes`, no "Docs" inside `📖 Documentation`.
- Keep it short and in the same mood throughout.

### 8. Quality checks

- Every PR in `<output_dir>/tmp/` appears exactly once.
- **No PR without a file in `<output_dir>/tmp/`**: it belongs to another release.
- Never write `#<number>` for anything but those PRs (not issues, not other repos'
  PRs): the validator counts every `#<number>` as a PR of this release.
- Every bullet ends with `by @author in #<number>`.

## Input

- Release tag, board, commit, title (from the prompt)
- PR JSON files in `<output_dir>/tmp/`

## Output

- `<output_dir>/RELEASE_NOTES_<version>.md`

## Resources

- `references/release-notes-template.md`: the skeleton
- `references/sections.md`: section mapping
