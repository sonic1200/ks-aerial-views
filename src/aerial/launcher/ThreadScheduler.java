// SPDX-License-Identifier: Apache-2.0
package aerial.launcher;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** A single daemon thread owned by the plugin and shut down in stop(). */
final class ThreadScheduler implements Scheduler {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "aerial-views-launcher");
        thread.setDaemon(true);
        return thread;
    });
    private final PluginLog log;

    interface PluginLog {
        void error(String message, Throwable error);
    }

    ThreadScheduler(PluginLog log) {
        this.log = log;
    }

    @Override public void run(Runnable task) {
        try {
            executor.execute(guard(task));
        } catch (RejectedExecutionException stopped) {
            // The plugin is stopping.
        }
    }

    @Override public Task later(long delayMs, Runnable task) {
        try {
            ScheduledFuture<?> future = executor.schedule(guard(task), Math.max(0, delayMs), TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        } catch (RejectedExecutionException stopped) {
            return () -> {};
        }
    }

    @Override public long now() {
        return System.currentTimeMillis();
    }

    @Override public void close() {
        executor.shutdownNow();
    }

    private Runnable guard(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable error) {
                // Never let a background error take the whole app down.
                log.error("Internal error", error);
            }
        };
    }
}
