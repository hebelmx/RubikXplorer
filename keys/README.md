# keys/

This is where signing material lives on your machine. Nothing in this folder is committed except this README and `.gitignore`. The root `.gitignore` ignores the folder's contents too, as a second guard.

Expected files:
- `cubentia-upload.jks`: the Play upload keystore (Play App Signing holds the app-signing key).
- `play-service-account.json`: the Google Play Android Developer API key that CI uses.

CI never reads this folder. It reads the GitHub Actions secrets `UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD` and `PLAY_SERVICE_ACCOUNT_JSON`. Keep a backup of the keystore and its passwords outside the repo, in a password manager.
