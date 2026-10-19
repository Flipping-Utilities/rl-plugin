#!/usr/bin/env bash
# Maintain the Plugin Hub publish branch for this repository.
#
# The Plugin Hub pins a literal commit of this repo and its reviewers diff the
# previous pin against the new one. Development history (tests, images, docs,
# CI) makes those reviews noisy, so this script maintains a branch that
# contains only what the Plugin Hub consumes for a build=standard plugin:
#
#   src/main/java  src/main/resources  lombok.config
#   runelite-plugin.properties  icon.png  LICENSE
#
# plus README.md, which the Hub build itself ignores but runelite.net renders
# as the plugin description: the site fetches
# repos/<owner>/<repo>/readme?ref=<pinned commit> from the GitHub API, so a pin
# without a README shows no description on
# https://runelite.net/plugin-hub/show/flipping-utilities.
#
# See STANDARD_BUILD_PATHS and Plugin#build in runelite/plugin-hub-tooling;
# everything else in this repository is ignored by the Hub build.
#
# Each run appends exactly one snapshot commit on top of the branch tip. The
# snapshot is built from tree data only (ls-tree, update-index, write-tree,
# commit-tree) so the working tree is never touched. The branch must never be
# rewritten or force-pushed: old pins have to stay reachable because the
# packager clones and checks out the pinned commit, and reviewers diff the
# previous pin against the new one.
#
# On first use the branch is seeded from the commit currently pinned in
# runelite/plugin-hub (or HUB_SEED), so the transition review shows only the
# removal of non-plugin files.
#
# Usage:
#   bash .github/scripts/publish-hub.sh [--push]
#
# Environment:
#   HUB_BRANCH  branch to update                        (default: hub/publish)
#   HUB_BASE    ref to snapshot the plugin from         (default: master)
#   HUB_REMOTE  remote used by --push                   (default: origin)
#   HUB_SEED    commit to seed a brand new branch from
#
# Prints the full SHA of the branch tip as the only stdout line, and also
# writes commit=, previous= and changed= to $GITHUB_OUTPUT when set.

set -euo pipefail

push=0
for arg in "$@"; do
  case "$arg" in
    --push) push=1 ;;
    -h | --help) awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 1 ;;
  esac
done

branch="${HUB_BRANCH:-hub/publish}"
base="${HUB_BASE:-master}"
remote="${HUB_REMOTE:-origin}"
plugin_slug="${PLUGIN_SLUG:-flipping-utilities}"
pointer_url="https://raw.githubusercontent.com/runelite/plugin-hub/master/plugins/${plugin_slug}"

# The inputs the Plugin Hub packager reads for build=standard plugins, plus
# README.md for the website description (see the header comment).
keep_paths=(
  src/main/java
  src/main/resources
  runelite-plugin.properties
  icon.png
  LICENSE
  lombok.config
  README.md
)

resolve() { git rev-parse --verify --quiet "$1^{commit}" || true; }

base_commit="$(resolve "$base")"
if [[ -z "$base_commit" ]]; then
  echo "error: base ref '${base}' not found" >&2
  exit 1
fi

tip="$(resolve "refs/heads/${branch}")"
if [[ -z "$tip" ]]; then
  tip="$(resolve "refs/remotes/${remote}/${branch}")"
fi
initial_tip="$tip"

# A brand new branch is seeded from the current Plugin Hub pin when possible,
# so its first commit is a pure strip and the transition review only shows the
# removal of non-plugin files.
seed=""
if [[ -z "$initial_tip" ]]; then
  if [[ -n "${HUB_SEED:-}" ]]; then
    seed="$(resolve "${HUB_SEED}")"
    if [[ -z "$seed" ]]; then
      echo "error: HUB_SEED '${HUB_SEED}' is not a commit in this repository" >&2
      exit 1
    fi
  else
    pinned="$(curl --silent --show-error --fail --max-time 15 "$pointer_url" \
      | sed -n 's/^commit=//p' || true)"
    if [[ "$pinned" =~ ^[0-9a-f]{40}$ ]]; then
      seed="$(resolve "$pinned")"
      if [[ -n "$seed" ]]; then
        echo "seeding new '${branch}' from the current Plugin Hub pin ${seed}" >&2
      else
        echo "warning: pinned commit ${pinned} is not present locally; falling back to '${base}'" >&2
      fi
    else
      echo "warning: could not read the Plugin Hub pin from ${pointer_url}; falling back to '${base}'" >&2
    fi
  fi
fi

sources=()
if [[ -n "$seed" ]]; then
  sources+=("$seed")
fi
if [[ "${sources[0]:-}" != "$base_commit" ]]; then
  sources+=("$base_commit")
fi

# commit-tree needs an identity; fall back rather than failing on a fresh clone.
if [[ -z "$(git config --get user.email || true)" ]]; then
  export GIT_AUTHOR_NAME="${GIT_AUTHOR_NAME:-Flipping Utilities}"
  export GIT_AUTHOR_EMAIL="${GIT_AUTHOR_EMAIL:-flipping-utilities@users.noreply.github.com}"
  export GIT_COMMITTER_NAME="${GIT_COMMITTER_NAME:-$GIT_AUTHOR_NAME}"
  export GIT_COMMITTER_EMAIL="${GIT_COMMITTER_EMAIL:-$GIT_AUTHOR_EMAIL}"
fi

snapshot_tree() {
  local src="$1" index tree
  # A unique nonexistent path: git treats a missing index file as empty,
  # while a zero-length file that exists is invalid ("index file smaller
  # than expected"), so remove the placeholder mktemp created.
  index="$(mktemp "${TMPDIR:-/tmp}/hub-publish-index.XXXXXX")"
  rm -f "$index"
  tree="$({ git ls-tree -r "$src" -- "${keep_paths[@]}" \
    | GIT_INDEX_FILE="$index" git update-index --index-info; } \
    && GIT_INDEX_FILE="$index" git write-tree)" || return 1
  rm -f "$index"
  printf '%s\n' "$tree"
}

validate_tree() {
  local tree="$1"
  if [[ "$(git cat-file -t "$tree" 2>/dev/null || true)" != "tree" ]]; then
    echo "error: snapshot did not produce a tree object" >&2
    exit 1
  fi
  if [[ -z "$(git ls-tree "$tree" -- runelite-plugin.properties)" ]]; then
    echo "error: snapshot is missing runelite-plugin.properties" >&2
    exit 1
  fi
  if [[ -z "$(git ls-tree -r --name-only "$tree" -- src/main/java | grep '\.java$' || true)" ]]; then
    echo "error: snapshot has no Java sources under src/main/java" >&2
    exit 1
  fi
}

current="$initial_tip"
if [[ -z "$current" ]]; then
  current="$seed"
fi

for src in "${sources[@]}"; do
  tree="$(snapshot_tree "$src")" || {
    echo "error: failed to snapshot ${src}" >&2
    exit 1
  }
  validate_tree "$tree"
  if [[ -n "$current" && "$(git rev-parse "${current}^{tree}")" == "$tree" ]]; then
    echo "no plugin changes to snapshot from ${src}" >&2
    continue
  fi
  short="$(git rev-parse --short "$src" || true)"
  if [[ "$src" == "$base_commit" ]]; then
    message="publish: plugin snapshot from ${base} (${short})"
  else
    message="publish: strip ${short} to Plugin Hub packager inputs"
  fi
  parents=()
  if [[ -n "$current" ]]; then
    parents+=(-p "$current")
  fi
  commit="$(git commit-tree "$tree" "${parents[@]}" -m "$message")" || {
    echo "error: failed to create the snapshot commit for ${src}" >&2
    exit 1
  }
  echo "created snapshot commit ${commit} (${message})" >&2
  current="$commit"
done

if [[ -z "$current" ]]; then
  echo "error: nothing to snapshot" >&2
  exit 1
fi

# Make sure the local branch exists before updating it, then update with an
# old-value check so a concurrent push can never be overwritten silently.
if [[ -n "$initial_tip" ]] && [[ -z "$(git rev-parse --verify --quiet "refs/heads/${branch}" || true)" ]]; then
  git update-ref "refs/heads/${branch}" "$initial_tip"
fi
if [[ -n "$initial_tip" ]]; then
  git update-ref "refs/heads/${branch}" "$current" "$initial_tip"
else
  git update-ref "refs/heads/${branch}" "$current"
fi

changed=false
if [[ "$current" != "$initial_tip" ]]; then
  changed=true
fi

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    printf 'commit=%s\n' "$current"
    printf 'previous=%s\n' "$initial_tip"
    printf 'changed=%s\n' "$changed"
  } >>"$GITHUB_OUTPUT"
fi

if [[ "$changed" == true && -n "$initial_tip" ]]; then
  echo "commits added to ${branch}:" >&2
  git log --oneline --no-decorate "${initial_tip}..${current}" >&2 || true
fi

if ((push)); then
  git push "$remote" "refs/heads/${branch}:refs/heads/${branch}"
fi

printf '%s\n' "$current"
