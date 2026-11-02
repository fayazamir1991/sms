#!/usr/bin/env bash
set -euo pipefail

app_version="$(python3 scripts/version.py)"
release_tag="v${app_version}"
apk_path="app/build/outputs/apk/release/app-release.apk"
named_apk="${RUNNER_TEMP}/delneveshte-${app_version}.apk"

# Never publish an obsolete candidate if application sources advanced during CI.
git fetch --quiet origin main
if ! git diff --quiet "$GITHUB_SHA" FETCH_HEAD -- app scripts release-input signing-certificate.sha256; then
  echo 'Main contains newer application changes; publication belongs to the newer successful run.'
  exit 0
fi

# This step runs only after build, lint and regression tests have passed on main.
# Existing releases are immutable: a version number must change for a new release.
if ! gh release view "$release_tag" >/dev/null 2>&1; then
  cp "$apk_path" "$named_apk"
  assets=("$named_apk")
  for preview in "docs/previews/${release_tag}"/preview-*.png; do
    if [[ -f "$preview" ]]; then assets+=("$preview"); fi
  done
  notes="docs/releases/${release_tag}.md"
  if [[ -f "$notes" ]]; then
    gh release create "$release_tag" "${assets[@]}" --target "$GITHUB_SHA" --title "دل‌نوشته ${app_version}" --notes-file "$notes" --prerelease
  else
    gh release create "$release_tag" "${assets[@]}" --target "$GITHUB_SHA" --title "دل‌نوشته ${app_version}" --generate-notes --prerelease
  fi
fi
