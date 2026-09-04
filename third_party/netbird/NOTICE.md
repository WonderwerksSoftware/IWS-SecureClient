# NetBird dependency notice

The embedded transport is built from NetBird **v0.77.1**, commit
`79a06720b684768b421f0a54f3bb14f22704994f`. NetBird's client code is provided
under BSD-3-Clause; the exact upstream license and REUSE metadata are preserved
in this directory.

The transitive Android client closure is checked during the clean-room build.
It includes these MPL-2.0 dependencies:

- `github.com/hashicorp/errwrap`
- `github.com/hashicorp/go-multierror`
- `github.com/hashicorp/go-version`

MPL-2.0 is file-level weak copyleft. Distribution must retain applicable
notices and make modifications to MPL-covered files available under MPL-2.0.
This does not relicense independently written IWS files, but it is inaccurate to
describe the entire dependency closure as permissively licensed. This record is
engineering compliance evidence, not legal advice.

The GPL-licensed `netbirdio/android-client` application is not copied or linked
into this repository. It was inspected only as upstream reference at revision
`c9ab37ecd1e1b4b3a57622bf2cdd4a400a58dee7`.
