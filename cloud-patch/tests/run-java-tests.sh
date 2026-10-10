#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
classes="${RUNNER_TEMP:?}/standalone-title-tests"
mkdir -p "$classes/unit" "$classes/integration"
cd "$root"

mapfile -t catalog_fixtures < <(find cloud-patch/tests/catalog_query -name '*.java' | sort)
javac --release 8 -d "$classes/unit" \
  cloud-patch/java/com/apple/android/music/player/ClusterLyricsPaginator.java \
  cloud-patch/java/com/apple/android/music/player/TitleCorrectionState.java \
  cloud-patch/java/com/apple/android/music/player/CatalogQueryMethod.java \
  cloud-patch/java/com/apple/android/music/player/CatalogTitleResolver.java \
  cloud-patch/ampp/java/dev/amenhancer/compat/CatalogQueryMethod.java \
  cloud-patch/tests/com/apple/android/music/player/ClusterLyricsPaginatorTest.java \
  cloud-patch/tests/com/apple/android/music/player/TitleCorrectionStateTest.java \
  cloud-patch/tests/com/apple/android/music/player/CatalogTitleResolverTest.java \
  cloud-patch/tests/com/apple/android/music/player/StandaloneCatalogQueryMethodTest.java \
  "${catalog_fixtures[@]}"
for test in \
  com.apple.android.music.player.ClusterLyricsPaginatorTest \
  com.apple.android.music.player.TitleCorrectionStateTest \
  com.apple.android.music.player.CatalogTitleResolverTest \
  com.apple.android.music.player.StandaloneCatalogQueryMethodTest \
  CatalogQueryMethodTest; do
  java -cp "$classes/unit" "$test"
done

mapfile -t integration_fixtures < <(find cloud-patch/tests/title_integration -name '*.java' | sort)
mapfile -t kotlin_fixtures < <(find cloud-patch/tests/catalog_query/kotlin -name '*.java' | sort)
javac --release 8 -d "$classes/integration" \
  cloud-patch/java/com/apple/android/music/player/*.java \
  "${kotlin_fixtures[@]}" "${integration_fixtures[@]}"
java -cp "$classes/integration" com.apple.android.music.player.VivoTitleIntegrationTest
