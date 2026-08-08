# Tehran Attack Alert

Android emergency-news monitoring prototype created for **alirahimi**.

## Important

- Sources can be added, removed, enabled, or disabled inside the app.
- Telegram public channel URLs should use `https://t.me/s/channel_name`.
- The first fetch creates a baseline and does not alarm on old posts.
- A later new post must contain both Tehran and a configured attack-related phrase.
- Public-page scraping can break when a website changes its HTML or blocks requests.
- This app is not an official civil-defense warning system and should not be the only safety source.

## Build

Use Android Studio with JDK 17 and Android SDK 35. Build the debug APK with `assembleDebug` after Gradle sync.
