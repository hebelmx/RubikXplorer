#!/usr/bin/env bash
# Story 7.8 release workflow guard: a vX.Y.Z tag and a Play release status of draft or completed.
# Usage: validate-release-inputs.sh <ref-type> <ref-name> <status>; prints version= and status= lines.
set -euo pipefail
ref_type="${1:-}"
tag="${2:-}"
status="${3:-}"
if [[ "$ref_type" != tag || ! "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "::error::Select a vX.Y.Z tag (got $ref_type '$tag')" >&2
  exit 1
fi
if [[ "$status" != draft && "$status" != completed ]]; then
  echo "::error::Release status must be draft or completed (got '$status')" >&2
  exit 1
fi
echo "version=${tag#v}"
echo "status=$status"
