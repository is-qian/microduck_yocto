"""Check that release notes name exactly the PRs in manifest.json.

Missing: in the manifest, not in the notes. Extra: a `#<number>` in the notes
that is not in the manifest, i.e. a PR from another release, or an issue number.

Exit 0 when they match, 1 otherwise.
"""

import json
import os
import re
import sys
from pathlib import Path

OUTPUT_DIR = Path(os.environ.get("RELEASE_NOTES_OUTPUT_DIR", ".release-notes"))

PR_REFERENCE_PATTERN = re.compile(r"#(\d+)")


def load_manifest() -> list[int]:
    """Load the PR numbers from manifest.json."""
    manifest_file = OUTPUT_DIR / "manifest.json"
    if not manifest_file.exists():
        raise FileNotFoundError(f"Manifest file not found: {manifest_file}")
    return json.loads(manifest_file.read_text())["pr_numbers"]


def validate_release_notes(version: str) -> tuple[list[int], list[int]]:
    """Return (missing, extra) PR numbers for RELEASE_NOTES_<version>.md."""
    expected = set(load_manifest())
    notes = OUTPUT_DIR / f"RELEASE_NOTES_{version}.md"
    if not notes.exists():
        print(f"No release notes at {notes}")
        return sorted(expected), []
    found = {int(m) for m in PR_REFERENCE_PATTERN.findall(notes.read_text())}
    return sorted(expected - found), sorted(found - expected)


def main():
    """Run the validation CLI."""
    import argparse

    parser = argparse.ArgumentParser(description="Check release notes against manifest.json")
    parser.add_argument("--version", required=True, help="e.g. microduck-os-v1.0.0")
    parser.add_argument("--json", action="store_true", help="output as JSON")
    args = parser.parse_args()

    try:
        missing, extra = validate_release_notes(args.version)
    except FileNotFoundError as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)

    if args.json:
        print(json.dumps({"missing": missing, "extra": extra}))
    else:
        for label, prs in (
            ("Missing (in manifest, not in notes)", missing),
            ("Extra (in notes, not in manifest)", extra),
        ):
            if prs:
                print(f"{label}: {', '.join(f'#{n}' for n in prs)}")
        if not missing and not extra:
            print("Release notes match the manifest exactly")
    sys.exit(1 if missing or extra else 0)


if __name__ == "__main__":
    main()
