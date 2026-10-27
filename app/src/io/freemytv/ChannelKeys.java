package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The remote's number keys on Philips TVs: from inside any app, typing a channel number (5, 12...) opens live TV and
 * tunes that channel. The digits wait a moment for the next one, then live TV opens and gets them through the TV's own
 * ADB, as if they had been typed there. In live TV itself, in the apps below and while a keyboard is up, the keys go
 * on as usual (a PIN, a search).
 */
final class ChannelKeys {
    private ChannelKeys() {}

    /**
     * Philips TVs are made by TP Vision, and the build names vary (Philips, TPV, TP Vision, TPVision...), so the TV's own
     * Philips pieces (Ambilight, Watch TV) decide too. Looked up once.
     */
    private static Boolean philips;

    static boolean philips(Context c) {
        if (philips == null) {
            String names = (Build.BRAND + " " + Build.MANUFACTURER + " " + Build.FINGERPRINT).toLowerCase(java.util.Locale.ROOT);
            philips = names.contains("philips") || names.contains("tpv") || names.contains("tp vision")
                    || installed(c, "org.droidtv.ambisvc") || installed(c, "org.droidtv.playtv");
        }
        return philips;
    }

    private static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Philips' live TV apps (Watch TV, MediaTek TV Center): the digits are theirs. */
    private static final Set<String> LIVE_TV = new HashSet<>(Arrays.asList(
            "org.droidtv.playtv", "org.droidtv.tvcenter", "com.mediatek.wwtv.tvcenter", "com.google.android.tv"));

    /** Apps where the digits mean something else (Canal+'s PIN), and the on-screen keyboards. */
    private static final Set<String> LEFT_ALONE = new HashSet<>(Arrays.asList(
            "com.canal.android.canal", "com.canalplus.canalplus", "com.google.android.leanback.ime",
            "com.google.android.inputmethod.latin", "com.android.tv.settings", "org.droidtv.settings"));

    /** How long the digits wait for the next one, then how long live TV gets to open before it receives them. */
    private static final long NEXT_DIGIT_MS = 1500, OPEN_MS = 2500;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final StringBuilder digits = new StringBuilder();
    private static volatile String foreground = "";
    private static String liveTvPkg;

    /** The package of the window in front, from the accessibility service's window events. */
    static void onWindow(CharSequence pkg) {
        if (pkg != null && pkg.length() > 0) foreground = pkg.toString();
    }

    /** True if the key is a channel digit we took (it then reaches no app). */
    static boolean onKey(Context c, KeyEvent e) {
        if (!philips(c)) return false;
        int digit = digit(e.getKeyCode());
        if (digit < 0) return false;
        // Not known yet (no window has changed since the service started): taken too, live TV gets the digits anyway.
        String front = foreground;
        if (LIVE_TV.contains(front) || LEFT_ALONE.contains(front) || front.equals(liveTvPkg(c))) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            synchronized (digits) {
                if (digits.length() < 4) digits.append((char) ('0' + digit));
                android.widget.Toast.makeText(c.getApplicationContext(), "\uD83D\uDCFA " + digits, android.widget.Toast.LENGTH_SHORT).show();
            }
            MAIN.removeCallbacksAndMessages(null);
            MAIN.postDelayed(() -> tune(c.getApplicationContext()), NEXT_DIGIT_MS);
        }
        return true;
    }

    /** 0 to 9 for the remote's number keys (some remotes send the keypad's), else -1. */
    private static int digit(int code) {
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) return code - KeyEvent.KEYCODE_0;
        if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9) return code - KeyEvent.KEYCODE_NUMPAD_0;
        return -1;
    }

    private static void tune(Context c) {
        String number;
        synchronized (digits) {
            number = digits.toString();
            digits.setLength(0);
        }
        if (number.isEmpty()) return;
        Intent tv = HomeApps.liveTv(c);
        AppLog.w("channel keys: " + number + " -> " + (tv == null ? "no live TV app" : tv));
        if (tv == null) return;
        c.startActivity(tv.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        StringBuilder keys = new StringBuilder("input keyevent");
        for (char d : number.toCharArray()) keys.append(" KEYCODE_").append(d);
        String command = keys.toString();
        new Thread(() -> {
            try {
                Thread.sleep(OPEN_MS);
                try (Adb adb = Adb.connect(c, 3_000, null)) {
                    adb.shell(command);
                }
            } catch (IOException | InterruptedException ex) {
                AppLog.w("channel keys: " + ex.getMessage());
            }
        }, "channel-keys").start();
    }

    /** The package live TV opens in on this TV (whatever HomeApps finds), looked up once. */
    private static String liveTvPkg(Context c) {
        if (liveTvPkg == null) {
            Intent tv = HomeApps.liveTv(c);
            ComponentName target = tv == null ? null
                    : tv.getComponent() != null ? tv.getComponent() : tv.resolveActivity(c.getPackageManager());
            liveTvPkg = target == null ? "" : target.getPackageName();
        }
        return liveTvPkg;
    }
}
