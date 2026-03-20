#!/bin/bash
# git-dirty-stop.sh — Block session stop when source code has uncommitted changes.
#
# Catches: modified tracked files, staged changes, AND new untracked source files.
# Filters to source code extensions only — ignores random non-source files.
# Build artifacts are already handled by .gitignore so they never appear here.

# Consume stdin (hook passes JSON input)
INPUT=$(cat)

# Prevent infinite loop — if we already blocked once, let it go
ACTIVE=$(echo "$INPUT" | jq -r '.stop_hook_active // false')
if [ "$ACTIVE" = "true" ]; then
  exit 0
fi

# Source code extensions we care about
# Kotlin, Java, XML (layouts/resources/manifest), Gradle build files, TOML (version catalog), properties
SOURCE_PATTERN='\.(kt|java|xml|kts|gradle|toml|properties)$'

# Get all dirty files from git (modified, staged, untracked)
DIRTY=$(git status --porcelain 2>/dev/null)
if [ -z "$DIRTY" ]; then
  exit 0  # Clean tree, allow stop
fi

# Filter to source code files only
SOURCE_CHANGES=$(echo "$DIRTY" | awk '{print $NF}' | grep -E "$SOURCE_PATTERN")

if [ -z "$SOURCE_CHANGES" ]; then
  exit 0  # No source code changes, allow stop
fi

COUNT=$(echo "$SOURCE_CHANGES" | wc -l | tr -d ' ')
FILES=$(echo "$SOURCE_CHANGES" | head -5 | tr '\n' ', ' | sed 's/,$//')

echo "{\"decision\": \"block\", \"reason\": \"STOP. You have ${COUNT} uncommitted source file(s): ${FILES}. Commit your changes before stopping. Do NOT skip this.\"}"
exit 0
