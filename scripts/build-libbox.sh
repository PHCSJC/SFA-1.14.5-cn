#!/usr/bin/env bash
# Build libbox for Android with a tag set trimmed for this project.
#
# Why not just `go run ./cmd/internal/build_libbox -target android`?
#
# Upstream's helper builds two AARs and enables every protocol. One of them,
# `with_naive_outbound`, pulls in cronet-go, whose prebuilt static libraries
# are compiled with a newer LLVM than the NDK shipping on GitHub runners. The
# link step then fails with:
#
#     ld.lld: error: libcronet.a(private_typeinfo.o):(.rodata+0x4):
#       unknown relocation (315) against symbol typeinfo for std::exception
#
# Naive is the only consumer of cronet here, and this build targets hysteria2
# / vless / trojan / shadowsocks users, so the tag is dropped instead of
# pinning an older cronet. Everything else in upstream's tag list is kept.
#
# The tag list below is copied from cmd/internal/build_libbox/main.go so a
# future upstream change to that list is a visible diff here rather than a
# silent behaviour change.

set -euo pipefail

ANDROID_API_MAIN=24
ANDROID_API_LEGACY=21

# Upstream sharedTags minus with_naive_outbound.
TAGS_BASE=(
  with_gvisor
  with_quic
  with_wireguard
  with_utls
  with_clash_api
  with_usbip
  with_openvpn
  with_openconnect
  badlinkname
  tfogo_checklinkname0
  with_tailscale
  ts_omit_logtail
  ts_omit_ssh
  ts_omit_drive
  ts_omit_taildrop
  ts_omit_webclient
  ts_omit_doctor
  ts_omit_capture
  ts_omit_kube
  ts_omit_aws
  ts_omit_synology
  ts_omit_bird
)

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  VERSION="$(git describe --tags --always 2>/dev/null || echo unknown)"
fi

LDFLAGS="-X github.com/sagernet/sing-box/constant.Version=${VERSION}"
LDFLAGS="$LDFLAGS -X runtime.godebugDefault=multipathtcp=0,tlssha1=1"
LDFLAGS="$LDFLAGS -checklinkname=0 -s -w"

join_tags() { local IFS=,; echo "$*"; }

build_variant() {
  local api="$1" out="$2"; shift 2
  local tags
  tags="$(join_tags "$@")"
  echo "==> building $out (androidapi=$api)"
  echo "    tags: $tags"
  gomobile bind \
    -v \
    -o "$out" \
    -target android \
    -androidapi "$api" \
    -javapkg=io.nekohasekai \
    -libname=box \
    -trimpath \
    -buildvcs=false \
    -ldflags "$LDFLAGS" \
    -tags "$tags" \
    ./experimental/libbox
}

build_variant "$ANDROID_API_MAIN" libbox.aar "${TAGS_BASE[@]}"
build_variant "$ANDROID_API_LEGACY" libbox-legacy.aar "${TAGS_BASE[@]}"

ls -lh libbox.aar libbox-legacy.aar
sha256sum libbox.aar libbox-legacy.aar
