#!/usr/bin/env bash
# Guard the .gitattributes rule `*.sh text eol=lf` (#119): every tracked shell script must resolve to
# text and eol=lf and be stored with LF in the index. Ubuntu CI never shows a CRLF regression on its own.
# Usage: check-sh-eol.sh [repo-dir]   (default: current directory)
# Exit: 0 clean, 1 a script breaks the rule, 2 git failed (not a repo, bad dir).
set -euo pipefail

repo="${1:-.}"
status=0

if ! files="$(git -C "$repo" ls-files -z -- '*.sh' | tr '\0' '\n')"; then
  echo "::error::git ls-files failed in $repo; cannot check shell-script line endings"
  exit 2
fi

while IFS= read -r file; do
  [[ -z "$file" ]] && continue
  text="$(git -C "$repo" check-attr text -- "$file" | sed 's/^.*: text: //')"
  if [[ "$text" != "set" ]]; then
    echo "::error::$file has text attribute $text, expected set (.gitattributes *.sh text eol=lf)"
    status=1
  fi
  attr="$(git -C "$repo" check-attr eol -- "$file" | sed 's/^.*: eol: //')"
  if [[ "$attr" != "lf" ]]; then
    echo "::error::$file resolves to eol=$attr, expected eol=lf (.gitattributes *.sh text eol=lf)"
    status=1
  fi
  index="$(git -C "$repo" ls-files --eol -- ":(literal)$file" | awk '{print $1}')"
  # i/none is a file with no line ending at all (empty, or one line with no newline): no CRLF, so it is accepted.
  if [[ "$index" != "i/lf" && "$index" != "i/none" ]]; then
    echo "::error::$file is stored as $index in the index, expected i/lf"
    status=1
  fi
done <<<"$files"

exit $status
