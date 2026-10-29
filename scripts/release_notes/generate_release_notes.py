"""Draft the release notes of a stable OS release.

1. Fetch every PR merged in since..head (fetch_prs.py) as JSON.
2. Run the microduck-os-release-notes OpenCode skill to draft from them.
3. Check the draft names exactly those PRs (validate_notes.py), and run the
   skill's :validate variant on the difference, up to --max-iterations times.

Output: .release-notes/RELEASE_NOTES_<version>.md, its first line the title.

    export GITHUB_TOKEN=...   # repo read
    export HF_TOKEN=...       # Inference Providers, via OpenCode's auth store
    export RELEASE_NOTES_MODEL=huggingface/zai-org/GLM-5.3
    python -m scripts.release_notes.generate_release_notes \\
        --since v0.1.0 --head main --version os-zero3-preview \\
        --title "v0.1.0-preview (microduck-zero3)"
"""

import argparse
import os
import shutil
import subprocess
import sys
import time

from github import GithubException

from .fetch_prs import OUTPUT_DIR, TMP_DIR, fetch_prs
from .validate_notes import validate_release_notes

SKILL = "microduck-os-release-notes"


def check_opencode_model(model: str) -> None:
    """Fail unless `opencode models` lists the model.

    OpenCode exits 0 on an unknown --model and does nothing, so a typo would
    otherwise produce empty notes and no error.
    """
    opencode = shutil.which("opencode")
    if not opencode:
        raise RuntimeError("'opencode' not found in PATH")
    out = subprocess.run([opencode, "models"], check=True, capture_output=True, text=True).stdout
    available = [line.strip() for line in out.splitlines() if line.strip()]
    if model not in available:
        raise RuntimeError(
            f"RELEASE_NOTES_MODEL={model!r} is not in `opencode models` "
            f"(expected provider/model, e.g. huggingface/zai-org/GLM-5.3). "
            f"First of {len(available)}: {', '.join(available[:10])}"
        )


def run_opencode(prompt: str) -> bool:
    """Run OpenCode non-interactively; --auto approves its tool use (no TTY in CI)."""
    cmd = ["opencode", "run", "--auto"]
    if model := os.environ.get("RELEASE_NOTES_MODEL"):
        cmd += ["--model", model]
    cmd.append(prompt)
    print(f"Running: {' '.join(cmd)}")
    try:
        subprocess.run(cmd, check=True)
        return True
    except (subprocess.CalledProcessError, FileNotFoundError) as e:
        print(f"OpenCode failed: {e}", file=sys.stderr)
        return False


def main(since: str, head: str, version: str, title: str, machine: str, max_iterations: int) -> int:
    """Run the pipeline; returns the exit code."""
    t0 = time.monotonic()
    if model := os.environ.get("RELEASE_NOTES_MODEL"):
        try:
            check_opencode_model(model)
        except RuntimeError as e:
            print(f"Error: {e}", file=sys.stderr)
            return 1

    if OUTPUT_DIR.exists():
        shutil.rmtree(OUTPUT_DIR)
    TMP_DIR.mkdir(parents=True)

    try:
        prs = fetch_prs(since, head)
    except (GithubException, ValueError) as e:
        print(f"Error fetching PRs: {e}", file=sys.stderr)
        return 1
    if not prs:
        print(f"No PRs merged in {since}..{head}", file=sys.stderr)
        return 1

    notes = OUTPUT_DIR / f"RELEASE_NOTES_{version}.md"
    context = (
        f"The output directory is {OUTPUT_DIR}. "
        f"The release is {version}, for the {machine} board, built from commit {head}. "
    )
    if not run_opencode(
        f"Run the {SKILL} skill. {context}"
        f"Generate release notes from the PR files in {TMP_DIR}/. "
        f"The first line must be exactly: # {title}: <tagline>. "
        f"Output to {notes}"
    ):
        return 1
    calls = 1
    if not notes.exists() or notes.stat().st_size == 0:
        # OpenCode can exit 0 without writing anything (auth, quota).
        print(f"Error: OpenCode did not write {notes}; see its output above", file=sys.stderr)
        return 1

    missing, extra = validate_release_notes(version)
    for i in range(max_iterations):
        if not missing and not extra:
            break
        print(f"Iteration {i + 1}/{max_iterations}: missing {missing}, extra {extra}")
        prompt = f"Run the {SKILL}:validate skill. {context}Fix the release notes at {notes}. "
        if missing:
            prompt += (
                f"Add these missing PRs: {', '.join(f'#{n}' for n in missing)}; "
                f"their details are in {TMP_DIR}/pr_<number>.json. "
            )
        if extra:
            prompt += f"Remove these PRs, which are not in this release: {', '.join(f'#{n}' for n in extra)}. "
        run_opencode(prompt)
        calls += 1
        missing, extra = validate_release_notes(version)

    print(
        f"\n{len(prs)} PRs, {calls} agent calls, {time.monotonic() - t0:.0f} s; "
        f"still missing {missing or 'none'}, extra {extra or 'none'}"
    )
    # Notes that still miss a PR are wrong, not merely rough: let the caller fall back.
    if missing or extra:
        print("Error: the notes do not match the PR list", file=sys.stderr)
        return 1
    print(f"Release notes: {notes}")
    return 0


def cli():
    """CLI entry point."""
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--since", required=True, help="the previous release's tag")
    parser.add_argument("--head", required=True, help="the commit this release was built from")
    parser.add_argument("--version", required=True, help="the release tag, e.g. microduck-os-v1.0.0-zero3")
    parser.add_argument("--title", required=True, help="the release title the notes' heading starts with")
    parser.add_argument("--machine", default="microduck-zero3")
    parser.add_argument("--max-iterations", type=int, default=3)
    args = parser.parse_args()
    sys.exit(main(args.since, args.head, args.version, args.title, args.machine, args.max_iterations))


if __name__ == "__main__":
    cli()
