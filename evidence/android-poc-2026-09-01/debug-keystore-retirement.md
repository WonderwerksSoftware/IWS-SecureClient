# External debug-keystore retirement

On 2026-09-01, after the independently signed clean-room build and protected
transport archive were verified, the external POC debug keystore was moved from
`/home/wcfox/.android/debug.keystore` to
`/home/wcfox/.android/debug.keystore.retired-20260901` and restricted to mode
0600. It was not copied into this repository or any checkpoint archive.

The clean-room debug keystore remains only in the ignored `.cleanroom` tree and
was also restricted to mode 0600. Neither keystore is tracked by Git.
