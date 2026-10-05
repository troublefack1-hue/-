#!/bin/sh
# Starts Claude Code with PHONE.md appended to its system prompt, so it knows the `phone` command.
here="$(cd "$(dirname "$0")" && pwd)"
export PATH="$here:$PATH"
exec claude --append-system-prompt "$(cat "$here/PHONE.md")" "$@"
