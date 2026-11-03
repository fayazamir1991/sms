#!/usr/bin/env bash
set -euo pipefail
app_version="$(python3 scripts/version.py)"
release_tag="v${app_version}"
if ! gh release view "$release_tag" >/dev/null 2>&1; then
  assets=()
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
