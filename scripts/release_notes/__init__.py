"""AI-drafted release notes for stable OS releases (promote-os.yml).

Ported from reachy_mini's utils/release_notes/, itself a port of huggingface_hub's
"trust-but-verify" generator: list the PRs, let OpenCode draft, check the draft
names exactly those PRs, and loop on the difference.
"""
