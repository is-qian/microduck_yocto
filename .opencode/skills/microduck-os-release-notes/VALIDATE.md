---
name: microduck-os-release-notes:validate
description: Fix microduck OS release notes by adding missing PRs and removing extra ones. Use when the notes don't match the PR manifest exactly.
---

# Validate microduck OS release notes

## Overview

Make the release notes name exactly the PRs of this release: add the missing ones,
remove the extra ones. Leave everything else as it is.

**Output directory:** the prompt gives it as `<output_dir>`.

## Workflow

### 1. Read the notes

Read `<output_dir>/RELEASE_NOTES_<version>.md`.

### 2. Add missing PRs

For each missing PR number given:

- Read `<output_dir>/tmp/pr_<number>.json`, including its `doc_diffs`.
- Pick the section with `references/sections.md`, an existing one first.
- Follow the main skill's bullet style (`SKILL.md`, step 7) and its reflash rule (step 4).
- With the `highlight` label, write a highlight section as the main skill describes;
  otherwise a bullet: `- Summary by @author in #<number>`.

### 3. Remove extra PRs

For each extra PR number given:

- Remove every line or bullet that mentions `#<number>`.
- If that empties a highlight or a section, remove the section.
- Remove only the numbers listed as extra.

### 4. Write it back

Write `<output_dir>/RELEASE_NOTES_<version>.md`, keeping its first line unchanged.

## Resources

- `SKILL.md`: the main skill's rules
- `references/sections.md`: section mapping
