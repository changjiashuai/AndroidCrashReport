package io.github.changjiashuai.crashreport;

import android.content.Context;

/**
 * Email: changjiashuai@gmail.com
 *
 * Created by CJS on 16/1/25 16:53.
 */
public final class CrashReport {

    public static void init(Thread.UncaughtExceptionHandler crashHandler) {
        Thread.setDefaultUncaughtExceptionHandler(crashHandler);
    }

    public static void defaultCrashHandler(Context context, boolean saveCrashToFile, boolean startErrorActivity) {
        CrashHandler crashHandler = new CrashHandler(context);
        crashHandler.setSaveCrashToFile(saveCrashToFile);
        crashHandler.setStartErrorActivity(startErrorActivity);
        init(crashHandler);
    }
}
