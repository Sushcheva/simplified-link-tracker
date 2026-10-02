#!/usr/bin/env bash
# Invoked by the release job after all tests pass. Uses GitHub's short-lived job token.
set -euo pipefail
: "${GH_TOKEN:?Required}" "${RELEASE_TAG:?Required}" "${COMMIT_SHA:?Required}" "${REPOSITORY:?Required}"
python3 - <<'PY'
import os,re,xml.etree.ElementTree as ET
version=ET.parse('pom.xml').getroot().find('{http://maven.apache.org/POM/4.0.0}version').text
assert os.environ['RELEASE_TAG']=='v'+version, 'Release tag must match Maven version'
assert re.fullmatch(r'v\d+\.\d+\.\d+',os.environ['RELEASE_TAG'])
PY
# Repository immutability is enabled by an administrator before the first release.
# The job token intentionally has no Administration permission.
if gh release view "$RELEASE_TAG" --repo "$REPOSITORY" >/dev/null 2>&1; then
  echo 'Release already exists. Inspect it; do not overwrite existing release assets.' >&2
  exit 1
fi
image="ghcr.io/$(printf '%s' "$REPOSITORY" | tr '[:upper:]' '[:lower:]')"
docker load --input dist/verified-image.tar.gz
printf '%s' "$GH_TOKEN" | docker login ghcr.io --username "${GITHUB_ACTOR}" --password-stdin
trap 'docker logout ghcr.io >/dev/null 2>&1 || true' EXIT
docker tag "link-tracker-verified:$COMMIT_SHA" "$image:$RELEASE_TAG"
docker push "$image:$RELEASE_TAG"
image_digest="$(docker image inspect "$image:$RELEASE_TAG" --format '{{index .RepoDigests 0}}')"
export IMAGE_DIGEST="$image_digest"
release_id="${RELEASE_TAG//./-}"
python3 scripts/render_k8s.py --release "$release_id" --image "$image_digest"
export RELEASE_DIR=".local/k8s/$release_id"
python3 scripts/assemble_release.py
# Draft first: attach everything before the release and its tag become immutable.
gh release create "$RELEASE_TAG" --repo "$REPOSITORY" --verify-tag --draft \
  --title "Simplified Link Tracker $RELEASE_TAG" --notes-file dist/release/notes.md
gh release upload "$RELEASE_TAG" --repo "$REPOSITORY" \
  dist/release/scrapper.jar dist/release/simplified-link-tracker-*.zip \
  dist/release/deployment.zip dist/release/release.json \
  dist/release/runtime.json dist/release/SHA256SUMS
gh release edit "$RELEASE_TAG" --repo "$REPOSITORY" --draft=false --latest
test "$(gh api "repos/$REPOSITORY/releases/tags/$RELEASE_TAG" --jq .immutable)" = true
