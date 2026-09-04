package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Meta;
import com.fongmi.android.tv.databinding.AdapterEpisodeCardBinding;
import com.fongmi.android.tv.databinding.AdapterEpisodeGridBinding;
import com.fongmi.android.tv.databinding.AdapterEpisodeHoriBinding;
import com.fongmi.android.tv.ui.base.BaseEpisodeHolder;
import com.fongmi.android.tv.ui.base.ViewType;
import com.fongmi.android.tv.ui.holder.EpisodeCardHolder;
import com.fongmi.android.tv.ui.holder.EpisodeGridHolder;
import com.fongmi.android.tv.ui.holder.EpisodeHoriHolder;
import com.fongmi.android.tv.utils.EpisodeTitleCompact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EpisodeAdapter extends RecyclerView.Adapter<BaseEpisodeHolder> {

    private final OnClickListener listener;
    private final List<Episode> mItems;
    private final Map<Integer, Meta.Chapter> mChapters = new HashMap<>();
    private String fallbackImage = "";
    private int viewType;

    public EpisodeAdapter(OnClickListener listener, int viewType) {
        this(listener, viewType, new ArrayList<>());
    }

    public EpisodeAdapter(OnClickListener listener, int viewType, ArrayList<Episode> items) {
        this.listener = listener;
        this.viewType = viewType;
        this.mItems = items;
    }

    public interface OnClickListener {

        void onItemClick(Episode item);
    }

    public void addAll(List<Episode> items) {
        EpisodeTitleCompact.apply(items);
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public int getPosition() {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).isSelected()) return i;
        return 0;
    }

    public int getPosition(Episode item) {
        return mItems.indexOf(item);
    }

    public Episode getActivated() {
        return mItems.get(getPosition());
    }

    public Episode getNext() {
        int current = getPosition();
        int max = getItemCount() - 1;
        current = ++current > max ? max : current;
        return mItems.get(current);
    }

    public Episode getPrev() {
        int current = getPosition();
        current = --current < 0 ? 0 : current;
        return mItems.get(current);
    }

    public List<Episode> getItems() {
        return mItems;
    }

    /**
     * TMDB chapters are matched by episode number so a missing or partial
     * season simply falls back to the site supplied title.
     */
    public void setChapters(List<Meta.Chapter> chapters) {
        mChapters.clear();
        if (chapters != null) for (Meta.Chapter chapter : chapters) mChapters.put(chapter.getNumber(), chapter);
        notifyItemRangeChanged(0, getItemCount());
    }

    public void setFallbackImage(String url) {
        this.fallbackImage = url == null ? "" : url;
    }

    public String getFallbackImage() {
        return fallbackImage;
    }

    public boolean hasChapters() {
        return !mChapters.isEmpty();
    }

    public int getViewType() {
        return viewType;
    }

    public void setViewType(int viewType) {
        if (this.viewType == viewType) return;
        this.viewType = viewType;
        notifyDataSetChanged();
    }

    public Meta.Chapter getChapter(Episode item) {
        if (mChapters.isEmpty() || item == null) return null;
        Meta.Chapter chapter = mChapters.get(item.getNumber());
        if (chapter != null) return chapter;
        int index = mItems.indexOf(item);
        return index < 0 ? null : mChapters.get(index + 1);
    }

    public boolean isEmpty() {
        return getItemCount() == 0;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @Override
    public int getItemViewType(int position) {
        return viewType;
    }

    @Override
    public void onBindViewHolder(@NonNull BaseEpisodeHolder holder, int position) {
        holder.initView(mItems.get(position));
    }

    @NonNull
    @Override
    public BaseEpisodeHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == ViewType.CARD) {
            return new EpisodeCardHolder(AdapterEpisodeCardBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), this, listener);
        } else if (viewType == ViewType.HORI) {
            return new EpisodeHoriHolder(AdapterEpisodeHoriBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), listener);
        } else {
            return new EpisodeGridHolder(AdapterEpisodeGridBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), listener);
        }
    }
}
