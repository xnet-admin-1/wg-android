# TODO

## Security

- [ ] Pin rootfs download to SHA-256 checksum in `ProotBootstrap.kt`
- [ ] Use `ProcessBuilder` with explicit argument arrays in `ProotExecutor.exec()` instead of `sh -c` string interpolation
- [ ] Validate HTTPS scheme in `SharedAppViewModel.importFromUrl()` at ViewModel layer (not just UI)
- [ ] Encrypt Room database with SQLCipher or migrate private keys to Android Keystore
