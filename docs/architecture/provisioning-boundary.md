# Provisioning to SecureClient boundary

IWS Stack remains the privileged administrator service. It owns device records,
one-off NetBird key creation, artifact publication, peer reconciliation, and
revocation. It checks out an exact IWS SecureClient tag/commit and invokes the
client-owned packager.

## Input

The server creates a mode-`0600` request file containing paths to:

- a device manifest;
- one one-use setup-key file;
- the selected SecureClient checkpoint root;
- a private output directory;
- a signer reference for Android.

The request also carries device ID, generation, platform, client hostname, and
selected checkpoint name. Platform-private build inputs such as the proven AAR,
signer fingerprint, and Windows bootstrap template are passed as environment
references. No administrative credential enters the request or child build.

## Output

`packaging/package-device.mjs` emits one JSON record with artifact path,
filename, size, SHA-256, package identity, client checkpoint, and non-secret
signer metadata. It never emits setup-key contents.

Android delegates to the accepted `scripts/build-android-device.sh`. Windows
uses `packaging/windows/package-device.mjs` to append an authenticated payload
to the replaceable bootstrap template. `prepare-payload.sh` combines verified
transport and Fixed Version WebView2 bundles without admitting the rejected
Edge app-mode launcher.
