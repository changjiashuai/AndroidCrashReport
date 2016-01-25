package io.github.changjiashuai.crashreport;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Environment;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import io.github.changjiashuai.crashreport.ui.DefaultErrorActivity;

/**
 * Email: changjiashuai@gmail.com
 *
 * Created by CJS on 16/1/25 17:10.
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    //Extras passed to the error activity
    private static final String EXTRA_RESTART_ACTIVITY_CLASS = "io.github.changjiashuai.crashreport.EXTRA_RESTART_ACTIVITY_CLASS";
    private static final String EXTRA_STACK_TRACE = "io.github.changjiashuai.crashreport.EXTRA_STACK_TRACE";

    private static final String INTENT_ACTION_ERROR_ACTIVITY = "io.github.changjiashuai.crashreport.ERROR";
    private static final String INTENT_ACTION_RESTART_ACTIVITY = "io.github.changjiashuai.crashreport.RESTART";
    private static final int MAX_STACK_TRACE_SIZE = 131071; //128 KB - 1

    private static WeakReference<Activity> lastActivityCreated = new WeakReference<>(null);
    private static boolean isInBackground = false;
    private Context mContext;
    private boolean mSaveCrashToFile;//是否保存crash信息
    private String mCrashSaveTargetFolder;
    private boolean mStartErrorActivity;

    //Settable properties and their defaults
    private static boolean launchErrorActivityWhenInBackground = true;
    private static boolean enableAppRestart = true;
    private static Class<? extends Activity> errorActivityClass = null;
    private static Class<? extends Activity> restartActivityClass = null;

    public CrashHandler(Context context) {
        mContext = context;
    }

    public void setSaveCrashToFile(boolean saveCrashToFile) {
        mSaveCrashToFile = saveCrashToFile;
    }

    public void setCrashSaveTargetFolder(String crashSaveTargetFolder){
        mCrashSaveTargetFolder = crashSaveTargetFolder;
    }

    public void setStartErrorActivity(boolean startErrorActivity) {
        mStartErrorActivity = startErrorActivity;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        //"App has crashed, executing CustomActivityOnCrash's UncaughtExceptionHandler"
        if (errorActivityClass == null) {
            errorActivityClass = guessErrorActivityClass(mContext);
        }

        if (isStackTraceLikelyConflictive(throwable, errorActivityClass)) {
            throw new RuntimeException("Your application class or your error activity have crashed, the custom activity will not be launched!");
        } else {
            // get StackTrace
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            String stackTraceString = sw.toString();

            if (mSaveCrashToFile) {
                //save to local file
                saveCrashInfo2File(stackTraceString);
            }

            if (mStartErrorActivity) {
                if (launchErrorActivityWhenInBackground || !isInBackground) {
                    Intent intent = new Intent(mContext, errorActivityClass);
                    //Reduce data to 128KB so we don't get a TransactionTooLargeException when sending the intent.
                    //The limit is 1MB on Android but some devices seem to have it lower.
                    //See: http://developer.android.com/reference/android/os/TransactionTooLargeException.html
                    //And: http://stackoverflow.com/questions/11451393/what-to-do-on-transactiontoolargeexception#comment46697371_12809171
                    if (stackTraceString.length() > MAX_STACK_TRACE_SIZE) {
                        String disclaimer = " [stack trace too large]";
                        stackTraceString = stackTraceString.substring(0, MAX_STACK_TRACE_SIZE - disclaimer.length()) + disclaimer;
                    }

                    if (enableAppRestart && restartActivityClass == null) {
                        //We can set the restartActivityClass because the app will terminate right now,
                        //and when relaunched, will be null again by default.
                        restartActivityClass = guessRestartActivityClass(mContext);
                    } else if (!enableAppRestart) {
                        //In case someone sets the activity and then decides to not restart
                        restartActivityClass = null;
                    }

                    intent.putExtra(EXTRA_STACK_TRACE, stackTraceString);
                    intent.putExtra(EXTRA_RESTART_ACTIVITY_CLASS, restartActivityClass);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    mContext.startActivity(intent);
                }
            }
        }
        final Activity lastActivity = lastActivityCreated.get();
        if (lastActivity != null) {
            //We finish the activity, this solves a bug which causes infinite recursion.
            //This is unsolvable in API<14, so beware!
            //See: https://github.com/ACRA/acra/issues/42
            lastActivity.finish();
            lastActivityCreated.clear();
        }
        killCurrentProcess();
    }

    /**
     * INTERNAL method used to guess which error activity must be called when the app crashes. It
     * will first get activities from the AndroidManifest with intent filter <action
     * android:name="cat.ereza.customactivityoncrash.ERROR" />, if it cannot find them, then it will
     * use the default error activity.
     *
     * @param context A valid context. Must not be null.
     * @return The guessed error activity class, or the default error activity if not found
     */
    private Class<? extends Activity> guessErrorActivityClass(Context context) {
        Class<? extends Activity> resolvedActivityClass;

        //If action is defined, use that
        resolvedActivityClass = getErrorActivityClassWithIntentFilter(context);

        //Else, get the default launcher activity
        if (resolvedActivityClass == null) {
            resolvedActivityClass = DefaultErrorActivity.class;
        }

        return resolvedActivityClass;
    }


    /**
     * INTERNAL method used to get the first activity with an intent-filter <action
     * android:name="io.github.changjiashuai.crashreport.ERROR" />, If there is no activity with
     * that intent filter, this returns null.
     *
     * @param context A valid context. Must not be null.
     * @return A valid activity class, or null if no suitable one is found
     */
    @SuppressWarnings("unchecked")
    private static Class<? extends Activity> getErrorActivityClassWithIntentFilter(Context context) {
        List<ResolveInfo> resolveInfos = context.getPackageManager().queryIntentActivities(
                new Intent().setAction(INTENT_ACTION_ERROR_ACTIVITY),
                PackageManager.GET_RESOLVED_FILTER);

        if (resolveInfos != null && resolveInfos.size() > 0) {
            ResolveInfo resolveInfo = resolveInfos.get(0);
            try {
                return (Class<? extends Activity>) Class.forName(resolveInfo.activityInfo.name);
            } catch (ClassNotFoundException e) {
                //Should not happen, print it to the log!
                throw new RuntimeException("Failed when resolving the error activity class via intent filter, stack trace follows!", e);
            }
        }

        return null;
    }

    /**
     * INTERNAL method that checks if the stack trace that just crashed is conflictive. This is true
     * in the following scenarios: - The application has crashed while initializing
     * (handleBindApplication is in the stack) - The error activity has crashed (activityClass is in
     * the stack)
     *
     * @param throwable     The throwable from which the stack trace will be checked
     * @param activityClass The activity class to launch when the app crashes
     * @return true if this stack trace is conflictive and the activity must not be launched, false
     * otherwise
     */
    private static boolean isStackTraceLikelyConflictive(Throwable throwable, Class<? extends Activity> activityClass) {
        do {
            StackTraceElement[] stackTrace = throwable.getStackTrace();
            for (StackTraceElement element : stackTrace) {
                if ((element.getClassName().equals("android.app.ActivityThread") && element.getMethodName().equals("handleBindApplication")) || element.getClassName().equals(activityClass.getName())) {
                    return true;
                }
            }
        } while ((throwable = throwable.getCause()) != null);
        return false;
    }

    /**
     * INTERNAL method used to guess which activity must be called from the error activity to
     * restart the app. It will first get activities from the AndroidManifest with intent filter
     * <action android:name="io.github.changjiashuai.crashreport.RESTART" />, if it cannot find
     * them, then it will get the default launcher. If there is no default launcher, this returns
     * null.
     *
     * @param context A valid context. Must not be null.
     * @return The guessed restart activity class, or null if no suitable one is found
     */
    private static Class<? extends Activity> guessRestartActivityClass(Context context) {
        Class<? extends Activity> resolvedActivityClass;
        //If action is defined, use that
        resolvedActivityClass = getRestartActivityClassWithIntentFilter(context);
        //Else, get the default launcher activity
        if (resolvedActivityClass == null) {
            resolvedActivityClass = getLauncherActivity(context);
        }
        return resolvedActivityClass;
    }

    /**
     * INTERNAL method used to get the first activity with an intent-filter <action
     * android:name="io.github.changjiashuai.crashreport.RESTART" />, If there is no activity with
     * that intent filter, this returns null.
     *
     * @param context A valid context. Must not be null.
     * @return A valid activity class, or null if no suitable one is found
     */
    @SuppressWarnings("unchecked")
    private static Class<? extends Activity> getRestartActivityClassWithIntentFilter(Context context) {
        List<ResolveInfo> resolveInfos = context.getPackageManager().queryIntentActivities(
                new Intent().setAction(INTENT_ACTION_RESTART_ACTIVITY),
                PackageManager.GET_RESOLVED_FILTER);

        if (resolveInfos != null && resolveInfos.size() > 0) {
            ResolveInfo resolveInfo = resolveInfos.get(0);
            try {
                return (Class<? extends Activity>) Class.forName(resolveInfo.activityInfo.name);
            } catch (ClassNotFoundException e) {
                //Should not happen, print it to the log!
                throw new RuntimeException("Failed when resolving the restart activity class via intent filter, stack trace follows!", e);
            }
        }

        return null;
    }

    /**
     * INTERNAL method used to get the default launcher activity for the app. If there is no
     * launchable activity, this returns null.
     *
     * @param context A valid context. Must not be null.
     * @return A valid activity class, or null if no suitable one is found
     */
    @SuppressWarnings("unchecked")
    private static Class<? extends Activity> getLauncherActivity(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (intent != null) {
            try {
                return (Class<? extends Activity>) Class.forName(intent.getComponent().getClassName());
            } catch (ClassNotFoundException e) {
                //Should not happen, print it to the log!
                throw new RuntimeException("Failed when resolving the restart activity class via getLaunchIntentForPackage, stack trace follows!", e);
            }
        }

        return null;
    }

    /**
     * 保存crash信息
     */
    private String saveCrashInfo2File(String crashMsg) {
        StringBuffer sb = new StringBuffer();
        sb.append(crashMsg);
        // 保存文件
        long timetamp = System.currentTimeMillis();
        @SuppressLint("SimpleDateFormat")
        SimpleDateFormat dateFomat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");// 用于格式化日期,作为日志文件名的一部分
        String time = dateFomat.format(new Date());
        String fileName = "crash-" + time + "-" + timetamp + ".log";
        if (Environment.getExternalStorageState().equals(
                Environment.MEDIA_MOUNTED)) {
            try {
                File dir;
                if (TextUtils.isEmpty(mCrashSaveTargetFolder)) {
                    dir = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + "crash");
                } else {
                    dir = new File(mCrashSaveTargetFolder);
                }
                if (!dir.exists()) {
                    dir.mkdir();
                }
                FileOutputStream fos = new FileOutputStream(new File(dir, fileName));
                fos.write(sb.toString().getBytes());
                fos.close();
                return fileName;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    /**
     * INTERNAL method that kills the current process. It is used after restarting or killing the
     * app.
     */
    private static void killCurrentProcess() {
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(10);
    }
}
