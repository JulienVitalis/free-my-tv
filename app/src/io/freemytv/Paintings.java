package io.freemytv;

import android.content.Context;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Famous paintings for the screensaver (Home Settings > Screensaver: Paintings): the best-known landscape-format
 * paintings on Wikidata (by how many Wikipedias have an article on them), whose painter died over 70 years ago, so
 * they are free to show. The list is fetched once a week; the images come from Wikimedia Commons at 1920 px wide, a
 * few new ones each time, and the newest KEEP stay on disk, so the set changes over the weeks. Each one shows with its
 * title, painter and year. Without Wikidata, a short list of classics.
 */
final class Paintings {
    private Paintings() {}

    private static final int KEEP = 40, NEW_EACH_TIME = 8, MIN_WIDTH = 1280;
    private static final long LIST_EVERY = 7 * 24 * 3600_000L, CHECK_EVERY = 6 * 3600_000L;
    private static final String SPARQL = "https://query.wikidata.org/sparql";
    private static final String AGENT = "FreeMyTV/1.2 (https://github.com/JulienVitalis/free-my-tv)";
    // Paintings (Q3305213) with an image, wider than tall by a quarter or more, known in 25+ Wikipedias, whose
    // painter died before 1956 (in the public domain); the labels in French, else English.
    private static final String QUERY = "SELECT ?item ?itemLabel ?creatorLabel ?image ?year ?links WHERE {\n"
            + " ?item wdt:P31 wd:Q3305213; wdt:P18 ?image; wdt:P170 ?creator; wdt:P2048 ?h; wdt:P2049 ?w;"
            + " wikibase:sitelinks ?links.\n"
            + " FILTER(?links >= 25 && ?w > ?h * 1.25)\n"
            + " ?creator wdt:P570 ?death. FILTER(YEAR(?death) < 1956)\n"
            + " OPTIONAL { ?item wdt:P571 ?date. BIND(YEAR(?date) AS ?year) }\n"
            + " SERVICE wikibase:label { bd:serviceParam wikibase:language \"fr,en\". }\n"
            + "} ORDER BY DESC(?links) LIMIT 400";
    /** Without Wikidata: Commons files of a few classics (title, painter, year, file). */
    private static final String[][] CLASSICS = {
        {"Le Radeau de la Méduse", "Théodore Géricault", "1819", "JEAN LOUIS THÉODORE GÉRICAULT - La Balsa de la Medusa (Museo del Louvre, 1818-19).jpg"},
        {"La Liberté guidant le peuple", "Eugène Delacroix", "1830", "Eugène Delacroix - La liberté guidant le peuple.jpg"},
        {"La Nuit étoilée", "Vincent van Gogh", "1889", "Van Gogh - Starry Night - Google Art Project.jpg"},
        {"Chasseurs dans la neige", "Pieter Bruegel l'Ancien", "1565", "Pieter Bruegel the Elder - Hunters in the Snow (Winter) - Google Art Project.jpg"},
        {"La Naissance de Vénus", "Sandro Botticelli", "1485", "Sandro Botticelli - La nascita di Venere - Google Art Project - edited.jpg"},
        {"Le Téméraire", "William Turner", "1839", "The Fighting Temeraire, JMW Turner, National Gallery.jpg"},
        {"Le Jardin des délices", "Jérôme Bosch", "1500", "Hieronymus Bosch - The Garden of Earthly Delights - Google Art Project.jpg"},
        {"Un dimanche après-midi à l'Île de la Grande Jatte", "Georges Seurat", "1886", "A Sunday on La Grande Jatte, Georges Seurat, 1884.jpg"},
        {"Rue de Paris, temps de pluie", "Gustave Caillebotte", "1877", "Gustave Caillebotte - Paris Street; Rainy Day - Google Art Project.jpg"},
        {"La Mer de glace", "Caspar David Friedrich", "1824", "Caspar David Friedrich - Das Eismeer - Google Art Project.jpg"},
        {"Le Déjeuner sur l'herbe", "Édouard Manet", "1863", "Edouard Manet - Luncheon on the Grass - Google Art Project.jpg"},
        {"Les Glaneuses", "Jean-François Millet", "1857", "Jean-François Millet - Gleaners - Google Art Project 2.jpg"},
    };

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static long checkedAt;
    private static boolean fetching;
    private static Map<String, JSONObject> known;   // list.json by id, read once per process

    private static File dir(Context c) {
        return new File(c.getFilesDir(), "paintings");
    }

    /** The images on disk (named by Wikidata id, Q12418.jpg). */
    private static List<File> files(Context c) {
        File[] all = dir(c).listFiles((d, name) -> name.endsWith(".jpg"));
        return all == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(all));
    }

    static int count(Context c) {
        return files(c).size();
    }

    /** For the screensaver: the paintings on disk, in an order of the day, with their caption. */
    static List<Recs.Pick> picks(Context c) {
        refreshIfStale(c);
        Map<String, JSONObject> list = list(c);
        List<File> files = files(c);
        Collections.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        Collections.shuffle(files, new Random(System.currentTimeMillis() / 86_400_000L));
        List<Recs.Pick> out = new ArrayList<>();
        for (File f : files) {
            String id = f.getName().replace(".jpg", "");
            JSONObject o = list.get(id);
            if (o == null) continue;
            Recs.Pick p = new Recs.Pick();
            p.kind = "art";
            p.id = id.hashCode();
            p.photo = f;
            p.art = true;
            p.title = o.optString("title");
            String year = o.optString("year");
            p.meta = o.optString("artist") + (year.isEmpty() ? "" : " · " + year);
            out.add(p);
        }
        return out;
    }

    private static synchronized Map<String, JSONObject> list(Context c) {
        if (known != null) return known;
        Map<String, JSONObject> map = new HashMap<>();
        try (InputStream in = new FileInputStream(new File(dir(c), "list.json"))) {
            JSONArray a = new JSONArray(read(in));
            for (int i = 0; i < a.length(); i++) map.put(a.getJSONObject(i).getString("id"), a.getJSONObject(i));
        } catch (Exception ignored) {
        }
        return known = map;
    }

    /** In the background, at most every few hours: the list when it's a week old, then a few new images. */
    static void refreshIfStale(Context c) {
        Context app = c.getApplicationContext();
        synchronized (Paintings.class) {
            long now = System.currentTimeMillis();
            if (fetching || (now - checkedAt < CHECK_EVERY && now >= checkedAt)) return;
            fetching = true;
            checkedAt = now;
        }
        worker.execute(() -> {
            try {
                File dir = dir(app);
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no folder");
                File listFile = new File(dir, "list.json");
                if (!listFile.exists() || System.currentTimeMillis() - listFile.lastModified() > LIST_EVERY) {
                    JSONArray a;
                    try {
                        a = fetchList();
                        AppLog.w("paintings: " + a.length() + " from Wikidata");
                    } catch (Exception e) {
                        AppLog.w("paintings: Wikidata: " + e.getMessage());
                        a = listFile.exists() ? null : classics();
                    }
                    if (a != null && a.length() > 0) {
                        try (OutputStream out = new FileOutputStream(listFile)) {
                            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
                        }
                        synchronized (Paintings.class) { known = null; }
                    }
                }
                download(app);
            } catch (Exception e) {
                AppLog.w("paintings: " + e.getMessage());
            } finally {
                synchronized (Paintings.class) { fetching = false; }
            }
        });
    }

    private static JSONArray fetchList() throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(SPARQL).openConnection();
        h.setConnectTimeout(10_000);
        h.setReadTimeout(65_000);
        h.setDoOutput(true);
        h.setRequestMethod("POST");
        h.setRequestProperty("User-Agent", AGENT);
        h.setRequestProperty("Accept", "application/sparql-results+json");
        h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try {
            try (OutputStream out = h.getOutputStream()) {
                out.write(("query=" + URLEncoder.encode(QUERY, "UTF-8")).getBytes(StandardCharsets.UTF_8));
            }
            if (h.getResponseCode() != 200) throw new IOException("HTTP " + h.getResponseCode());
            JSONArray rows = new JSONObject(read(h.getInputStream())).getJSONObject("results").getJSONArray("bindings");
            JSONArray out = new JSONArray();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.getJSONObject(i);
                String item = value(r, "item"), id = item.substring(item.lastIndexOf('/') + 1);
                String title = value(r, "itemLabel"), image = value(r, "image");
                // No label in French or English: Wikidata gives the id back.
                if (!seen.add(id) || title.isEmpty() || title.equals(id) || !image.contains("Special:FilePath/")) continue;
                out.put(new JSONObject().put("id", id).put("title", title).put("artist", value(r, "creatorLabel"))
                        .put("year", value(r, "year")).put("file", image.substring(image.indexOf("Special:FilePath/") + 17)));
            }
            return out;
        } finally {
            h.disconnect();
        }
    }

    private static String value(JSONObject row, String name) {
        JSONObject v = row.optJSONObject(name);
        return v == null ? "" : v.optString("value");
    }

    private static JSONArray classics() throws Exception {
        JSONArray a = new JSONArray();
        for (int i = 0; i < CLASSICS.length; i++) {
            String[] p = CLASSICS[i];
            a.put(new JSONObject().put("id", "C" + i).put("title", p[0]).put("artist", p[1]).put("year", p[2])
                    .put("file", URLEncoder.encode(p[3], "UTF-8").replace("+", "%20")));
        }
        return a;
    }

    /** A few images not on disk yet, picked at random from the list; then the oldest go beyond KEEP. */
    private static void download(Context c) throws IOException {
        Map<String, JSONObject> list = list(c);
        Set<String> have = new HashSet<>();
        for (File f : files(c)) have.add(f.getName().replace(".jpg", ""));
        List<JSONObject> todo = new ArrayList<>();
        for (JSONObject o : list.values()) if (!have.contains(o.optString("id"))) todo.add(o);
        Collections.shuffle(todo);
        int added = 0, tried = 0;
        for (JSONObject o : todo) {
            if (added >= NEW_EACH_TIME || tried >= NEW_EACH_TIME * 2) break;
            tried++;
            File file = new File(dir(c), o.optString("id") + ".jpg"), part = new File(file.getPath() + ".part");
            try {
                byte[] bytes = get("https://commons.wikimedia.org/wiki/Special:FilePath/" + o.optString("file") + "?width=1920");
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                if (bounds.outWidth < MIN_WIDTH || bounds.outWidth < bounds.outHeight) continue;   // too small, or not landscape
                try (OutputStream out = new FileOutputStream(part)) {
                    out.write(bytes);
                }
                if (part.renameTo(file)) added++;
            } catch (IOException e) {
                AppLog.w("paintings: " + o.optString("title") + ": " + e.getMessage());
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
        }
        List<File> all = files(c);
        Collections.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = KEEP; i < all.size(); i++) {
            //noinspection ResultOfMethodCallIgnored
            all.get(i).delete();
        }
        if (added > 0) AppLog.w("paintings: " + added + " new, " + Math.min(all.size(), KEEP) + " on the TV");
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10_000);
        h.setReadTimeout(30_000);
        h.setRequestProperty("User-Agent", AGENT);
        try {
            if (h.getResponseCode() != 200) throw new IOException("HTTP " + h.getResponseCode());
            try (InputStream in = h.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > 20 << 20) throw new IOException("too large");
                }
                return out.toByteArray();
            }
        } finally {
            h.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString("UTF-8");
    }
}
