package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * TMDB metadata for the mobile detail page. Presentation only: it never
 * takes part in resolving playback urls or switching flags.
 */
public class Meta {

    @SerializedName("id")
    private Integer id;
    @SerializedName("title")
    private String title;
    @SerializedName("name")
    private String name;
    @SerializedName("overview")
    private String overview;
    @SerializedName("backdrop_path")
    private String backdrop;
    @SerializedName("poster_path")
    private String poster;
    @SerializedName("release_date")
    private String releaseDate;
    @SerializedName("first_air_date")
    private String firstAirDate;
    @SerializedName("vote_average")
    private Double vote;
    @SerializedName("media_type")
    private String mediaType;
    @SerializedName("genres")
    private List<Genre> genres;
    @SerializedName("seasons")
    private List<Season> seasons;
    @SerializedName("episodes")
    private List<Chapter> episodes;
    @SerializedName("results")
    private List<Meta> results;

    public int getId() {
        return id == null ? 0 : id;
    }

    public String getTitle() {
        if (!TextUtils.isEmpty(title)) return title.trim();
        return TextUtils.isEmpty(name) ? "" : name.trim();
    }

    public String getOverview() {
        return TextUtils.isEmpty(overview) ? "" : overview.trim();
    }

    public String getBackdrop() {
        return TextUtils.isEmpty(backdrop) ? "" : backdrop.trim();
    }

    public String getPoster() {
        return TextUtils.isEmpty(poster) ? "" : poster.trim();
    }

    public String getDate() {
        if (!TextUtils.isEmpty(releaseDate)) return releaseDate.trim();
        return TextUtils.isEmpty(firstAirDate) ? "" : firstAirDate.trim();
    }

    public String getYear() {
        String date = getDate();
        return date.length() < 4 ? "" : date.substring(0, 4);
    }

    public double getVote() {
        return vote == null ? 0 : vote;
    }

    public String getMediaType() {
        return TextUtils.isEmpty(mediaType) ? "" : mediaType.trim();
    }

    public List<Genre> getGenres() {
        return genres == null ? new ArrayList<>() : genres;
    }

    public List<Season> getSeasons() {
        return seasons == null ? new ArrayList<>() : seasons;
    }

    public List<Chapter> getEpisodes() {
        return episodes == null ? new ArrayList<>() : episodes;
    }

    public List<Meta> getResults() {
        return results == null ? new ArrayList<>() : results;
    }

    public String getGenreText() {
        StringBuilder sb = new StringBuilder();
        for (Genre genre : getGenres()) {
            if (TextUtils.isEmpty(genre.getName())) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(genre.getName());
        }
        return sb.toString();
    }

    public static class Genre {

        @SerializedName("name")
        private String name;

        public String getName() {
            return TextUtils.isEmpty(name) ? "" : name.trim();
        }
    }

    public static class Season {

        @SerializedName("season_number")
        private Integer number;
        @SerializedName("name")
        private String name;
        @SerializedName("episode_count")
        private Integer count;

        public int getNumber() {
            return number == null ? 0 : number;
        }

        public String getName() {
            return TextUtils.isEmpty(name) ? "" : name.trim();
        }

        public int getCount() {
            return count == null ? 0 : count;
        }
    }

    public static class Chapter {

        @SerializedName("episode_number")
        private Integer number;
        @SerializedName("name")
        private String name;
        @SerializedName("overview")
        private String overview;
        @SerializedName("still_path")
        private String still;
        @SerializedName("air_date")
        private String airDate;
        @SerializedName("runtime")
        private Integer runtime;

        public int getNumber() {
            return number == null ? 0 : number;
        }

        public String getName() {
            return TextUtils.isEmpty(name) ? "" : name.trim();
        }

        public String getOverview() {
            return TextUtils.isEmpty(overview) ? "" : overview.trim();
        }

        public String getStill() {
            return TextUtils.isEmpty(still) ? "" : still.trim();
        }

        public String getAirDate() {
            return TextUtils.isEmpty(airDate) ? "" : airDate.trim();
        }

        public int getRuntime() {
            return runtime == null ? 0 : runtime;
        }
    }
}
