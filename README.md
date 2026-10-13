# มดแดง · Mot Dang for Android

motdang.net in one window, plus what a web page cannot do on a phone:

- Live location that keeps sending with the screen locked. Start it from the
  site's Send button; the phone shows a notification with Stop until the hour
  is up. Positions go to motdang.net/api/live (mot-dang/publish/live.js).
- Shares from other apps land on the motdang map: a Google Maps link (short
  links opened out by /api/resolve), coordinates, a plus code, a place ID, a
  name, and geo: links from "open in maps".
- The phone's own share sheet for everything the site sends.

- Parking. ParkService notices where a bike or car was left (GPS only while
  moving, then steps and a minute of standing still confirm it), keeps the spot,
  a walk-away trail and a history, and posts "Parked here" with Not mine and
  Photo buttons. The page motdang.net/parking/ reads it through window.MotDangApp
  (parkState, parkHere, parkNote, parkClear, parkNotMine, parkHistory, parkWatch,
  parkWatchState, parkSetupNext, parkSetup, parkRemind, parkRemindClear, vibrate).
  ParkDetector, ParkBook and ParkText are plain Java with tests in test/.

Links off motdang.net (LINE, Grab, phone numbers) open in their own apps.

Build: `./build.sh` → `build/motdang.apk` (SDK build-tools 36, platform 36,
JDK 17; no Gradle; same toolchain as baan-launcher). The build runs
test/LinksTest.java, ParkDetectorTest.java and ParkBookTest.java. Updates must be signed with `motdang-app.keystore`.

Install: `adb install -r build/motdang.apk`, or open the .apk on the phone.

Code: MIT.
