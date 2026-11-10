#!/bin/zsh
set -euo pipefail
# long: 发布密钥仅生成一次并保存在忽略目录，后续升级必须复用它；不把密码放入命令参数或日志。
project_dir="${0:A:h}"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
signing_dir="$project_dir/.signing"
output_dir="$project_dir/app/build/outputs/apk/release"
android_build_tools="$ANDROID_HOME/build-tools/36.0.0"
umask 077
mkdir -p "$signing_dir"
if [[ ! -f "$signing_dir/release.p12" ]]; then
    if [[ -e "$signing_dir/password" ]]; then
        print -u2 '签名密码文件已存在但密钥缺失，请先恢复密钥，脚本不会覆盖现有材料。'
        exit 1
    fi
    python3 -c 'import secrets; print(secrets.token_urlsafe(48))' > "$signing_dir/password"
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$signing_dir/release.p12" -storetype PKCS12 \
      -storepass:file "$signing_dir/password" -keypass:file "$signing_dir/password" \
      -alias tgproxy -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=TGProxy Android, OU=Mobile, O=TGProxy'
fi
if [[ ! -f "$signing_dir/key-password" ]]; then
    cp "$signing_dir/password" "$signing_dir/key-password"
fi
zsh "$project_dir/build-core.sh"
"$project_dir/gradlew" -p "$project_dir" :app:assembleRelease
"$android_build_tools/zipalign" -f -p 4 "$output_dir/app-release-unsigned.apk" "$output_dir/tgproxy-android-arm64.apk"
"$android_build_tools/apksigner" sign --ks "$signing_dir/release.p12" --ks-key-alias tgproxy \
  --ks-pass "file:$signing_dir/password" --key-pass "file:$signing_dir/key-password" "$output_dir/tgproxy-android-arm64.apk"
"$android_build_tools/apksigner" verify --verbose "$output_dir/tgproxy-android-arm64.apk"
shasum -a 256 "$output_dir/tgproxy-android-arm64.apk"
