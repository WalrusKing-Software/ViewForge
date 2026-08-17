#!/usr/bin/env bash
#
# Apply (create or update) the repository branch-protection rulesets defined in
# .github/rulesets/*.json. Idempotent: re-running updates the existing ruleset instead of creating
# a duplicate.
#
# Requirements:
#   - gh  (GitHub CLI, authenticated with a token that has `repo` admin scope)
#   - jq
#
# Usage:
#   .github/scripts/apply-branch-protection.sh           # apply to the repo `gh` infers from origin
#   REPO=owner/name .github/scripts/apply-branch-protection.sh
#
# What it sets up (ViewForge branching model — _docs/BRANCHING.md):
#   protect-main     -> refs/heads/main      : PR required, all checks pass, no force-push/deletion
#   protect-release  -> refs/heads/release/* : PR required, all checks pass, no force-push/deletion
#   protect-develop  -> refs/heads/develop   : PR required, all checks pass, no force-push/deletion
#
# The head -> base SOURCE branch policy (only release/*|hotfix/* into main; only develop|hotfix/*
# into release/*; only feature|bugfix|chore|docs|refactor|test|dependabot/* + release/hotfix
# back-merges into develop) is enforced by the `validate-branch-flow` CI check, which is listed as
# a required status check inside these rulesets.
#
# GitHub-only: this uses the GitHub rulesets API. It targets whichever GitHub repo `gh` resolves
# (the mirror). Two caveats specific to a force-pushed mirror:
#   - `non_fast_forward` here will BLOCK the filtered-mirror workflow's force-push unless the
#     mirror token is added as a bypass actor on these rulesets (or you accept applying protection
#     only after the mirror has been seeded).
#   - Rulesets require a public repo or a paid plan on private repos (handled below).

set -euo pipefail

command -v gh >/dev/null || { echo "error: gh (GitHub CLI) is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "error: jq is required" >&2; exit 1; }

REPO="${REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RULESET_DIR="$SCRIPT_DIR/../rulesets"

echo "Target repository: $REPO"

# Preflight: repository rulesets require either a public repository or a paid plan (GitHub Pro and
# above for private repos). On a private repo on the Free plan the rulesets API returns HTTP 403,
# so the apply would fail mid-run with a raw error. Probe once up front and turn that into a clear,
# actionable message.
if ! probe_out="$(gh api "repos/$REPO/rulesets" 2>&1)"; then
  if grep -q '403' <<<"$probe_out" \
     && grep -qiE 'Upgrade to GitHub Pro|make this repository public' <<<"$probe_out"; then
    cat >&2 <<EOF

error: repository rulesets cannot be applied to '$REPO' on its current plan.

  The GitHub rulesets API returned HTTP 403:
    "Upgrade to GitHub Pro or make this repository public to enable this feature."

  Branch protection via rulesets needs ONE of:
    - the repository made public, or
    - a paid plan (GitHub Pro or above) that supports rulesets on private repos.

  Until then the CI/SAST workflows still RUN on pushes and PRs, but nothing BLOCKS a merge when
  they fail. Re-run this script once the repo is public or upgraded; it is idempotent.
EOF
    exit 2
  fi
  echo "error: could not query rulesets for '$REPO':" >&2
  echo "$probe_out" >&2
  exit 1
fi

apply_ruleset() {
  local file="$1"
  local name
  name="$(jq -r .name "$file")"

  echo
  echo "==> Ruleset '$name'  (from ${file##*/})"

  # Find an existing ruleset with this name (rulesets are addressed by numeric id).
  local existing_id
  existing_id="$(gh api "repos/$REPO/rulesets" --paginate \
    | jq -r --arg n "$name" '.[] | select(.name == $n) | .id' | head -n1)"

  if [[ -n "$existing_id" ]]; then
    echo "    updating existing ruleset id=$existing_id"
    gh api --method PUT "repos/$REPO/rulesets/$existing_id" \
      --input "$file" >/dev/null
    echo "    updated."
  else
    echo "    creating new ruleset"
    gh api --method POST "repos/$REPO/rulesets" \
      --input "$file" >/dev/null
    echo "    created."
  fi
}

apply_ruleset "$RULESET_DIR/protect-main.json"
apply_ruleset "$RULESET_DIR/protect-release.json"
apply_ruleset "$RULESET_DIR/protect-develop.json"

echo
echo "Done. Review them at: https://github.com/$REPO/settings/rules"
