# QuickDock — right-side quick launch drawer for Jetour G700

A small Android app that draws an **amber edge tab** on the right edge of the head-unit screen, over every other app.
Swipe left from the edge (or tap the tab) to open a drawer with your **5 favorite apps**.

## Behaviour
- **Open:** swipe left starting at the right edge, or tap the edge tab.
- **Auto-hide:** closes after **4 seconds** without touch (a thin bar at the top of the drawer counts down), or immediately when you tap outside the drawer.
- **Favorites:** a fixed grid of 5 slots. Empty slots show a dashed **+ Add app**. One tap launches the app and closes the drawer.
- **All apps:** a button at the bottom opens a full-screen grid of every installed app.
- **App Switcher:** the pages icon in the drawer header opens *Running apps*: tap a card to **restore** it, ✕ to **close** it, or **Close all**. QuickDock itself is never listed.
  - Android does not let a normal app read the system task list, so the list is built from usage statistics (apps that came to the foreground in the last 12 hours and were not closed from here). Needs: `appops set com.quickdock.app GET_USAGE_STATS allow` (the install page does it).
  - Closing works by ending the app's background process (going Home first if it is on screen). Apps running audio or navigation as a foreground service may not close.
- **Edit mode:** long-press an app or tap ✎ in the header.
  - Drag and drop to reorder; ✕ badge removes an app; tap an empty slot to pick from the full library.
  - The auto-hide timer is paused while editing and while the All apps grid is open.
  - **Done** saves to local storage; **Cancel** discards the changes.
- Favorites are saved on the device (SharedPreferences) and survive reboots. The tab comes back after the car starts (can be switched off in the app).

## Build
1. Create a new GitHub repository and upload the contents of this folder (including the hidden `.github` folder).
2. **Actions** tab → *Build QuickDock APK* runs automatically; download `QuickDock.apk` from **Releases**.
3. Optional: add a `SIGNING_KEY` secret (base64 of a `.jks`, alias `quickdock`, password `quickdock-sign-2026`) so updates install over the old version. Without it each build has a different key, so uninstall the old build first.
4. Optional: *Settings → Pages → Source: GitHub Actions* for the browser install page.

## Install on the car
Open the install page (`https://<user>.github.io/<repo>/`) in Chrome or Edge, connect the car by USB with ADB enabled, press install. It grants the overlay permission:

    appops set com.quickdock.app SYSTEM_ALERT_WINDOW allow

Open QuickDock once; it starts the edge tab (and starts it automatically after boot).

## Layout
The drawer is compact (about 250 dp wide, 5 slots of 56 dp) and centered on the right edge.

## Notes
- The swipe zone is a 34 dp strip on the right edge that sits above other apps, so that strip no longer reaches the app underneath.
- Not affiliated with Jetour, Chery or Autolink. May stop working after a system update.
