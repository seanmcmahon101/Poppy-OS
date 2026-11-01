# Poppy OS

A home screen for Sean's Fire TV sticks: one photo of Poppy fills the screen, a clock sits centred at
the top, and the apps sit in a row along the bottom. No ads, no content rows, no internet permission.

It's a fork of [foyer](https://github.com/ro-hansolo/foyer) by Rohan (see [UPSTREAM.md](UPSTREAM.md)),
paired with [Home on Fire](https://github.com/toolicious/home-on-fire) to catch the Home button.
Everything is meant to be driven over adb, by a person or an agent, with `./homectl`.

## What changed from foyer

| | foyer | Poppy OS |
|---|---|---|
| Clock | big serif `H:mm`, top left, with the date | `HH:mm:ss`, centred, Google Sans Flex Light, tabular digits; date optional |
| Photo | drifts slowly, rotates hourly | holds still; Poppy is built in, rotation off |
| Inputs button + HDMI player | yes | gone (a stick has no inputs) |
| Default home row | Indian streaming apps | Netflix, Prime Video, YouTube, Stremio, Surfshark |
| adb control | photos folder only | `ControlReceiver`: settings, home row, labels, tile art, launch, UI, state as JSON |
| Tile art | 640x360 jpegs for ten Indian apps, built in | any app: pushed over adb (`homectl art`), or built in by package name |
| App id | `dev.rohan.foyer` | `com.sean.foyer` (Kotlin package unchanged, so upstream merges stay clean) |

The warm night tint (20:00 to 06:00), the frosted glass, the All apps drawer, Customize and the
Settings hop are upstream's and unchanged.

## Driving it: `homectl`

```sh
./homectl help                     # every command
./homectl get                      # settings, home row, apps, photos, what's on screen (JSON)
./homectl add iplayer 3            # BBC iPlayer into 3rd place on the home row
./homectl move stremio 1
./homectl label "com.bskyb.nowtv.beta" NOW
./homectl set seconds off          # HH:mm
./homectl apply                    # push config.json; ./homectl pull saves the TV's settings into it
./homectl diff                     # what apply would change, or "in sync"
./homectl wall "Poppy on the sofa.jpg"
./homectl art stremio stremio.jpg  # tile art for any app; `./homectl art stremio` puts its banner back
./homectl key down right ok        # the remote, from a terminal
./homectl -t living health         # the living-room Max; default is the bedroom test stick
./homectl -t all apply             # both sticks, one after the other
./homectl perf --walk              # frame times and memory, measured on the TV
```

`config.json` is the source of truth for settings. `homectl apply` sends it; `homectl pull` writes the
TV's live settings back after changing things with the remote or one-off commands, in exactly the
file's own shape, so `git diff` shows only what really changed. Foyer checks a whole file before
writing any of it: a typo is reported (every one, with where it is) and nothing changes.
`homectl health` includes the same comparison, so drift shows up with everything else.

Under the hood each command is one broadcast to a receiver guarded by `android.permission.DUMP`, which
on a retail Fire TV only `adb shell` holds, so no app on the TV can send it commands:

```sh
adb shell am broadcast -n com.sean.foyer/dev.rohan.foyer.ControlReceiver --es cmd add --es arg netflix
```

The reply comes back as the broadcast's result data. `Control.kt` has the full command list.
`FOYER_PKG=com.sean.foyer.dev ./homectl ...` drives the dev build instead.

### Tile art

Fire TV apps ship 320x180 banners, and Foyer uses those by default. For any app, a picture of any
size and shape can replace it: `./homectl art <app> <image>` pushes it to
`/sdcard/Pictures/Foyer/art/<package>.jpg` (or `.png`, `.webp`), and Foyer scales it to cover the tile,
crops the middle and swaps it in while it's on screen. `./homectl art <app>` takes it away again.

To build art into the APK instead, drop 640x360 jpegs into `app/local/res/drawable-nodpi/` (gitignored:
the logos aren't ours to give away) named `art_<package with dots as underscores>.jpg`, e.g.
`art_com_stremio_one.jpg`. Upstream's short names (`art_netflix.jpg`, `art_primevideo.jpg`, ...) still work.

## Build and install

Needs JDK 21 and the Android SDK (`sdk.dir` in `local.properties`). On Windows, build outputs go to
`%LOCALAPPDATA%\foyer-build` because OneDrive locks files mid-build.

```sh
./gradlew assembleRelease          # signed with ~/.config/foyer/keystore.properties if present
./gradlew testDebugUnitTest        # JVM tests: layout rules, focus walks, the clock
./homectl install                  # adb install -r + storage and background-start grants
./homectl apply
```

Home on Fire (first time per TV): install its release APK, then
`adb shell pm grant io.github.toolicious.homeonfire android.permission.WRITE_SECURE_SETTINGS`,
`adb shell settings put secure enabled_accessibility_services io.github.toolicious.homeonfire/.HijackService`,
then `./homectl hof-target` (picks Foyer on its screen; "launch on boot" is on by default) and
`./homectl health --home`. `health` without `--home` never touches the screen, so it's safe while
someone is watching.

`debloat.txt` is the list of Amazon packages kept disabled; `homectl health` checks it.

`./homectl perf` reports frame times per render stage against the TV's own refresh rate (read from
SurfaceFlinger), plus jank and memory, while you use the remote; `--walk` drives a fixed route along the
home row and through All apps itself, arrows and Back only. `./homectl logs` shows Foyer's recent log
lines and its last crash.

Never `am start -S` or `am force-stop` Foyer or Home on Fire: on Fire OS, killing an app drops its
accessibility service and Home goes back to Amazon.

## Licence

foyer is MIT or Apache 2.0 (your pick, [LICENSE-MIT](LICENSE-MIT), [LICENSE-APACHE](LICENSE-APACHE)),
and so are Sean's changes. Google Sans Flex is under the SIL OFL 1.1 ([licenses/](licenses/)).
Home on Fire is GPL-3.0 and isn't bundled. The photo of Poppy is Sean's.
