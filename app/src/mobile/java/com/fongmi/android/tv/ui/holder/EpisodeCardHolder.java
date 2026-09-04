package com.fongmi.android.tv.ui.holder;

import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.MetaApi;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Meta;
import com.fongmi.android.tv.databinding.AdapterEpisodeCardBinding;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.base.BaseEpisodeHolder;
import com.fongmi.android.tv.utils.ImgUtil;

/**
 * Renders one site episode as a TMDB styled card. TMDB data is decoration only:
 * the click still forwards the original {@link Episode} to the activity.
 */
public class EpisodeCardHolder extends BaseEpisodeHolder {

    private final EpisodeAdapter.OnClickListener listener;
    private final AdapterEpisodeCardBinding binding;
    private final EpisodeAdapter adapter;

    public EpisodeCardHolder(@NonNull AdapterEpisodeCardBinding binding, EpisodeAdapter adapter, EpisodeAdapter.OnClickListener listener) {
        super(binding.getRoot());
        this.binding = binding;
        this.adapter = adapter;
        this.listener = listener;
    }

    @Override
    public void initView(Episode item) {
        Meta.Chapter chapter = adapter == null ? null : adapter.getChapter(item);
        binding.getRoot().setActivated(item.isSelected());
        binding.text.setActivated(item.isSelected());
        binding.text.setText(getTitle(item, chapter));
        setNumber(item, chapter);
        setDate(chapter);
        setOverview(chapter);
        setStill(item, chapter);
        binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
    }

    private String getTitle(Episode item, Meta.Chapter chapter) {
        if (chapter != null && !TextUtils.isEmpty(chapter.getName())) return chapter.getName();
        return item.getDisplayName();
    }

    private void setNumber(Episode item, Meta.Chapter chapter) {
        String label = binding.getRoot().getContext().getString(R.string.detail_episode_index, getNumber(item, chapter));
        int runtime = chapter == null ? 0 : chapter.getRuntime();
        if (runtime > 0) label = label + " · " + binding.getRoot().getContext().getString(R.string.detail_episode_runtime, runtime);
        binding.number.setText(label);
    }

    private int getNumber(Episode item, Meta.Chapter chapter) {
        if (chapter != null && chapter.getNumber() > 0) return chapter.getNumber();
        if (item.getNumber() > 0) return item.getNumber();
        return getBindingAdapterPosition() + 1;
    }

    private void setDate(Meta.Chapter chapter) {
        String date = chapter == null ? "" : chapter.getAirDate();
        binding.date.setText(date);
        binding.date.setVisibility(TextUtils.isEmpty(date) ? View.GONE : View.VISIBLE);
    }

    private void setOverview(Meta.Chapter chapter) {
        String overview = chapter == null ? "" : chapter.getOverview();
        binding.overview.setText(overview);
        binding.overview.setVisibility(TextUtils.isEmpty(overview) ? View.GONE : View.VISIBLE);
    }

    private void setStill(Episode item, Meta.Chapter chapter) {
        String still = chapter == null ? "" : MetaApi.image(chapter.getStill(), false);
        String fallback = adapter == null ? "" : adapter.getFallbackImage();
        ImgUtil.load(getTitle(item, chapter), TextUtils.isEmpty(still) ? fallback : still, binding.still);
    }
}
