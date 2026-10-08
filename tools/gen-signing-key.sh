#!/bin/sh
# 生成 Maven Central 制品签名用的 PGP 密钥（不需要系统装 gpg）。
#
#   tools/gen-signing-key.sh ["Name <email>"] [输出目录]
#
# 默认输出到 ~/.gradle/netlab-signing（就是 Makefile 里 NETLAB_SECRETS 的位置）：
#   signing.key       私钥（ASCII-armored，600）
#   signing.password  私钥口令（600）
#   public.asc        公钥，需要上传到 keyserver 之后 Central 才能验签
#
# 为什么要这个脚本：Central 强制要求 .asc 签名，但签名本身由 Gradle 在内存里完成，
# 不需要 gpg 命令行；而 gpg 在某些机器上装不了（Homebrew 对未识别的 macOS 版本会报
# "unknown or unsupported macOS version: :dunno"）。这里用 BouncyCastle 直接生成。
set -eu

KEY_USER_ID="${1:-OpenKing88 <openking88@users.noreply.github.com>}"
OUT_DIR="${2:-$HOME/.gradle/netlab-signing}"
BC_VERSION="1.78.1"
MAVEN_CENTRAL="https://repo1.maven.org/maven2/org/bouncycastle"

TOOL_DIR="$(cd "$(dirname "$0")" && pwd)"
WORK_DIR="$(mktemp -d)"

cleanup() {
    find "$WORK_DIR" -type f -delete 2>/dev/null || true
    rmdir "$WORK_DIR" 2>/dev/null || true
}
trap cleanup EXIT

echo "==> 下载 BouncyCastle ${BC_VERSION}"
for artifact in bcpg bcprov bcutil; do
    curl -sSfL -o "$WORK_DIR/$artifact.jar" \
        "$MAVEN_CENTRAL/$artifact-jdk18on/$BC_VERSION/$artifact-jdk18on-$BC_VERSION.jar"
done

mkdir -p "$OUT_DIR"
chmod 700 "$OUT_DIR"

PASSPHRASE="$(openssl rand -base64 24)"

# 变量后面紧跟全角字符时要写成 ${VAR}：有些 shell 会把多字节字符算进变量名
echo "==> 生成 4096 位 RSA 密钥（UID: ${KEY_USER_ID}）"
java -cp "$WORK_DIR/bcpg.jar:$WORK_DIR/bcprov.jar:$WORK_DIR/bcutil.jar" \
    "$TOOL_DIR/GenSigningKey.java" "$KEY_USER_ID" "$PASSPHRASE" "$OUT_DIR"

mv "$OUT_DIR/private.asc" "$OUT_DIR/signing.key"
printf '%s' "$PASSPHRASE" > "$OUT_DIR/signing.password"
chmod 600 "$OUT_DIR/signing.key" "$OUT_DIR/signing.password"
chmod 644 "$OUT_DIR/public.asc"

cat <<EOF

密钥已生成：$OUT_DIR
  私钥        $OUT_DIR/signing.key
  口令        $OUT_DIR/signing.password
  公钥        $OUT_DIR/public.asc

还差一步：把公钥发到 keyserver，否则 Central 校验时会报
"Invalid signature ... public key not found"：

  curl -sS -X POST --data-urlencode "keytext=\$(cat $OUT_DIR/public.asc)" \\
    https://keyserver.ubuntu.com/pks/add

注意：私钥一旦丢失就无法再用它签名（已发布的制品不受影响，但后续版本要换新密钥）。
$OUT_DIR 目录建议一并备份。
EOF
