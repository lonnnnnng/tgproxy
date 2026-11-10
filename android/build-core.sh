#!/bin/zsh
set -euo pipefail
# long: 构建工具和 AAR 留在忽略目录，固定版本使手机内核能重复构建且不污染全局 Go 工具。
project_dir="${0:A:h}"
mobile_dir="$project_dir/../tgproxy/mobile"
tool_dir="$project_dir/.tools"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
mkdir -p "$tool_dir" "$project_dir/app/libs"
cd "$mobile_dir"
mobile_version="v0.0.0-20261011034528-197770444867"
GOBIN="$tool_dir" go install "golang.org/x/mobile/cmd/gomobile@$mobile_version" "golang.org/x/mobile/cmd/gobind@$mobile_version"
export PATH="$tool_dir:$PATH"
gomobile bind -target=android/arm64 -androidapi=26 -javapkg=dev.tgproxy.core -trimpath -ldflags='-s -w' -o "$project_dir/app/libs/tgproxy.aar" .
