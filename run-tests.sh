#!/usr/bin/env bash
# Plain-JVM unit tests for the remote helper's decision logic (no Android SDK needed).
set -euo pipefail
cd "$(dirname "$0")"
out=$(mktemp -d)
javac -nowarn -d "$out" src/java/com/benwintle/tvhome/RemoteLogic.java tests/RemoteLogicTest.java
java -cp "$out" com.benwintle.tvhome.RemoteLogicTest
