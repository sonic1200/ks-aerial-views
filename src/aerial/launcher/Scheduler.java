// SPDX-License-Identifier: Apache-2.0
package aerial.launcher;

/** Runs every piece of plugin logic on one thread, so the state needs no locks. */
interface Scheduler {
    interface Task {
        void cancel();
    }

    void run(Runnable task);

    Task later(long delayMs, Runnable task);

    long now();

    void close();
}
