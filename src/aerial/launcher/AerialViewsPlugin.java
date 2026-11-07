// SPDX-License-Identifier: Apache-2.0
package aerial.launcher;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Opens Aerial Views when the Kiosk Satellite screensaver starts and brings the kiosk back in front when someone
 * shows up: camera motion or face, presence, the wake word, an intercom call or a Home Assistant sensor.
 *
 * Every piece of logic runs on the plugin's own scheduler thread. KS callbacks only hand work over to it, so they
 * always return well within the three-second deadline.
 */
public final class AerialViewsPlugin implements KioskPlugin {
    static final String DEFAULT_COMPONENT = "com.neilturner.aerialviews/.ui.screensaver.TestActivity";
    /** How long Android gets to bring the kiosk back before we try Shizuku or report a failure. */
    static final long RETURN_CHECK_MS = 1500;
    /** How long Android gets to show Aerial Views before we try Shizuku or report a failure. */
    static final long LAUNCH_CHECK_MS = 2500;
    static final long WAKE_DEBOUNCE_MS = 3000;
    /** FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_REORDER_TO_FRONT, for am start. */
    static final String REORDER_FLAGS = "0x10020000";

    private static final String[] EVENTS = {
        "screensaver.state", "screen.state",
        "detection.motion", "detection.face", "detection.presence", "detection.person", "detection.proximity",
        "wakeword.detected", "voice.state", "intercom.state",
    };

    private final Platform injectedPlatform;
    private final Scheduler injectedScheduler;

    private volatile PluginHost host;
    private volatile Scheduler scheduler;
    private volatile boolean alive;
    private Platform platform;

    // Settings. Written and read on the scheduler thread only.
    private boolean enabled = true;
    private String component = DEFAULT_COMPONENT;
    private boolean wakeMotion = true;
    private boolean wakeFace = true;
    private boolean wakePresence;
    private boolean wakeVoice = true;
    private String wakeEntity = "";
    private long graceMs = 5000;
    private String returnMethod = "Auto";
    private long maxMs;
    private boolean nightEnabled;
    private int nightStart = 23 * 60;
    private int nightEnd = 7 * 60;
    private boolean nightScreenOff = true;
    private boolean nightScreenOn = true;
    private TimeZone zone = TimeZone.getDefault();

    // Runtime state, scheduler thread only.
    private boolean away;
    private long awaySince;
    private long lastWake = Long.MIN_VALUE / 2;
    private boolean suppressUntilScreenOn;
    private boolean wasNight;
    private Scheduler.Task nightTimer;
    private String subscribedEntity = "";
    private String lastEntityState;
    private Scheduler.Task maxTimer;
    private Scheduler.Task returnCheck;
    private Scheduler.Task launchCheck;
    private String returnReason;
    private boolean returnScreenOff;
    private boolean shizukuBusy;
    private int attempt;
    private final List<String> unsupportedEvents = new ArrayList<>();

    public AerialViewsPlugin() {
        this(null, null);
    }

    /** For tests: a fake platform and a manual scheduler. */
    AerialViewsPlugin(Platform platform, Scheduler scheduler) {
        injectedPlatform = platform;
        injectedScheduler = scheduler;
    }

    // ---------------------------------------------------------------- KS callbacks, on the KS plugin worker

    @Override public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        alive = true;
        scheduler = injectedScheduler != null ? injectedScheduler
            : new ThreadScheduler((message, error) -> safeLog(message + ": " + error));
        Platform created = injectedPlatform != null ? injectedPlatform : AndroidPlatform.create();
        unsupportedEvents.clear();
        for (String event : EVENTS) {
            try {
                host.subscribe(event);
            } catch (RuntimeException unsupported) {
                // Older Kiosk Satellite releases lack some events. The rest keep working.
                unsupportedEvents.add(event);
            }
        }
        final Map<String, Object> copy = new HashMap<>(settings);
        scheduler.run(() -> {
            platform = created;
            if (platform != null) platform.setForegroundListener(() -> post(this::onKioskResumed));
            apply(copy);
            if (platform == null) {
                status("Cannot reach Android from the plugin. It cannot open anything.", true);
            } else {
                status(idleStatus(), false);
            }
        });
    }

    @Override public void configure(Map<String, Object> settings) {
        final Map<String, Object> copy = new HashMap<>(settings);
        post(() -> {
            apply(copy);
            if (!away) status(idleStatus(), false);
        });
    }

    @Override public void execute(String command, Map<String, Object> arguments) {
        if ("launchNow".equals(command)) {
            post(() -> launch(true));
        } else if ("returnToKiosk".equals(command)) {
            post(() -> {
                if (away) bringBack("command", false);
                else status("The kiosk is already in front.", false);
            });
        } else {
            throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    @Override public void onEvent(String event, Map<String, Object> payload) {
        final Map<String, Object> copy = payload == null ? Collections.emptyMap() : new HashMap<>(payload);
        post(() -> handle(event, copy));
    }

    @Override public void stop() {
        alive = false;
        Scheduler current = scheduler;
        if (current != null) current.close();
        Platform current2 = platform;
        if (current2 != null) current2.close();
        // KS has already revoked the host, so no unsubscribe here.
    }

    // ---------------------------------------------------------------- Logic, on the scheduler thread

    private void post(Runnable task) {
        Scheduler current = scheduler;
        if (alive && current != null) current.run(() -> {
            if (alive) task.run();
        });
    }

    private void apply(Map<String, Object> settings) {
        enabled = bool(settings, "enabled", true);
        String configured = string(settings, "component", DEFAULT_COMPONENT).trim();
        component = configured.isEmpty() ? DEFAULT_COMPONENT : configured;
        wakeMotion = bool(settings, "wakeMotion", true);
        wakeFace = bool(settings, "wakeFace", true);
        wakePresence = bool(settings, "wakePresence", false);
        wakeVoice = bool(settings, "wakeVoice", true);
        graceMs = Math.round(number(settings, "graceSeconds", 5) * 1000);
        returnMethod = string(settings, "returnMethod", "Auto");
        maxMs = Math.round(number(settings, "maxMinutes", 0) * 60_000);
        if (away) scheduleMaxTimer();
        followEntity(string(settings, "wakeEntity", "").trim());
        nightEnabled = bool(settings, "nightEnabled", false);
        nightStart = minutes(string(settings, "nightStart", "23:00"), 23 * 60);
        nightEnd = minutes(string(settings, "nightEnd", "07:00"), 7 * 60);
        nightScreenOff = bool(settings, "nightScreenOff", true);
        nightScreenOn = bool(settings, "nightScreenOn", true);
        // A settings change never turns the screen off or on by itself. Only crossing a boundary does.
        wasNight = isNight();
        scheduleNightTimer();
    }

    /** For tests. */
    void setTimeZone(TimeZone zone) {
        this.zone = zone;
    }

    static int minutes(String time, int fallback) {
        try {
            String[] parts = time.trim().split(":");
            int hours = Integer.parseInt(parts[0]);
            int mins = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            if (hours < 0 || hours > 23 || mins < 0 || mins > 59) return fallback;
            return hours * 60 + mins;
        } catch (RuntimeException invalid) {
            return fallback;
        }
    }

    static boolean inNight(int minuteOfDay, int start, int end) {
        if (start == end) return false;
        return start < end ? minuteOfDay >= start && minuteOfDay < end : minuteOfDay >= start || minuteOfDay < end;
    }

    private Calendar calendar(long time) {
        Calendar calendar = Calendar.getInstance(zone);
        calendar.setTimeInMillis(time);
        return calendar;
    }

    private boolean isNight() {
        if (!nightEnabled) return false;
        Calendar now = calendar(scheduler.now());
        return inNight(now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE), nightStart, nightEnd);
    }

    /** Milliseconds until the next occurrence of a minute of the day, in the kiosk's time zone. */
    private long untilMinute(int minuteOfDay) {
        long now = scheduler.now();
        Calendar next = calendar(now);
        next.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        next.set(Calendar.MINUTE, minuteOfDay % 60);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= now) next.add(Calendar.DAY_OF_MONTH, 1);
        return next.getTimeInMillis() - now;
    }

    private void scheduleNightTimer() {
        cancel(nightTimer);
        nightTimer = null;
        if (!nightEnabled || nightStart == nightEnd) return;
        long delay = Math.min(untilMinute(nightStart), untilMinute(nightEnd));
        // A second late, so the boundary minute has started even if the clock is slightly off.
        nightTimer = scheduler.later(delay + 1000, this::onNightBoundary);
    }

    private void onNightBoundary() {
        nightTimer = null;
        boolean night = isNight();
        if (night && !wasNight) enterNight();
        else if (!night && wasNight) leaveNight();
        wasNight = night;
        scheduleNightTimer();
    }

    private void enterNight() {
        if (away) {
            bringBack("night", nightScreenOff);
            return;
        }
        if (nightScreenOff) {
            suppressUntilScreenOn = true;
            command("screenOff");
            status("Night: screen turned off. Aerial Views stays closed until " + clock(nightEnd) + ".", false);
        } else {
            status("Night: Aerial Views stays closed until " + clock(nightEnd) + ".", false);
        }
    }

    private void leaveNight() {
        suppressUntilScreenOn = false;
        if (nightScreenOn) command("screenOn");
        status("Morning. " + idleStatus(), false);
        // If the screensaver already shows, Aerial Views takes over now instead of at the next one.
        try {
            host.executeCommand("isScreensaverActive", Collections.emptyMap(), (ok, data, error) -> {
                if (ok && Boolean.TRUE.equals(data)) post(this::onScreensaverStarted);
            });
        } catch (RuntimeException ignored) {
            // Not fatal: the next screensaver start opens Aerial Views.
        }
    }

    static String clock(int minuteOfDay) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    private void followEntity(String entity) {
        if (entity.equals(subscribedEntity)) return;
        PluginHost current = host;
        if (!subscribedEntity.isEmpty()) {
            try {
                current.unsubscribe("ha.entity." + subscribedEntity);
            } catch (RuntimeException ignored) {
                // Session ended or the subscription was already gone.
            }
        }
        subscribedEntity = "";
        lastEntityState = null;
        if (entity.isEmpty()) return;
        try {
            current.subscribe("ha.entity." + entity);
            subscribedEntity = entity;
        } catch (RuntimeException failed) {
            status("Cannot follow " + entity + ": " + failed.getMessage(), true);
        }
    }

    private void handle(String event, Map<String, Object> payload) {
        switch (event) {
            case "ks.screensaver.state":
                if (Boolean.TRUE.equals(payload.get("active"))) onScreensaverStarted();
                return;
            case "ks.screen.state":
                if (Boolean.TRUE.equals(payload.get("on"))) onScreenOn();
                return;
            case "ks.detection.motion":
                if (wakeMotion) wake("motion");
                return;
            case "ks.detection.face":
                if (wakeFace) wake("face");
                return;
            case "ks.detection.presence":
                if (wakePresence && Boolean.TRUE.equals(payload.get("present"))) wake("presence");
                return;
            case "ks.detection.person":
            case "ks.detection.proximity":
                if (wakePresence && !Boolean.TRUE.equals(payload.get("held"))) wake("presence");
                return;
            case "ks.wakeword.detected":
                if (wakeVoice) wake("wake word");
                return;
            case "ks.voice.state":
                Object state = payload.get("state");
                if (wakeVoice && state != null && !"idle".equals(state)) wake("voice assistant");
                return;
            case "ks.intercom.state":
                Object call = payload.get("state");
                if (wakeVoice && ("ringing".equals(call) || "listening".equals(call))) wake("intercom");
                return;
            case "shizuku.state":
                return;
            default:
                if (!subscribedEntity.isEmpty() && event.equals("ks.ha.entity." + subscribedEntity)) {
                    onEntity(payload);
                }
        }
    }

    private void onEntity(Map<String, Object> payload) {
        Object raw = payload.get("state");
        String state = raw == null ? null : String.valueOf(raw);
        String previous = lastEntityState;
        lastEntityState = state;
        // Only a change into an active state wakes, never the first snapshot: a sensor that stays on must not
        // close Aerial Views the moment it opens.
        if (previous != null && !active(previous) && active(state)) wake("sensor " + subscribedEntity);
    }

    static boolean active(String state) {
        return "on".equals(state) || "home".equals(state) || "open".equals(state) || "detected".equals(state);
    }

    private void onScreensaverStarted() {
        if (!enabled || platform == null) return;
        if (suppressUntilScreenOn) return;
        if (isNight()) {
            status("Night: Aerial Views stays closed until " + clock(nightEnd) + ".", false);
            return;
        }
        if (away && !platform.kioskInForeground()) return;
        launch(false);
    }

    private void onScreenOn() {
        if (!suppressUntilScreenOn) return;
        suppressUntilScreenOn = false;
        // With Wake to screensaver, KS shows its screensaver as the screen comes on. Follow it.
        PluginHost current = host;
        try {
            current.executeCommand("isScreensaverActive", Collections.emptyMap(), (ok, data, error) -> {
                if (ok && Boolean.TRUE.equals(data)) post(this::onScreensaverStarted);
            });
        } catch (RuntimeException ignored) {
            // Not fatal: the next screensaver start opens Aerial Views.
        }
    }

    private void launch(boolean manual) {
        if (platform == null) return;
        cancel(returnCheck);
        returnCheck = null;
        final int run = ++attempt;
        String error = platform.launch(component);
        if (error != null) {
            if (shizukuReady()) {
                launchWithShizuku(run);
            } else {
                status(error, true);
            }
            return;
        }
        markAway(manual ? "Aerial Views opened from the command." : "Aerial Views opened.");
        // Android can drop the request without an error. Check that the kiosk really went to the background.
        cancel(launchCheck);
        launchCheck = scheduler.later(LAUNCH_CHECK_MS, () -> {
            if (run != attempt || !away) return;
            if (platform.kioskInForeground()) {
                leaveAway();
                if (shizukuReady()) launchWithShizuku(run);
                else status("Aerial Views did not show. Check that it is installed and that Kiosk Mode allows other apps.", true);
            }
        });
    }

    private void launchWithShizuku(int run) {
        shizuku(new String[] {"/system/bin/am", "start", "-n", component}, ok -> {
            if (run != attempt) return;
            if (ok) markAway("Aerial Views opened with Shizuku.");
            else status("Aerial Views could not open, even with Shizuku.", true);
        });
    }

    private void markAway(String message) {
        away = true;
        returnReason = null;
        returnScreenOff = false;
        awaySince = scheduler.now();
        scheduleMaxTimer();
        status(message, false);
    }

    private void scheduleMaxTimer() {
        cancel(maxTimer);
        maxTimer = null;
        if (maxMs <= 0) return;
        long remaining = Math.max(0, awaySince + maxMs - scheduler.now());
        final int run = attempt;
        maxTimer = scheduler.later(remaining, () -> {
            if (run == attempt && away) bringBack("time limit", true);
        });
    }

    private void wake(String reason) {
        if (!away || platform == null) return;
        if (platform.kioskInForeground()) {
            onKioskResumed();
            return;
        }
        long now = scheduler.now();
        if (now - awaySince < graceMs) return;
        if (now - lastWake < WAKE_DEBOUNCE_MS) return;
        lastWake = now;
        bringBack(reason, false);
    }

    private void bringBack(String reason, boolean screenOff) {
        final int run = attempt;
        // The kiosk resuming confirms the return. The timed checks below only catch a blocked request.
        returnReason = reason;
        returnScreenOff = screenOff;
        if ("Shizuku".equals(returnMethod)) {
            bringBackWithShizuku(run);
            return;
        }
        cancel(returnCheck);
        final String[] error = new String[1];
        returnCheck = scheduler.later(RETURN_CHECK_MS, () -> {
            returnCheck = null;
            if (run != attempt || !away) return;
            if (platform.kioskInForeground()) {
                onKioskResumed();
            } else if ("Auto".equals(returnMethod) && shizukuReady()) {
                bringBackWithShizuku(run);
            } else {
                returnReason = null;
                status(returnBlocked(error[0]), true);
            }
        });
        error[0] = platform.bringKioskBack();
    }

    private String returnBlocked(String error) {
        StringBuilder message = new StringBuilder("Android blocked the return to the kiosk");
        if (error != null) message.append(" (").append(error).append(')');
        message.append(". ");
        if (!platform.canStartFromBackground()) {
            message.append("Grant Kiosk Satellite Display over other apps or set up Shizuku.");
        } else {
            message.append("Set up Shizuku for a guaranteed return.");
        }
        return message.toString();
    }

    private void bringBackWithShizuku(int run) {
        String kiosk = platform.kioskComponent();
        if (kiosk == null) {
            returnReason = null;
            status("Unknown kiosk activity, cannot return with Shizuku.", true);
            return;
        }
        cancel(returnCheck);
        returnCheck = null;
        shizuku(new String[] {"/system/bin/am", "start", "-n", kiosk, "-f", REORDER_FLAGS}, ok -> {
            if (run != attempt || !away) return;
            if (!ok) {
                returnReason = null;
                status("Could not return to the kiosk with Shizuku.", true);
                return;
            }
            cancel(returnCheck);
            returnCheck = scheduler.later(RETURN_CHECK_MS, () -> {
                returnCheck = null;
                if (run != attempt || !away) return;
                if (platform.kioskInForeground()) {
                    onKioskResumed();
                } else {
                    returnReason = null;
                    status("Shizuku started the kiosk but it did not come to the front.", true);
                }
            });
        });
    }

    /** A kiosk activity resumed: a wake brought the kiosk back, or the user left Aerial Views. */
    private void onKioskResumed() {
        if (!away) return;
        String reason = returnReason;
        boolean screenOff = returnScreenOff;
        leaveAway();
        if (reason == null) {
            status("Aerial Views closed. " + idleStatus(), false);
            return;
        }
        command("postponeScreensaver");
        if (screenOff) {
            suppressUntilScreenOn = true;
            command("screenOff");
            status("night".equals(reason)
                ? "Night: screen turned off. Aerial Views stays closed until " + clock(nightEnd) + "."
                : "Screen turned off after the time limit. Aerial Views opens again at the next screensaver.", false);
        } else {
            status("Back to the kiosk (" + reason + ").", false);
        }
    }

    private void leaveAway() {
        away = false;
        returnReason = null;
        returnScreenOff = false;
        cancel(maxTimer);
        cancel(launchCheck);
        cancel(returnCheck);
        maxTimer = null;
        launchCheck = null;
        returnCheck = null;
    }

    // ---------------------------------------------------------------- Host helpers

    private interface Done {
        void run(boolean ok);
    }

    private boolean shizukuReady() {
        try {
            Map<String, Object> state = host.shizukuState();
            return state != null && Boolean.TRUE.equals(state.get("granted"));
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private void shizuku(String[] command, Done done) {
        if (shizukuBusy) {
            // One command at a time. Retry shortly instead of failing.
            scheduler.later(400, () -> shizuku(command, done));
            return;
        }
        shizukuBusy = true;
        try {
            host.executeShizuku(command, 5000, (ok, data, error) -> post(() -> {
                shizukuBusy = false;
                boolean success = ok && data instanceof Map
                    && ((Map<?, ?>) data).get("exitCode") instanceof Number
                    && ((Number) ((Map<?, ?>) data).get("exitCode")).intValue() == 0;
                if (!success) safeLog("Shizuku " + String.join(" ", command) + ": " + (ok ? data : error));
                done.run(success);
            }));
        } catch (RuntimeException | LinkageError failed) {
            shizukuBusy = false;
            safeLog("Shizuku: " + failed);
            done.run(false);
        }
    }

    private void command(String name) {
        try {
            host.executeCommand(name, Collections.emptyMap(), (ok, data, error) -> {
                if (!ok) safeLog(name + " : " + error);
            });
        } catch (RuntimeException failed) {
            safeLog(name + " : " + failed);
        }
    }

    private String idleStatus() {
        StringBuilder text = new StringBuilder(enabled ? "Ready. Aerial Views opens at the next screensaver."
            : "Paused. Aerial Views only opens from the command.");
        if (nightEnabled && nightStart != nightEnd) {
            text.append(" Night hours: ").append(clock(nightStart)).append(" to ").append(clock(nightEnd)).append('.');
        }
        if (!unsupportedEvents.isEmpty()) {
            text.append(" Not supported by this Kiosk Satellite version: ")
                .append(String.join(", ", unsupportedEvents)).append('.');
        }
        if (platform != null && !platform.canStartFromBackground() && !shizukuReady()) {
            text.append(" Warning: without Display over other apps or Shizuku, Android may block the return to the kiosk.");
        }
        return text.toString();
    }

    private void status(String message, boolean error) {
        try {
            host.status(message, error);
        } catch (RuntimeException ignored) {
            // Session ended.
        }
        safeLog(message);
    }

    private void safeLog(String message) {
        try {
            PluginHost current = host;
            if (current != null) current.log(message);
        } catch (RuntimeException ignored) {
            // Session ended.
        }
    }

    private static void cancel(Scheduler.Task task) {
        if (task != null) task.cancel();
    }

    private static boolean bool(Map<String, Object> settings, String key, boolean fallback) {
        Object value = settings.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static String string(Map<String, Object> settings, String key, String fallback) {
        Object value = settings.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    private static double number(Map<String, Object> settings, String key, double fallback) {
        Object value = settings.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }
}
