# Phone Search: rules for every session working on this repo

This is a Kotlin Android app (`app/`) that searches contacts, SMS and files on the phone,
moves/copies/deletes files, sorts photos with on-device AI and shows an offline caller ID.
The owner installs it from the `latest` GitHub release; family members use it too.
The UI text is Telugu.

## Hard rules (do not break these)

1. **No internet, ever.** The app must never have `android.permission.INTERNET` or
   `ACCESS_NETWORK_STATE`. Libraries that add them are stripped in `AndroidManifest.xml`
   with `tools:node="remove"`. Keep that. Never add analytics, ads, crash reporting or
   anything else that needs the network. "Nothing leaves the phone" is the app's promise.
2. **Risky features stay opt-in.** Anything that bank/UPI apps may flag (drawing over other
   apps, accessibility services, call screening, device admin) must only work after the
   person turns it on from inside the app. Never request these at startup. Do not add
   accessibility services or device admin.
3. **Nothing destructive without confirmation.** Move, delete and rename always show what
   will happen first and wait for "yes". Deletes offer the recycle bin (`Trash.kt`).
4. **Keep existing features working.** Before changing shared files (`MainActivity.kt`,
   `activity_main.xml`, `SearchRepository.kt`), read them as they are now, since another
   session may have changed them.

## Git workflow (avoid clashing with other sessions)

- Work only on branch `claude/ayyindi-u6iu8x`. Every push builds the APK.
- **Always** run `git fetch origin claude/ayyindi-u6iu8x && git merge FETCH_HEAD` before you
  start and again before you push. Resolve conflicts by keeping both sides' features.
- Bump `versionCode` and `versionName` in `app/build.gradle.kts` for each release.

## Build and release

- There is no Android SDK in the cloud container. GitHub Actions
  (`.github/workflows/build.yml`) builds `assembleDebug` and replaces the `latest` release
  asset `PhoneSearch.apk`.
- The APK is signed with the committed `app/debug.keystore`, so updates install over the
  old app. Don't change or regenerate it, or everyone would have to uninstall first.
- After a build, download the release APK and check its permissions (for example with
  androguard) to make sure INTERNET is still absent before telling the owner it's ready.
