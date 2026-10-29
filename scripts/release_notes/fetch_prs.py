"""Fetch the PRs a release contains, as JSON files for the release-notes skill.

The range is <since tag>..<head>: in promote-os.yml the board's previous stable
release and the commit the promoted build came from. This repo merges PRs with
merge commits ("Merge pull request #64 from ..."), and that is the only pattern
looked for. Not the squash-merge "Title (#64)": commits pushed straight to main
end that way when they mention an issue (`(#24)` on v0.1.0..main, a 404 as a PR).
Commits pushed straight to main belong to no PR and are not listed.
"""

import json
import os
import re
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from github import Auth, Github, GithubException

PR_NUMBER_PATTERN = re.compile(r"^Merge pull request #(\d+) ")

OUTPUT_DIR = Path(os.environ.get("RELEASE_NOTES_OUTPUT_DIR", ".release-notes"))
TMP_DIR = OUTPUT_DIR / "tmp"

DEFAULT_REPO = (
    os.environ.get("RELEASE_NOTES_REPO") or os.environ.get("GITHUB_REPOSITORY") or "pollen-robotics/microduck_yocto"
)


def get_github_client() -> Github:
    """Get an authenticated GitHub client."""
    token = os.environ.get("GITHUB_TOKEN_RELEASE_NOTES") or os.environ.get("GITHUB_TOKEN")
    if not token:
        raise ValueError("GITHUB_TOKEN_RELEASE_NOTES or GITHUB_TOKEN environment variable is required")
    return Github(auth=Auth.Token(token))


def extract_pr_number(commit_message: str) -> int | None:
    """Extract the PR number from a merge commit's title."""
    match = PR_NUMBER_PATTERN.match(commit_message)
    return int(match.group(1)) if match else None


# Per file. The notes use a doc diff to pick the file to link and, now and then, a
# command; the whole patch is not needed. Uncapped, v1.0.0's 39 PRs came to 201 KB,
# half of it doc patches (#58 alone 58 KB), and the model read them all and then
# stopped without writing the notes.
DOC_PATCH_MAX = 2000


def fetch_doc_diffs(pr) -> list[dict]:
    """The diffs of the Markdown files a PR changed: README, AGENTS.md, docs/agents/."""
    return [
        {
            "filename": f.filename,
            "status": f.status,
            "patch": f.patch if len(f.patch) <= DOC_PATCH_MAX else f.patch[:DOC_PATCH_MAX] + "\n[... truncated]",
        }
        for f in pr.get_files()
        if f.filename.endswith(".md") and f.patch
    ]


def fetch_pr_details(repo, pr_number: int) -> dict:
    """Fetch a PR's details, including its doc diffs."""
    pr = repo.get_pull(pr_number)
    return {
        "number": pr.number,
        "title": pr.title,
        "author": pr.user.login,
        "merged_at": pr.merged_at.isoformat() if pr.merged_at else None,
        "body": pr.body or "",
        "labels": [label.name for label in pr.labels],
        "url": pr.html_url,
        "doc_diffs": fetch_doc_diffs(pr),
    }


def fetch_prs(since: str, head: str, repo_name: str = DEFAULT_REPO) -> list[int]:
    """Write tmp/pr_<n>.json for every PR merged in since..head, and manifest.json.

    Returns the PR numbers.
    """
    TMP_DIR.mkdir(parents=True, exist_ok=True)
    repo = get_github_client().get_repo(repo_name)

    print(f"Fetching commits {since}..{head}...")
    commits = list(repo.compare(since, head).commits)
    print(f"Found {len(commits)} commits")

    pr_numbers = sorted({n for c in commits if (n := extract_pr_number(c.commit.message))})
    print(f"Found {len(pr_numbers)} PRs")

    def _fetch_and_save(pr_num: int) -> int:
        # One client per call: PyGithub objects are not shared across threads.
        data = fetch_pr_details(get_github_client().get_repo(repo_name), pr_num)
        (TMP_DIR / f"pr_{pr_num}.json").write_text(json.dumps(data, indent=2))
        return pr_num

    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = {pool.submit(_fetch_and_save, n): n for n in pr_numbers}
        for i, future in enumerate(as_completed(futures), 1):
            try:
                future.result()
                print(f"  [{i}/{len(pr_numbers)}] Fetched PR #{futures[future]}")
            # A PR whose file is missing still counts in the manifest, so validation
            # reports it: the notes fail rather than silently skip it.
            except GithubException as e:
                print(f"  [{i}/{len(pr_numbers)}] Warning: failed to fetch PR #{futures[future]}: {e}")

    (OUTPUT_DIR / "manifest.json").write_text(
        json.dumps({"pr_numbers": pr_numbers, "count": len(pr_numbers)}, indent=2)
    )
    return pr_numbers


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Fetch the PRs merged in since..head")
    parser.add_argument("--since", required=True, help="tag or commit to start after, e.g. v0.1.0")
    parser.add_argument("--head", default="main", help="tag, branch or commit to end at")
    parser.add_argument("--repo", default=DEFAULT_REPO)
    args = parser.parse_args()
    fetch_prs(args.since, args.head, args.repo)
