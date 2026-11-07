# Aerial Views Launcher

A Kiosk Satellite plugin that uses the [Aerial Views](https://github.com/theothernt/AerialViews) app as the screensaver.

- When the Kiosk Satellite screensaver starts, Aerial Views opens on top, straight into its screensaver.
- When someone shows up, the kiosk comes back to the front. The return is triggered by motion or a face (Kiosk Satellite's camera), presence, the wake word, a voice conversation, an intercom call or a Home Assistant sensor of your choice.
- Tapping Aerial Views closes it and brings the kiosk back, once the gesture is set in Aerial Views (see below).
- Optionally, the screen turns off after a delay spent in Aerial Views.

## Installation

First install [Aerial Views](https://github.com/theothernt/AerialViews) on the tablet.

### Install button

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=sonic1200/ks-aerial-views)

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/sonic1200/ks-aerial-views` and choose **Preview**.
3. Review the plugin, choose **Trust and install**, then enable **Aerial Views Launcher** from its entry row.

## Recommended Kiosk Satellite settings

| Setting | Why |
| --- | --- |
| **Screensaver** on, mode **Black** | The KS screensaver only triggers Aerial Views. Black avoids a flash before it opens. |
| **Camera**: motion and/or face detection on | These are the main wake sources. |
| **Background listening** on | While Aerial Views shows, Kiosk Satellite runs in the background. This setting keeps the camera and the wake word running. |
| Android permission **Display over other apps** for Kiosk Satellite | Android 10 and later stop a background app from coming back to the front without it. |
| *Optional*: **Shizuku** with access granted to Kiosk Satellite | Guaranteed return even when Android blocks the other method. The plugin falls back to it on its own (**Auto** method). |
| Kiosk Mode: allow Aerial Views | If Kiosk Mode locks the screen to KS, Android refuses to open another app. |

## Required Aerial Views setting

By default, Aerial Views **ignores** taps on the screen. To make a tap close it and bring the kiosk back:

**Aerial Views > D-Pad/Remote, Tap & Swipe Gestures > Screen Swipe & Tap Gestures > Single Tap**: choose the action that exits the screensaver (**Exit**), not "Exit to settings".

You don't need to turn on "Start screensaver on launch": the plugin opens the screensaver activity (`TestActivity`) directly.

## Plugin settings

| Setting | Effect |
| --- | --- |
| Open Aerial Views with the screensaver | Main switch. When off, Aerial Views only opens from the **Open Aerial Views** action. |
| Activity to open | Defaults to `com.neilturner.aerialviews/.ui.screensaver.TestActivity`. |
| Return on motion / a face / presence or proximity / the wake word or a call | Kiosk Satellite wake sources. |
| Home Assistant sensor | The kiosk comes back when this sensor turns `on`. Most reliable if the tablet stops the camera in the background. |
| Ignore detections after opening | Grace period, 5 s by default, so motion right at the start doesn't close Aerial Views. |
| Return method | **Auto** (Android, then Shizuku), **Android** or **Shizuku**. |
| Turn the screen off after | After this delay, returns to the kiosk and turns the screen off. Aerial Views opens again at the next screensaver. 0 = never. |

Actions available to gestures, the kiosk drawer and Home Assistant buttons: **Open Aerial Views** and **Return to the kiosk**.

The plugin's state shows on its page in Plugin Manager: ready, open, returned and why, or what is blocking it.

## Data and permissions

The plugin sends nothing over the network. It reads Kiosk Satellite's screensaver, screen and detection state (`host.read`), uses its transient controls such as resetting the screensaver timer or turning the screen off (`host.control`), and runs `am start` through Shizuku when you have set it up (`shizuku`). The optional Home Assistant sensor is read through Kiosk Satellite's existing connection.

## Limitations

- Camera wake requires Kiosk Satellite to keep the camera running in the background. With background listening it does on most devices, but not all (some Samsung devices, for example). On those, use a Home Assistant sensor.
- Kiosk Satellite alarms are not visible to plugins: the plugin does not return to the kiosk on its own when an alarm rings.
- Besides the Kiosk Satellite SDK, the plugin uses public Android APIs (opening an activity, following which app is in front). It touches no internal Kiosk Satellite class.

## Building

JDK 17+ and an Android SDK with `platforms;android-35` and `build-tools`:

```sh
python3 tools/test.py
python3 tools/build.py --android-platform 35
```

The ZIP is created in `dist/`.

## Disclaimer

This plugin is not affiliated with Aerial Views or Kiosk Satellite. It only opens the Aerial Views app installed on the device.
