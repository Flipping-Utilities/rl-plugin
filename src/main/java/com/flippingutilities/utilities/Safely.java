package com.flippingutilities.utilities;

import lombok.extern.slf4j.Slf4j;

/**
 * Guards the runnables runelite invokes on the plugin's behalf (client thread tasks, scheduled
 * executor tasks) so one failing step logs and lets the remaining steps run instead of aborting
 * the whole task.
 * <p>
 * This matters because runelite drops a client thread task the moment it throws (nothing after the
 * throwing line in that task ever runs), and a repeating task submitted to a scheduled executor
 * that throws stops repeating forever without any log. Catching at these entry points bounds the
 * failure to the step that failed.
 * <p>
 * Catch at these edges rather than inside every method; deep catches hide bugs and let code keep
 * running on half-broken state. Errors (OOM and friends) are deliberately not caught.
 */
@Slf4j
public class Safely {
    /**
     * Runs the runnable now, logging instead of throwing if it fails.
     *
     * @param what a short description of the step, used in the log message
     */
    public static void run(String what, Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            log.error("error while {}, continuing", what, e);
        }
    }

    /**
     * Wraps the runnable so that when it is invoked by runelite or an executor, a failure is
     * logged instead of aborting a client thread task or silently cancelling a scheduled task.
     *
     * @param what a short description of the step, used in the log message
     * @return a runnable that never throws
     */
    public static Runnable safely(String what, Runnable runnable) {
        return () -> run(what, runnable);
    }
}
