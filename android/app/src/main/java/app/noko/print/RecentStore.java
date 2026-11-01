package app.noko.print;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Remembers the last few files that were opened. */
public final class RecentStore {
    public static final class Item {
        public final String uri;
        public final String name;
        public final String kind;
        public final long time;

        Item(String uri, String name, String kind, long time) {
            this.uri = uri;
            this.name = name;
            this.kind = kind;
            this.time = time;
        }
    }

    private static final int MAX = 12;
    private final SharedPreferences prefs;

    public RecentStore(Context ctx) {
        prefs = ctx.getSharedPreferences("noko_recent", Context.MODE_PRIVATE);
    }

    public List<Item> list() {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString("items", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Item(o.getString("uri"), o.getString("name"), o.getString("kind"), o.getLong("time")));
            }
        } catch (JSONException ignored) {
            // start fresh
        }
        return out;
    }

    public void add(String uri, String name, String kind) {
        List<Item> items = list();
        List<Item> next = new ArrayList<>();
        next.add(new Item(uri, name, kind, System.currentTimeMillis()));
        for (Item it : items) {
            if (!it.uri.equals(uri) && next.size() < MAX) next.add(it);
        }
        save(next);
    }

    public void remove(String uri) {
        List<Item> next = new ArrayList<>();
        for (Item it : list()) {
            if (!it.uri.equals(uri)) next.add(it);
        }
        save(next);
    }

    private void save(List<Item> items) {
        JSONArray arr = new JSONArray();
        try {
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("uri", it.uri);
                o.put("name", it.name);
                o.put("kind", it.kind);
                o.put("time", it.time);
                arr.put(o);
            }
        } catch (JSONException ignored) {
            return;
        }
        prefs.edit().putString("items", arr.toString()).apply();
    }
}
