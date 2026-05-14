# Privacy Policy

This privacy policy applies to the independently maintained Android app
R47 Zen RPN Calculator published from this repository.

Repository: https://github.com/ppigazzini/r47_android

Privacy contact mechanism: https://github.com/ppigazzini/r47_android/issues

## What This App Does

R47 Zen RPN Calculator is a local calculator shell for Android. It runs the
calculator core on your device, restores local session state, and can optionally
use an Android document-provider folder that you choose as a Work Directory for
portable PROGRAMS, SAVFILES, SCREENS, and STATE files.

## Data This App Accesses

- Calculator session and settings data stored in Android app-private storage.
- Optional document-provider folder access that you grant through Android's
  folder picker.
- Files that you choose to open, create, save, or export through that Work
  Directory.
- Vibration access for haptic feedback.

## Data This App Does Not Collect

- No user account or sign-in data.
- No advertising identifier.
- No analytics or crash-reporting telemetry.
- No contacts, location, microphone, camera, call log, or SMS data.
- No network traffic for app operation. This build does not request Android
  Internet permission.

## How Data Is Used

- App-private storage is used to restore your last calculator session and save
  local preferences.
- The optional Work Directory is used only for calculator files that you choose
  to manage through Android's document browser.
- Haptic settings are used only to control on-device feedback.

## Sharing

This app does not sell or share your personal data with advertising, analytics,
or data-broker services.

If you choose an external Work Directory through Android's document provider,
files that you save there are stored with that provider because you explicitly
asked the app to use that location.

## Retention And Deletion

- App-private session data stays on your device until you clear app data,
  uninstall the app, or use Reset to Factory Defaults inside the app.
- Reset to Factory Defaults clears internal app data but does not delete files
  that you saved in an external Work Directory.
- You can remove the saved Work Directory choice from Settings with Use Android
  Default Folder.
- Files stored in an external Work Directory remain there until you delete them
  through your document provider or file manager.

## Backup And Device Transfer

Android may back up some app-private state depending on your device and account
backup settings. The app excludes its saved Work Directory and slot preference
files from Android backup and device-transfer rules.

## Security

This app keeps its session and preference data in Android app-private storage
unless you explicitly choose an external Work Directory through the system
document picker.

## Policy Updates

When the app's data behavior changes, this policy should be updated in the
source repository and in the app build that ships those changes.
