package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Landscapes for the screensaver, unless your own photos are on: Bing's photos of the day (mostly landscapes, no key,
 * no account). The newest KEEP are kept in the app's own folder, a new one comes each day, and the
 * screensaver shows them in an order shuffled again every time it starts.
 */
final class Landscapes {
    private Landscapes() {}

    /** How many are kept (Bing's archive gives the last 15 days). */
    private static final int KEEP = 16;
    private static final long REFRESH_MS = 6 * 3600_000L;
    private static final String ARCHIVE = "https://www.bing.com/HPImageArchive.aspx?format=js&mkt=fr-FR&n=8&idx=";

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static long checkedAt;
    private static boolean fetching;
    private static long seed = new Random().nextLong();

    private static File dir(Context c) {
        return new File(c.getFilesDir(), "landscapes");
    }

    /** The ones on disk, newest first (named by Bing's date, yyyymmdd.jpg). */
    private static List<File> files(Context c) {
        File[] all = dir(c).listFiles((d, name) -> name.matches("\\d{8}\\.jpg"));
        List<File> list = all == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(all));
        Collections.sort(list, (a, b) -> b.getName().compareTo(a.getName()));
        return list;
    }

    /** A new order for the next screensaver. */
    static void shuffle() {
        seed = new Random().nextLong();
    }

    /** For the screensaver: the landscapes as picks (no title, no caption), in this session's order. */
    static List<Recs.Pick> picks(Context c) {
        List<Recs.Pick> out = new ArrayList<>();
        for (File f : files(c)) {
            Recs.Pick p = new Recs.Pick();
            p.kind = "landscape";
            p.id = Integer.parseInt(f.getName().substring(0, 8));
            p.photo = f;
            out.add(p);
        }
        Collections.shuffle(out, new Random(seed));
        return out;
    }

    /** Fetches the new ones in the background, at most every few hours; `done` runs on that thread if any came. */
    static void refreshIfStale(Context c, Runnable done) {
        Context app = c.getApplicationContext();
        synchronized (Landscapes.class) {
            long now = System.currentTimeMillis();
            if (fetching || (now - checkedAt < REFRESH_MS && !files(app).isEmpty())) return;
            fetching = true;
            checkedAt = now;
        }
        worker.execute(() -> {
            int added = 0;
            try {
                added = fetch(app);
            } catch (IOException | RuntimeException e) {
                AppLog.w("landscapes: " + e.getMessage());
            } finally {
                synchronized (Landscapes.class) { fetching = false; }
            }
            if (added > 0 && done != null) done.run();
        });
    }

    private static int fetch(Context c) throws IOException {
        File dir = dir(c);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no folder");
        int added = 0;
        for (int idx : new int[] {0, 7}) {   // Bing goes back 7 days, 8 at a time
            JSONArray images;
            try {
                images = new JSONObject(new String(get(ARCHIVE + idx), StandardCharsets.UTF_8)).getJSONArray("images");
            } catch (org.json.JSONException e) {
                throw new IOException("unexpected answer from Bing");
            }
            for (int i = 0; i < images.length(); i++) {
                JSONObject image = images.optJSONObject(i);
                if (image == null) continue;
                String date = image.optString("startdate"), base = image.optString("urlbase");
                if (!date.matches("\\d{8}") || !base.startsWith("/")) continue;
                File file = new File(dir, date + ".jpg");
                if (file.exists()) continue;
                File part = new File(dir, date + ".part");
                try (FileOutputStream out = new FileOutputStream(part)) {
                    out.write(get("https://www.bing.com" + base + "_1920x1080.jpg"));
                }
                if (part.renameTo(file)) added++;
            }
        }
        List<File> all = files(c);
        for (int i = KEEP; i < all.size(); i++) all.get(i).delete();   // the oldest go
        return added;
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
