<div align="center">

<!-- hero: screen recording of foyer at 2x. gif so it plays inline on github; click through for the mp4 -->
<a href="assets/foyer-demo.mp4"><img src="assets/foyer-demo.gif" alt="foyer on a fire tv, sped up 2x. one of my photos fills the screen, a big serif clock sits top left, and a row of app tiles runs along the bottom." width="900"></a>

# foyer

**a home screen for fire tv that only opens apps.**

[![licence: mit or apache-2.0](https://img.shields.io/badge/licence-mit%20or%20apache--2.0-3a4a5e?style=flat-square&labelColor=0a0e14)](#licence)
[![fire os 8](https://img.shields.io/badge/fire%20os-8-3a4a5e?style=flat-square&labelColor=0a0e14)](#install-it)
[![kotlin and compose](https://img.shields.io/badge/kotlin-compose-3a4a5e?style=flat-square&labelColor=0a0e14)](#how-it-got-built)

[why](#why-this-exists) &nbsp;·&nbsp; [what it does](#what-it-does) &nbsp;·&nbsp; [numbers](#numbers) &nbsp;·&nbsp; [install it](#install-it) &nbsp;·&nbsp; [photos](#photos) &nbsp;·&nbsp; [how it got built](#how-it-got-built)

</div>

foyer is a launcher for fire tv. one of your photos fills the screen, there's a big clock, and your apps sit in a row along the bottom. that's the whole thing. no ads, no content rows, no recommendations, no search. it doesn't even ask for internet access.

i built it for my bedroom tv, a xiaomi fx pro 43 running fire os 8.1 with about 1.8 gb of ram and a 32 bit userspace. foyer sits at about 82 mb, 0.1% of its frames are janky, and it's back on screen less than 300 ms after you leave netflix.

this is something i built for myself, for my tv. it's not a product. there's no roadmap, it's only been tested on that one tv, and nobody's on the hook if it breaks yours. it's open source because it shows what i build when something pisses me off. if your fire tv pisses you off the same way, it'll probably work for you too, and you're welcome to fork it and make it yours. fuck fire os.

## why this exists

the fire os home screen is 80% ad. that's with every ad setting i could find already turned off. fire tv remotes don't come with an input button either, so switching to hdmi means going through this ad first, every single time.

<p align="center">
<img src="assets/fire-os-home-ads.jpg" alt="the stock fire os home screen on my tv. almost the whole screen is an amazon great indian festival ad with a learn more button, and the apps are squeezed into one small row along the bottom." width="700">
</p>

that's my problem with a lot of ux right now. it's built to show you more ads, not to be nicer to use. when seeing an ad is at odds with a better experience, the ad wins. i paid for this tv and it kept trying to sell me shit, and it pissed me off enough that i built my own fucking launcher.

you can't just swap the launcher. amazon's launcher is a system app, and disabling it needs root. these xiaomi tvs can't be rooted. the bootloader is locked, verified boot reports green, and nobody has built a custom rom for them. the only way out without root is [home on fire](https://github.com/toolicious/home-on-fire). it's an accessibility service that catches the home button, boot and wake, and sends them to whatever launcher you pick. foyer still needs it.

so i tried the launchers that already exist. projectivy's free version won't let you set your own wallpaper or icons. that's premium, and i wasn't paying $7.49 for a wallpaper. (i then spent $55 in anthropic credits building my own, which i'm aware is funny.) atv launcher is closed source and paywalls the same thing. flauncher works but it's bare. arc was the best free one, basically an apple tv clone, but it's flutter and sat at about 178 mb. fire os killed it after a youtube session, and after that every home press showed amazon's ads for about 3 seconds while arc cold started. the one thing i wanted gone came back every time i pressed home.

buying a box made no sense either. there's already an apple tv 4k in my living room, and after all the fire os nonsense i was going to get another one for the bedroom. i paid ₹16,900 for mine. the exact same box is ₹31,900 now. same 2022 hardware, almost double the price. fuck the rampocalypse. a ₹32k box on a ₹26k tv is fucking stupid. the google tv streamer isn't sold here, neither is walmart's onn box, and i'm not buying some spyware infested chinese android box. a raspberry pi has no widevine l1, so netflix and prime drop to sd. meanwhile the fire tv itself is l1 certified and plays everything perfectly. the tv was fine. the home screen was the problem.

so i went full thanos. fine, i'll do it myself. i kept amazon's certified hardware and replaced the home screen. all the android tv launchers were too bloated. i just wanted a homescreen that opens apps.

<p align="center">
<img src="assets/thanos-fine-ill-do-it-myself.jpg" alt="thanos meme. top panel: his gauntlet with the caption &quot;fine.&quot; bottom panel: thanos putting it on, captioned &quot;i'll do it myself.&quot;" width="360">
</p>

## what it does

- one of your photos fills the screen and drifts very slowly. it changes every 5 minutes, every hour, every day, or never.
- a big instrument serif clock sits top left with the date under it.
- your favourite apps are big tiles along the bottom. press right past the last one and the rest of your apps slide up in a drawer.
- three buttons sit top right. inputs switches hdmi, customize picks your photos and how often they change, and settings opens amazon's real settings grid.
- press menu on a tile, or hold ok, to move it, add it to home, take it off home or hide it.
- after 8 pm the whole screen warms up, so it doesn't blast blue light at you in bed.
- amazon's app store, screensaver, profile picker and early access app never show up.
- every button has a test tag exposed as a resource id, so `uiautomator dump` reads like a map and an agent can drive the whole thing. arc couldn't do that. flutter draws everything itself, so there's no accessibility tree to read.

## numbers

measured on the tv itself, a 50 hz panel, on the release build with its baseline profile.

| what | measured |
|---|---|
| memory at idle | about 82 mb (arc sat at about 178 mb) |
| janky frames, 22 presses across the shelf and drawer | 0.1% |
| frame time while the photo drifts | about 6 ms of a 20 ms budget |
| frame time opening the drawer | about 9 ms |
| cold start | about 550 ms |
| back from netflix, youtube or spotify | 250 to 295 ms |
| apk | about 2 mb |

how it stays that small:

- no material 3, no tv-material and no coil. it's plain compose foundation and a small image loader that decodes every bitmap at the size it's drawn.
- there's no live blur. the glass on the buttons and sheets is a pre-blurred 160x90 copy of the current photo.
- the dark gradients behind the clock and the shelf are baked into the photo bitmap once, so each frame draws one image instead of three layers.
- the photo isn't drawn at all while an opaque sheet covers it.
- the drift and the fades move in tiny steps through `derivedStateOf`, so compose only redraws when a pixel would actually change.
- it ships a baseline profile and only `armeabi-v7a`, because the tv is 32 bit anyway.

## the fire os bullshit

amazon locks a lot of this down, and the workarounds are the fun part.

**settings.** amazon's main settings grid lives inside amazon's launcher, so home on fire sees it open and bounces you straight back to foyer. foyer opens a screen in amazon's settings app first and hops to the grid about a second later. home on fire leaves amazon's launcher alone when you get there from one of amazon's own screens right after pressing ok, and the hop lands inside that window. android blocks apps from starting screens once they're in the background, so foyer needs the "display over other apps" permission to make the hop. it never draws over anything.

**inputs.** every way amazon switches hdmi input is locked to apps signed by amazon. so foyer plays hdmi in its own full screen `TvView`. picture, sound, hdr and the picture settings all work. the remote doesn't pass through to the hdmi device over cec, which i can live with.

**photos.** fire os 8 won't let adb write into an app's private folder, so photos go in `/sdcard/Pictures/Foyer/`, and foyer reads them with a storage permission you grant once.

**the home button.** that's all home on fire. amazon's home flashes up for about a second, then foyer is back.

## install it

you need adb and a fire tv with adb debugging switched on, which lives in settings > my fire tv > developer options. to build it you need jdk 21 and the android sdk, with `sdk.dir=...` in `local.properties`.

1. build foyer and install it:

   ```sh
   export ANDROID_SERIAL=<tv-ip>:5555
   adb connect $ANDROID_SERIAL
   ./gradlew installRelease
   ```

   without your own keystore in `~/.config/foyer/keystore.properties`, the release build is signed with the debug key.

2. let it read your photos and make the settings hop:

   ```sh
   adb shell pm grant dev.rohan.foyer android.permission.READ_EXTERNAL_STORAGE
   adb shell appops set dev.rohan.foyer SYSTEM_ALERT_WINDOW allow
   ```

3. install [home on fire](https://github.com/toolicious/home-on-fire) and follow its readme to switch on its accessibility service. pick foyer as the target, then turn on "replace home button" and "launch on boot".

without home on fire, foyer still works as an app you open. it just won't come back when you press home.

### app art

fire tv apps ship small 320x180 banners, and foyer uses those by default. for sharper tiles on the big apps, drop 640x360 jpegs into `app/local/res/drawable-nodpi/` and rebuild. name them `art_<key>.jpg`, where the key is one of `netflix`, `primevideo`, `jiohotstar`, `youtube`, `appletv`, `spotify`, `sonyliv`, `zee5`, `discoveryplus` or `xstream`. that folder is gitignored, because those logos aren't mine to give away.

## photos

foyer was built to be driven by an agent, so there's no upload server, no phone app and nothing to tap through. you push files over adb:

```sh
adb shell mkdir -p /sdcard/Pictures/Foyer
adb push "Pangong Tso, Ladakh.jpg" /sdcard/Pictures/Foyer/
```

- the file name is the caption. `Pangong Tso, Ladakh.jpg` shows "Pangong Tso, Ladakh" in the corner. camera names like `IMG_2041` or `PXL_20260412_0931` show nothing.
- new photos show up within a minute, even while foyer is on screen.
- delete a file and it drops out of the rotation on the next scan. names with spaces need nested quotes: `adb shell 'rm "/sdcard/Pictures/Foyer/Pangong Tso, Ladakh.jpg"'`.
- landscape at 1920x1080 or bigger looks best. foyer crops to 16:9 and caches its own copy.
- with no photos at all you get a dusk over some hills, so the screen is never blank.

that's how i get iris, my ai assistant, put my album on the tv. she pushed it over my tailnet with adb, and foyer picked it up within a minute.

## how it got built

i didn't open android studio once. agents built foyer in [cmx2](https://github.com/clauseo-ai/cmx2), my harness, on a headless linux vm with no screen and no emulator.

- google's android cli installed and managed the sdk.
- [roborazzi](https://github.com/takahirom/roborazzi) rendered every screen on the jvm, so the agents could look at what they built and walk the focus with a fake d-pad.
- everything else ran on two real tvs over adb across my tailnet. the performance numbers come from `dumpsys gfxinfo` framestats on the actual panel, parsed by `scripts/framestats.py`.

it went from an html mockup to running on the tv overnight. then i lived with it for a day, sent feedback from the couch, and the agents fixed things while i watched.

## working on it

```sh
./gradlew testDebugUnitTest        # jvm tests: layout rules, captions, d-pad focus walks
./gradlew recordRoborazziDebug     # renders every screen into app/build/outputs/roborazzi/
./gradlew installDev               # "Foyer dev", installs next to the real one
adb shell dumpsys gfxinfo dev.rohan.foyer.dev framestats | python3 scripts/framestats.py 20
```

the last one prints frame times per stage. the 20 is the frame budget in ms, which is what a 50 hz panel gives you.

`FoyerModel.kt` holds all the state. `ui/` is the compose screens. `data/` has apps, photos, the image loader and the fire os glue in `Platform.kt`. it's kotlin 2.4, compose bom 2026.09, agp 9.4 and min sdk 30, because fire os 8 is android 11.

## licence

your pick of [mit](LICENSE-MIT) or [apache 2.0](LICENSE-APACHE).

the fonts are [instrument serif](https://github.com/Instrument/instrument-serif) and [google sans flex](https://github.com/googlefonts/googlesans-flex), both under the sil open font license 1.1, with the texts in [licenses/](licenses/). the icons are [material symbols](https://github.com/google/material-design-icons), apache 2.0. [home on fire](https://github.com/toolicious/home-on-fire) handles the home button. it's gpl-3.0 and isn't bundled. foyer has nothing to do with amazon, and the app names and logos on the tiles belong to their owners.
