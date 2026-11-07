// SPDX-License-Identifier: Apache-2.0
package aerial.launcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.jxl.kiosk.plugins.PluginHost;

/** Runs the launcher against a fake KS host, a fake Android and a manual clock. */
public final class AerialViewsPluginTest {
    static final class Clock implements Scheduler {
        long time = 1_000_000;
        final List<long[]> due = new ArrayList<>();
        final List<Runnable> tasks = new ArrayList<>();
        final Set<Runnable> cancelled = new HashSet<>();

        @Override public void run(Runnable task) { task.run(); }
        @Override public Task later(long delayMs, Runnable task) {
            due.add(new long[] {time + delayMs, tasks.size()});
            tasks.add(task);
            return () -> cancelled.add(task);
        }
        @Override public long now() { return time; }
        @Override public void close() {}

        void advance(long ms) {
            long end = time + ms;
            while (true) {
                long[] next = null;
                for (long[] entry : due) if (entry[0] <= end && (next == null || entry[0] < next[0])) next = entry;
                if (next == null) break;
                due.remove(next);
                time = next[0];
                Runnable task = tasks.get((int) next[1]);
                if (!cancelled.contains(task)) task.run();
            }
            time = end;
        }
    }

    static final class Android implements Platform {
        boolean foreground = true;
        boolean returnWorks = true;
        boolean overlays = true;
        String launchError;
        int launches;
        int returns;
        String lastComponent;
        Runnable listener;

        @Override public String launch(String component) {
            launches++;
            lastComponent = component;
            if (launchError != null) return launchError;
            foreground = false;
            return null;
        }
        @Override public String bringKioskBack() {
            returns++;
            if (returnWorks) resume();
            return null;
        }
        void resume() {
            foreground = true;
            if (listener != null) listener.run();
        }
        @Override public boolean kioskInForeground() { return foreground; }
        @Override public String kioskComponent() { return "me.jxl.kiosk_satellite/.MainActivity"; }
        @Override public boolean canStartFromBackground() { return overlays; }
        @Override public void setForegroundListener(Runnable listener) { this.listener = listener; }
        @Override public void close() {}
    }

    static final class Host implements PluginHost {
        final Set<String> subscriptions = new HashSet<>();
        final List<String> commands = new ArrayList<>();
        final List<String[]> shizukuCommands = new ArrayList<>();
        boolean shizukuGranted;
        boolean shizukuSucceeds = true;
        Runnable onShizuku;
        String status = "";
        boolean statusError;
        Boolean screensaverActive;

        @Override public void subscribe(String event) {
            if ("intercom.state".equals(event)) throw new IllegalArgumentException("unknown event");
            subscriptions.add(event);
        }
        @Override public void unsubscribe(String event) { subscriptions.remove(event); }
        @Override public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) {
            commands.add(command);
            if ("isScreensaverActive".equals(command)) callback.onResult(true, screensaverActive, null);
            else callback.onResult(true, null, null);
        }
        @Override public Map<String, Object> shizukuState() {
            return Collections.singletonMap("granted", shizukuGranted);
        }
        @Override public void executeShizuku(String[] command, int timeoutMs, CommandCallback callback) {
            shizukuCommands.add(command);
            if (onShizuku != null) onShizuku.run();
            callback.onResult(true, Collections.singletonMap("exitCode", shizukuSucceeds ? 0 : 1), null);
        }
        @Override public void status(String message, boolean error) { status = message; statusError = error; }
        @Override public void showWindow(String title, String message, String buttonLabel) {}
        @Override public void hideWindow() {}
        @Override public void log(String message) {}
    }

    static Map<String, Object> settings(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        values.put("enabled", true);
        values.put("component", AerialViewsPlugin.DEFAULT_COMPONENT);
        values.put("wakeMotion", true);
        values.put("wakeFace", true);
        values.put("wakePresence", false);
        values.put("wakeVoice", true);
        values.put("wakeEntity", "");
        values.put("graceSeconds", 5);
        values.put("returnMethod", "Auto");
        values.put("maxMinutes", 0);
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return values;
    }

    static Map<String, Object> payload(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return values;
    }

    final Clock clock = new Clock();
    final Android android = new Android();
    final Host host = new Host();
    final AerialViewsPlugin plugin = new AerialViewsPlugin(android, clock);

    void start(Map<String, Object> settings) throws Exception {
        plugin.start(host, settings);
    }

    void screensaver() throws Exception {
        plugin.onEvent("ks.screensaver.state", payload("active", true));
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        new AerialViewsPluginTest().launchesAndReturnsOnMotion();
        new AerialViewsPluginTest().userClosingAerialViewsIsNoticed();
        new AerialViewsPluginTest().blockedReturnFallsBackToShizuku();
        new AerialViewsPluginTest().blockedReturnWithoutShizukuExplains();
        new AerialViewsPluginTest().homeAssistantSensorWakesOnlyOnChange();
        new AerialViewsPluginTest().timeoutTurnsScreenOffAndWaitsForScreenOn();
        new AerialViewsPluginTest().disabledOnlyOpensFromCommand();
        new AerialViewsPluginTest().launchErrorIsReported();
        new AerialViewsPluginTest().silentLaunchFailureUsesShizuku();
        new AerialViewsPluginTest().wakeSourcesFollowSettings();
        System.out.println("AerialViewsPluginTest: all tests passed");
    }

    void launchesAndReturnsOnMotion() throws Exception {
        start(settings());
        check(host.subscriptions.contains("detection.motion"), "subscribes to motion");
        check(host.status.contains("intercom.state"), "reports events this KS lacks: " + host.status);
        screensaver();
        check(android.launches == 1, "opens Aerial Views");
        check(AerialViewsPlugin.DEFAULT_COMPONENT.equals(android.lastComponent), "opens the TestActivity");
        screensaver();
        check(android.launches == 1, "does not open it twice");
        clock.advance(2000);
        plugin.onEvent("ks.detection.motion", payload());
        check(android.returns == 0, "ignores motion during the grace period");
        clock.advance(4000);
        plugin.onEvent("ks.detection.motion", payload());
        check(android.returns == 1, "brings the kiosk back on motion");
        clock.advance(AerialViewsPlugin.RETURN_CHECK_MS);
        check(host.commands.contains("postponeScreensaver"), "resets the idle timer");
        check(host.status.contains("motion") && !host.statusError, "reports the return: " + host.status);
        plugin.onEvent("ks.detection.motion", payload());
        check(android.returns == 1, "no return while the kiosk is in front");
        screensaver();
        check(android.launches == 2, "opens again at the next screensaver");
    }

    void userClosingAerialViewsIsNoticed() throws Exception {
        start(settings());
        screensaver();
        clock.advance(10_000);
        android.resume();
        check(host.status.startsWith("Aerial Views closed"), "notices the user closed it: " + host.status);
        plugin.onEvent("ks.detection.face", payload());
        check(android.returns == 0, "no return needed");
    }

    void blockedReturnFallsBackToShizuku() throws Exception {
        host.shizukuGranted = true;
        android.returnWorks = false;
        host.onShizuku = () -> {
            String[] command = host.shizukuCommands.get(host.shizukuCommands.size() - 1);
            if (Arrays.asList(command).contains(AerialViewsPlugin.REORDER_FLAGS)) android.resume();
        };
        start(settings());
        screensaver();
        clock.advance(10_000);
        plugin.onEvent("ks.detection.face", payload());
        check(android.returns == 1, "tries Android first");
        clock.advance(AerialViewsPlugin.RETURN_CHECK_MS);
        check(host.shizukuCommands.size() == 1, "falls back to Shizuku");
        check(Arrays.asList(host.shizukuCommands.get(0)).contains("me.jxl.kiosk_satellite/.MainActivity"), "starts the kiosk");
        clock.advance(AerialViewsPlugin.RETURN_CHECK_MS);
        check(host.status.contains("face") && !host.statusError, "returned with Shizuku: " + host.status);
    }

    void blockedReturnWithoutShizukuExplains() throws Exception {
        android.returnWorks = false;
        android.overlays = false;
        start(settings());
        screensaver();
        clock.advance(10_000);
        plugin.onEvent("ks.wakeword.detected", payload());
        clock.advance(AerialViewsPlugin.RETURN_CHECK_MS);
        check(host.statusError && host.status.contains("Display over other apps"), "explains the fix: " + host.status);
        android.resume();
        check(host.status.startsWith("Aerial Views closed"), "still notices a later manual exit: " + host.status);
    }

    void homeAssistantSensorWakesOnlyOnChange() throws Exception {
        start(settings("wakeEntity", "binary_sensor.salon"));
        check(host.subscriptions.contains("ha.entity.binary_sensor.salon"), "follows the sensor");
        screensaver();
        clock.advance(10_000);
        plugin.onEvent("ks.ha.entity.binary_sensor.salon", payload("state", "on"));
        check(android.returns == 0, "the first snapshot does not wake");
        plugin.onEvent("ks.ha.entity.binary_sensor.salon", payload("state", "off"));
        plugin.onEvent("ks.ha.entity.binary_sensor.salon", payload("state", "on"));
        check(android.returns == 1, "off to on wakes");
        plugin.configure(settings("wakeEntity", ""));
        check(!host.subscriptions.contains("ha.entity.binary_sensor.salon"), "stops following a cleared sensor");
    }

    void timeoutTurnsScreenOffAndWaitsForScreenOn() throws Exception {
        start(settings("maxMinutes", 30));
        screensaver();
        clock.advance(30 * 60_000);
        check(android.returns == 1, "comes back after the delay");
        clock.advance(AerialViewsPlugin.RETURN_CHECK_MS);
        check(host.commands.contains("screenOff"), "turns the screen off");
        screensaver();
        check(android.launches == 1, "no Aerial Views while the screen is off");
        host.screensaverActive = true;
        plugin.onEvent("ks.screen.state", payload("on", true, "source", "app"));
        check(android.launches == 2, "follows the screensaver once the screen is on again");
    }

    void disabledOnlyOpensFromCommand() throws Exception {
        start(settings("enabled", false));
        screensaver();
        check(android.launches == 0, "disabled: the screensaver alone does nothing");
        plugin.execute("launchNow", Collections.emptyMap());
        check(android.launches == 1, "the command still opens it");
        clock.advance(10_000);
        plugin.execute("returnToKiosk", Collections.emptyMap());
        check(android.returns == 1, "the command brings the kiosk back without waiting for a detection");
    }

    void launchErrorIsReported() throws Exception {
        android.launchError = "Aerial Views not found.";
        start(settings());
        screensaver();
        check(host.statusError && host.status.contains("not found"), "reports a missing app: " + host.status);
        plugin.onEvent("ks.detection.motion", payload());
        check(android.returns == 0, "never away, so nothing to return from");
    }

    void silentLaunchFailureUsesShizuku() throws Exception {
        // Android accepts the request but nothing shows.
        AerialViewsPlugin quiet = new AerialViewsPlugin(new Platform() {
            @Override public String launch(String component) { return null; }
            @Override public String bringKioskBack() { return null; }
            @Override public boolean kioskInForeground() { return true; }
            @Override public String kioskComponent() { return "k/.Main"; }
            @Override public boolean canStartFromBackground() { return true; }
            @Override public void setForegroundListener(Runnable listener) {}
            @Override public void close() {}
        }, clock);
        Host other = new Host();
        other.shizukuGranted = true;
        quiet.start(other, settings());
        quiet.onEvent("ks.screensaver.state", payload("active", true));
        clock.advance(AerialViewsPlugin.LAUNCH_CHECK_MS);
        check(other.shizukuCommands.size() == 1, "retries with Shizuku");
        check(Arrays.asList(other.shizukuCommands.get(0)).contains(AerialViewsPlugin.DEFAULT_COMPONENT), "am start Aerial Views");
    }

    void wakeSourcesFollowSettings() throws Exception {
        start(settings("wakeMotion", false, "wakePresence", true));
        screensaver();
        clock.advance(10_000);
        plugin.onEvent("ks.detection.motion", payload());
        check(android.returns == 0, "motion off");
        plugin.onEvent("ks.detection.person", payload("held", true));
        check(android.returns == 0, "continuing presence does not wake");
        plugin.onEvent("ks.detection.person", payload("held", false));
        check(android.returns == 1, "arrival wakes");
        check(AerialViewsPlugin.active("on") && !AerialViewsPlugin.active("off"), "active states");
    }
}
