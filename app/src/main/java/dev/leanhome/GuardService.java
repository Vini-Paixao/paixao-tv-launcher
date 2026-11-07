package dev.leanhome;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Fire OS will not give Home to another app, so when Amazon's home comes up this brings our task back
 * under a black cover. Same approach as hrudek/cleanyourtv FireHomeRedirect and humblemuzzu/dumbtv (MIT).
 * The cover fails open after four seconds, so a failure here shows Amazon's home, never a black screen.
 * ponytail: the first launch after boot needs `alarm_manager_constants min_futurity=0`; without it the
 * alarm path takes about five seconds.
 */
public final class GuardService extends AccessibilityService {
    private static final String AMAZON = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    private static final long BOOT_WINDOW_MS = 180_000; // Amazon's home is up before this service binds.
    private static final long FAIL_OPEN_MS = 4_000;

    private static GuardService active;
    private static long lastHomeShown;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hide = this::hideCover;
    private View cover;
    private PendingIntent target;
    private long lastRequest;

    @Override
    protected void onServiceConnected() {
        Intent home = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        target = PendingIntent.getActivity(this, 1, home,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle());

        cover = new View(this);
        cover.setBackgroundColor(Color.BLACK);
        cover.setVisibility(View.GONE);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        params.windowAnimations = 0;
        getSystemService(WindowManager.class).addView(cover, params);
        active = this;
        // Also covers a rebind after our process died: Amazon's home may already be up, with no event to come.
        handler.postDelayed(this::returnIfAmazonInFront, 1_000);
        handler.postDelayed(this::returnIfAmazonInFront, 4_000); // The active window can still be unknown at 1 s.
    }

    /** Reads only the package name of the active window, never its content. */
    private void returnIfAmazonInFront() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        CharSequence pkg = root == null ? null : root.getPackageName();
        if (root != null) root.recycle();
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS || (pkg != null && AMAZON.contentEquals(pkg))) {
            bringHomeBack();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (pkg == null) return;
        if (AMAZON.contentEquals(pkg) && cls != null && AMAZON_HOME.contentEquals(cls)) {
            if (event.getEventTime() > lastHomeShown) bringHomeBack();
        } else if (!getPackageName().contentEquals(pkg) && !"android".contentEquals(pkg)
                && !"com.android.systemui".contentEquals(pkg)) {
            hideCover(); // Another app opened meanwhile: do not pull the user out of it.
        }
    }

    private void bringHomeBack() {
        long now = SystemClock.uptimeMillis();
        if (now - lastRequest < 600) return;
        lastRequest = now;
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, FAIL_OPEN_MS);
        cover.setVisibility(View.VISIBLE);
        try {
            for (ActivityManager.AppTask task : getSystemService(ActivityManager.class).getAppTasks()) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info.baseActivity != null
                        && MainActivity.class.getName().equals(info.baseActivity.getClassName())) {
                    task.moveToFront();
                    return;
                }
            }
        } catch (SecurityException | IllegalArgumentException stale) {
            // Fall through to the alarm.
        }
        getSystemService(AlarmManager.class).setExact(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime(), target);
    }

    private void hideCover() {
        handler.removeCallbacks(hide);
        if (cover != null) cover.setVisibility(View.GONE);
    }

    static void homeShown() {
        lastHomeShown = SystemClock.uptimeMillis();
        if (active != null) active.hideCover();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        hideCover();
        if (cover != null) getSystemService(WindowManager.class).removeView(cover);
        cover = null;
        if (active == this) active = null;
        super.onDestroy();
    }
}
