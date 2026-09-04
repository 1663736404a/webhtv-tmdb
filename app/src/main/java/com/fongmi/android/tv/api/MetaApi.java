package com.fongmi.android.tv.api;

import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Meta;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import java.util.List;
import java.util.function.Consumer;

/**
 * Read-only TMDB metadata used to decorate the mobile detail page. Nothing here
 * feeds the playback pipeline: failures degrade to the site supplied detail.
 */
public class MetaApi {

    private static final String TAG = MetaApi.class.getSimpleName();
    private static final String KEY = "d913a144d0ba98fdca978f53a1ce27a5";
    private static final String BASE = "https://vce.vce810.ip-ddns.com/api/tmdb.php";
    private static final String IMAGE = "https://vce.vce810.ip-ddns.com/api/tmdb-image.php";
    private static final String LANG = "zh-CN";
    private static final long TIMEOUT = 10 * 1000;

    public static String image(String path, boolean original) {
        if (TextUtils.isEmpty(path)) return "";
        return IMAGE + (original ? "/original" : "/w500") + path;
    }

    public static void search(String name, String year, Consumer<Meta> consumer) {
        if (TextUtils.isEmpty(name)) return;
        Task.execute(() -> {
            Meta meta = findBest(name, year);
            if (meta != null) App.post(() -> consumer.accept(meta));
        });
    }

    public static void detail(String type, int id, Consumer<Meta> consumer) {
        if (id <= 0 || TextUtils.isEmpty(type)) return;
        Task.execute(() -> {
            Meta meta = parse(get(String.format("/%s/%d?api_key=%s&language=%s", type, id, KEY, LANG)));
            if (meta != null) App.post(() -> consumer.accept(meta));
        });
    }

    public static void season(int id, int season, Consumer<List<Meta.Chapter>> consumer) {
        if (id <= 0) return;
        Task.execute(() -> {
            Meta meta = parse(get(String.format("/tv/%d/season/%d?api_key=%s&language=%s", id, season, KEY, LANG)));
            List<Meta.Chapter> items = meta == null ? null : meta.getEpisodes();
            if (items != null && !items.isEmpty()) App.post(() -> consumer.accept(items));
        });
    }

    private static Meta findBest(String name, String year) {
        Meta result = parse(get("/search/multi?api_key=" + KEY + "&language=" + LANG + "&query=" + Uri.encode(name)));
        if (result == null) return null;
        Meta fallback = null;
        for (Meta item : result.getResults()) {
            String type = item.getMediaType();
            if (!"tv".equals(type) && !"movie".equals(type)) continue;
            if (fallback == null) fallback = item;
            boolean sameName = item.getTitle().equalsIgnoreCase(name);
            boolean sameYear = TextUtils.isEmpty(year) || year.equals(item.getYear());
            if (sameName && sameYear) return item;
        }
        return fallback;
    }

    private static Meta parse(String body) {
        if (TextUtils.isEmpty(body)) return null;
        try {
            return App.gson().fromJson(body, Meta.class);
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "parse failed %s", e.getMessage());
            return null;
        }
    }

    private static String get(String path) {
        try {
            return OkHttp.string(BASE + path, TIMEOUT);
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "request failed %s", e.getMessage());
            return "";
        }
    }
}
