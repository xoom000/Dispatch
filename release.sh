#!/bin/bash
# release.sh - One-command release automation for Dispatch (DG Messages)
# Usage: ./release.sh "Short description of changes"
#
# What it does:
#   1. Bumps versionCode + versionName in build.gradle.kts
#   2. Commits the version bump
#   3. Builds the debug APK
#   4. Creates a GitHub release with the APK attached
#   5. Stages APK to File Bridge for phone download
#   6. Prints the download URL

set -e

REPO="xoom000/Dispatch"
FILE_BRIDGE="http://100.122.241.82:8600"
DISPATCH_FILES_DIR="/home/xoom000/.dispatch/files"

# Parse arguments
RELEASE_NOTES=""
for arg in "$@"; do
    RELEASE_NOTES="$arg"
done

if [ -z "$RELEASE_NOTES" ]; then
    echo "Usage: ./release.sh \"Short description of changes\""
    exit 1
fi

# Step 1: Get current version
CURRENT_VERSION=$(grep "versionCode = " app/build.gradle.kts | head -1 | sed 's/[^0-9]//g')
NEW_VERSION=$((CURRENT_VERSION + 1))

echo "📦 Releasing v1.0.${NEW_VERSION} (was v1.0.${CURRENT_VERSION})"
echo "📝 Notes: ${RELEASE_NOTES}"
echo ""

# Step 2: Bump version in build.gradle.kts
sed -i "s/versionCode = ${CURRENT_VERSION}/versionCode = ${NEW_VERSION}/" app/build.gradle.kts
sed -i "s/versionName = \".*\"/versionName = \"1.0.${NEW_VERSION}\"/" app/build.gradle.kts

echo "✅ Version bumped to ${NEW_VERSION}"

# Step 3: Commit version bump
git add app/build.gradle.kts
git commit -m "release: v1.0.${NEW_VERSION} — ${RELEASE_NOTES}" --quiet

echo "✅ Version bump committed"

# Step 4: Build debug APK
echo "🔨 Building APK..."
./gradlew assembleDebug --quiet

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "❌ Build failed!"
    exit 1
fi
echo "✅ APK built"

# Step 5: Push to GitHub
echo "📤 Pushing to GitHub..."
git push origin HEAD:main --quiet

# Step 6: Create GitHub release
echo "📤 Creating GitHub release..."
RELEASE_RESPONSE=$(gh release create "v1.0.${NEW_VERSION}" \
    "$APK_PATH#Dispatch-v1.0.${NEW_VERSION}.apk" \
    --title "v1.0.${NEW_VERSION} — ${RELEASE_NOTES}" \
    --notes "<!-- versionCode: ${NEW_VERSION} -->\n\n${RELEASE_NOTES}" \
    2>&1)

if [ $? -ne 0 ]; then
    echo "❌ Failed to create release!"
    echo "$RELEASE_RESPONSE"
    exit 1
fi
echo "✅ GitHub release created"

# Step 7: Stage APK to File Bridge for phone download
cp "$APK_PATH" "${DISPATCH_FILES_DIR}/dispatch-debug.apk"
echo "✅ APK staged to File Bridge"

echo ""
echo "🎉 Release v1.0.${NEW_VERSION} complete!"
echo "📱 Phone download: ${FILE_BRIDGE}/files/dispatch-debug.apk"
echo "🐙 GitHub: https://github.com/${REPO}/releases/tag/v1.0.${NEW_VERSION}"
