#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"
clean_root=${IWS_CLEANROOM_ROOT:-$repo_root/.cleanroom}
netbird_source=${IWS_NETBIRD_SOURCE:-$clean_root/netbird}
if [ -x "$clean_root/tools/go/bin/go" ]; then
    PATH=$clean_root/tools/go/bin:$PATH
    export PATH
fi

./scripts/secret-scan.sh
npm test
node --test windows/tests/bundle-contract.test.mjs
node --test windows/webview2/tests/productization-contract.test.mjs
(cd test-fixtures/private-endpoint && go test ./...)
./third_party/netbird/verify-license-closure.sh "$netbird_source"
git diff --check
