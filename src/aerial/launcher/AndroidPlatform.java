// SPDX-License-Identifier: Apache-2.0
package aerial.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Android side of the plugin. It only uses public Android APIs plus ActivityThread.currentApplication(), the usual
 * way for code without a Context to reach its own app. No Kiosk Satellite class is touched.
 */
final class AndroidPlatform implements Platform {
    private final Application app;
    private final Set<Activity> resumed = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Application.ActivityLifecycleCallbacks callbacks;
    private volatile boolean seenEvent;
    private volatile ComponentName lastKioskActivity;
    private volatile int lastTaskId = -1;
    private volatile Runnable foregroundListener;

    private AndroidPlatform(Application app) {
        this.app = app;
        callbacks = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                synchronized (resumed) {
                    seenEvent = true;
                    resumed.add(activity);
                }
                lastKioskActivity = activity.getComponentName();
                lastTaskId = activity.getTaskId();
                Runnable listener = foregroundListener;
                if (listener != null) listener.run();
            }

            @Override public void onActivityPaused(Activity activity) {
                synchronized (resumed) {
                    seenEvent = true;
                    resumed.remove(activity);
                }
            }

            @Override public void onActivityDestroyed(Activity activity) {
                synchronized (resumed) {
                    resumed.remove(activity);
                }
            }

            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        };
        app.registerActivityLifecycleCallbacks(callbacks);
    }

    /** Null when the app object cannot be found, which only happens outside an Android app process. */
    static AndroidPlatform create() {
        Application app = currentApplication();
        return app == null ? null : new AndroidPlatform(app);
    }

    private static Application currentApplication() {
        try {
            Object app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (app instanceof Application) return (Application) app;
        } catch (Throwable ignored) {
            // Fall through to the other public-ish entry point.
        }
        try {
            Object app = Class.forName("android.app.AppGlobals").getMethod("getInitialApplication").invoke(null);
            if (app instanceof Application) return (Application) app;
        } catch (Throwable ignored) {
            // Nothing else to try.
        }
        return null;
    }

    @Override public String launch(String component) {
        ComponentName name = ComponentName.unflattenFromString(component.trim());
        if (name == null) return "Invalid activity: " + component;
        Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            app.startActivity(intent);
            return null;
        } catch (ActivityNotFoundException missing) {
            return "Aerial Views not found. Install it or check the activity (" + component + ").";
        } catch (SecurityException denied) {
            return "Android refuses to open " + component + ": " + denied.getMessage();
        } catch (RuntimeException failed) {
            return "Cannot open: " + failed;
        }
    }

    @Override public String bringKioskBack() {
        int task = lastTaskId;
        if (task != -1) {
            try {
                ActivityManager manager = (ActivityManager) app.getSystemService(Application.ACTIVITY_SERVICE);
                if (manager != null) manager.moveTaskToFront(task, 0);
            } catch (RuntimeException ignored) {
                // Needs REORDER_TASKS, which the app may not declare. The intent below does the same job.
            }
        }
        Intent intent = kioskIntent();
        if (intent == null) return "Unknown kiosk activity";
        try {
            app.startActivity(intent);
            return null;
        } catch (RuntimeException failed) {
            return "Cannot return to the kiosk: " + failed;
        }
    }

    private Intent kioskIntent() {
        ComponentName last = lastKioskActivity;
        if (last != null) {
            return new Intent(Intent.ACTION_MAIN).setComponent(last)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        }
        Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        if (launch == null) return null;
        return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    @Override public boolean kioskInForeground() {
        synchronized (resumed) {
            // Until the first lifecycle event, assume the kiosk is in front: the plugin starts with the app.
            return !seenEvent || !resumed.isEmpty();
        }
    }

    @Override public String kioskComponent() {
        ComponentName last = lastKioskActivity;
        if (last != null) return last.flattenToShortString();
        Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        if (launch != null && launch.getComponent() != null) return launch.getComponent().flattenToShortString();
        return null;
    }

    @Override public boolean canStartFromBackground() {
        if (Build.VERSION.SDK_INT < 29) return true;
        try {
            return Settings.canDrawOverlays(app);
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    @Override public void setForegroundListener(Runnable listener) {
        foregroundListener = listener;
    }

    @Override public void close() {
        foregroundListener = null;
        try {
            app.unregisterActivityLifecycleCallbacks(callbacks);
        } catch (RuntimeException ignored) {
            // Already gone.
        }
    }
}
