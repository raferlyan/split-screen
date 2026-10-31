#!/bin/bash
# 在 Mac 上执行：从编译服务器拉回导出的产物，放进 aosp/，并生成平台签名 keystore
#   用法：scripts/fetch_from_server.sh root@<server-ip>
set -e
SERVER=${1:?usage: fetch_from_server.sh user@host}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
AOSP=$ROOT/aosp
mkdir -p "$AOSP"

rsync -avz --progress "$SERVER:/root/export/" "$AOSP/"

rm -rf "$AOSP/m2repository"
tar -C "$AOSP" -xzf "$AOSP/m2repository.tgz"

# platform.pk8 + platform.x509.pem -> platform.p12（密码 android，别名 platform）
openssl pkcs8 -inform DER -nocrypt -in "$AOSP/platform.pk8" -out "$AOSP/platform.key"
openssl pkcs12 -export -in "$AOSP/platform.x509.pem" -inkey "$AOSP/platform.key" \
    -name platform -out "$AOSP/platform.p12" -passout pass:android
rm -f "$AOSP/platform.key"

echo "done: $(ls "$AOSP")"
