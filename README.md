# มดแดง · Mot Dang for Android

motdang.net in one window, plus what a web page cannot do on a phone:

- Live location that keeps sending with the screen locked. Start it from the
  site's Send button; the phone shows a notification with Stop until the hour
  is up. Positions go to motdang.net/api/live (mot-dang/publish/live.js).
- Shares from other apps land on the motdang map: a Google Maps link (short
  links opened out by /api/resolve), coordinates, a plus code, a place ID, a
  name, and geo: links from "open in maps".
- The phone's own share sheet for everything the site sends.

Links off motdang.net (LINE, Grab, phone numbers) open in their own apps.

Build: `./build.sh` → `build/motdang.apk` (SDK build-tools 36, platform 36,
JDK 17; no Gradle; same toolchain as baan-launcher). The build runs
test/LinksTest.java. Updates must be signed with `motdang-app.keystore`.

Install: `adb install -r build/motdang.apk`, or open the .apk on the phone.

Code: MIT.
