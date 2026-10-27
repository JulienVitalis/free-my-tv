package io.freemytv;

import android.content.Context;
import android.media.tv.TvContract;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * What you were watching, from Android's "Play Next" row: the streaming apps (Netflix, Prime Video...) put the title,
 * the episode and an address that resumes it there. Another app may only read its own entries, so this asks through
 * the TV's own ADB (as the shell); on a TV that refuses, it finds nothing and the home screen goes by the last pick
 * opened from it (Recs).
 */
final class WatchNext {
    private WatchNext() {}

    static final class Entry {
        String title, pkg, intent = "";
        int type = -1, season, episode;
        long at;
    }

    private static final String[] COLUMNS = {
        TvContract.WatchNextPrograms.COLUMN_TITLE,
        TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
        TvContract.WatchNextPrograms.COLUMN_TYPE,
        TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
        TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
        TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
        TvContract.WatchNextPrograms.COLUMN_INTENT_URI,   // last: it may hold ", " of its own
    };

    /** The entries of the apps in `pkgs`, newest first; empty if the TV won't tell. */
    static List<Entry> read(Context c, java.util.Set<String> pkgs) throws IOException {
        String out;
        try (Adb adb = Adb.connect(c, 3_000, null)) {
            out = adb.shell("content query --uri " + TvContract.WatchNextPrograms.CONTENT_URI
                    + " --projection " + String.join(":", COLUMNS));
        }
        List<Entry> list = new ArrayList<>();
        for (String line : out.split("\n")) {
            Entry e = parse(line.trim());
            if (e != null && pkgs.contains(e.pkg) && !e.title.isEmpty()) list.add(e);
        }
        list.sort((a, b) -> Long.compare(b.at, a.at));
        if (list.isEmpty()) AppLog.w("play next: " + (out.startsWith("Row:") || out.contains("No result") ? "nothing" : "not readable"));
        return list;
    }

    /** "Row: 0 title=Carrie, package_name=com.netflix.ninja, type=1, ..." (the columns in the order asked). */
    private static Entry parse(String line) {
        if (!line.startsWith("Row:")) return null;
        String[] values = new String[COLUMNS.length];
        int from = line.indexOf(" " + COLUMNS[0] + "=");
        if (from < 0) return null;
        from += COLUMNS[0].length() + 2;
        for (int i = 0; i < COLUMNS.length; i++) {
            int end = i + 1 < COLUMNS.length ? line.indexOf(", " + COLUMNS[i + 1] + "=", from) : line.length();
            if (end < 0) return null;
            values[i] = line.substring(from, end);
            if (i + 1 < COLUMNS.length) from = end + COLUMNS[i + 1].length() + 3;
        }
        Entry e = new Entry();
        e.title = text(values[0]);
        e.pkg = text(values[1]);
        e.type = number(values[2]);
        e.season = number(values[3]);
        e.episode = number(values[4]);
        e.at = numberLong(values[5]);
        e.intent = text(values[6]);
        return e;
    }

    private static String text(String v) {
        return v == null || v.equals("NULL") ? "" : v.trim();
    }

    private static int number(String v) {
        return (int) numberLong(v);
    }

    private static long numberLong(String v) {
        try {
            return Long.parseLong(text(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
