#!/usr/bin/env bash
# Run from a checkout of the same published commit supplied in PLUGIN_COMMIT.
set -euo pipefail

source_dir="$(git rev-parse --show-toplevel)"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
output_dir="${PLUGIN_HUB_OUTPUT_DIR:-$source_dir/build/plugin-hub}"
mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"
rm -f "$output_dir"/{environment.txt,summary.md,packager.log,manifest_diff,flipping-utilities.jar,flipping-utilities.log,flipping-utilities.zip}
runtime="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/plugin-hub-ci.XXXXXX")"
command_token=""

finish() {
  local status=$?
  trap - EXIT
  set +e
  if [[ -n "$command_token" ]]; then
    printf '::%s::\n' "$command_token"
  fi
  # The upstream packager writes these fixed paths only after a build attempt.
  for artifact in /tmp/manifest_diff /tmp/jars/flipping-utilities.{jar,log,zip}; do
    if [[ -f "$runtime/started" && -f "$artifact" && "$artifact" -nt "$runtime/started" ]]; then
      cp "$artifact" "$output_dir/"
    fi
  done
  python3 "$script_dir/plugin-hub-summary.py" "$output_dir" "$status"
  if [[ "$status" -ne 0 && "${GITHUB_ACTIONS:-}" == true ]]; then
    echo '::error title=Plugin Hub check failed::RuneLite packaging failed. See the job summary and plugin-hub-check artifact for details.'
  fi
  exit "$status"
}
trap finish EXIT

build() {
  if [[ ! "${PLUGIN_REPOSITORY:-}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ||
        ! "${PLUGIN_COMMIT:-}" =~ ^[0-9a-fA-F]{40}$ ]]; then
    echo 'Set PLUGIN_REPOSITORY to owner/repository and PLUGIN_COMMIT to its full commit SHA.' >&2
    exit 1
  fi
  if [[ "$(git -C "$source_dir" rev-parse HEAD)" != "$PLUGIN_COMMIT" ]]; then
    echo 'PLUGIN_COMMIT must match the checked-out commit.' >&2
    exit 1
  fi
  if ! tr -d '\r' < "$source_dir/runelite-plugin.properties" | grep -qx 'build=standard'; then
    echo 'This check requires build=standard in runelite-plugin.properties.' >&2
    exit 1
  fi

  cd "$runtime"
  git clone --depth 1 --branch master https://github.com/runelite/plugin-hub.git plugin-hub
  {
    printf 'Plugin: %s\nCommit: %s\n' "$PLUGIN_REPOSITORY" "$PLUGIN_COMMIT"
    printf 'Plugin Hub commit: %s\n' "$(git -C plugin-hub rev-parse HEAD)"
    printf 'RuneLite: %s\n' "$(cat plugin-hub/runelite.version)"
    printf 'Packager: v4 (5893c9f0da9c7602c5574f3a14031c0a9707f008)\n'
  } > "$output_dir/environment.txt"

  curl --location --fail --retry 4 --max-time 120 --output bundle.tar.zst \
    https://github.com/runelite/plugin-hub-tooling/releases/download/v4/bundle.tar.zst
  printf '%s  %s\n' \
    627c97f0ae8b86d59dc3e9c666ab75a7c88ed58ec6320967eabc172b36c3dcd5 \
    bundle.tar.zst | sha256sum --check
  tar xf bundle.tar.zst

  printf 'repository=https://github.com/%s.git\ncommit=%s\n' \
    "$PLUGIN_REPOSITORY" "$PLUGIN_COMMIT" > plugin-hub/plugins/flipping-utilities
  ./prepare.sh

  # Always use PR mode, including on pushes: strict validation without uploading.
  # FORCE_BUILD=ALL would allow individual plugin failures without failing CI.
  unset PACKAGE_COMMIT_RANGE API_FILES_VERSION REPO_CREDS REPO_ROOT
  export PACKAGE_IS_PR=true FORCE_BUILD=flipping-utilities
  touch "$runtime/started"
  java -XX:+UseParallelGC -cp package.jar net.runelite.pluginhub.packager.Packager
}

# Compiler output can contain arbitrary plugin text, so do not interpret it as
# GitHub workflow commands. The EXIT trap restores commands before reporting.
if [[ "${GITHUB_ACTIONS:-}" == true ]]; then
  command_token="$(openssl rand -hex 32)"
  printf '::add-mask::%s\n::stop-commands::%s\n' "$command_token" "$command_token"
fi
(build) 2>&1 | tee "$output_dir/packager.log"
