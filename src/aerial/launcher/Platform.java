// SPDX-License-Identifier: Apache-2.0
package aerial.launcher;

/** What the plugin needs from Android, so the logic can be tested without a device. */
interface Platform {
    /** Opens an activity given as package/class. Returns null on success, else a message. */
    String launch(String component);

    /** Asks Android to bring the kiosk's activity back in front. Returns null if the request went out. */
    String bringKioskBack();

    /** True while one of the kiosk's activities is resumed. */
    boolean kioskInForeground();

    /** The kiosk activity to bring back, as package/class, for Shizuku's am start. */
    String kioskComponent();

    /** True when Android lets the kiosk start activities from the background. */
    boolean canStartFromBackground();

    /** Runs whenever a kiosk activity comes back in front. Called on the main thread. */
    void setForegroundListener(Runnable listener);

    void close();
}
