#!/usr/bin/env bash
set -euo pipefail

app_version="$(python3 scripts/version.py)"
release_tag="v${app_version}"
apk_path="app/build/outputs/apk/debug/app-debug.apk"
named_apk="${RUNNER_TEMP}/delneveshte-${app_version}.apk"

# This step runs only after build, lint and regression tests have passed on main.
# Existing releases are immutable: a version number must change for a new release.
if ! gh release view "$release_tag" >/dev/null 2>&1; then
  cp "$apk_path" "$named_apk"
  notes="docs/releases/${release_tag}.md"
  if [[ -f "$notes" ]]; then
    gh release create "$release_tag" "$named_apk" --target "$GITHUB_SHA" --title "دل‌نوشته ${app_version}" --notes-file "$notes" --prerelease
  else
    gh release create "$release_tag" "$named_apk" --target "$GITHUB_SHA" --title "دل‌نوشته ${app_version}" --generate-notes --prerelease
  fi
fi

# Backfill the first delivered version from its already verified build artifact.
if ! gh release view v0.1.0 >/dev/null 2>&1; then
  historical_dir="${RUNNER_TEMP}/delneveshte-0.1.0"
  mkdir -p "$historical_dir"
  gh run download 41284805394 --name delneveshte-debug-apk --dir "$historical_dir"
  mv "${historical_dir}/app-debug.apk" "${historical_dir}/delneveshte-0.1.0.apk"
  gh release create v0.1.0 "${historical_dir}/delneveshte-0.1.0.apk" --target c7868b810104801ded448d0ab6b3b8e38779ede9 --title 'دل‌نوشته 0.1.0' --notes-file docs/releases/v0.1.0.md --prerelease
fi
