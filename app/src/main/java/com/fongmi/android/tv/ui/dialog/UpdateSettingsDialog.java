package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Update;
import com.fongmi.android.tv.databinding.DialogUpdateSettingsBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.update.GithubProxy;
import com.fongmi.android.tv.update.OciMirror;
import com.fongmi.android.tv.update.UpdateSource;
import com.fongmi.android.tv.update.UpdateUrl;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

public final class UpdateSettingsDialog {

    private UpdateSettingsDialog() {
    }

    public static void show(FragmentActivity activity) {
        DialogUpdateSettingsBinding binding = DialogUpdateSettingsBinding.inflate(LayoutInflater.from(activity));
        State state = State.load();
        Dialog dialog = LightDialog.create(activity, null, binding.getRoot());
        render(activity, binding, state);
        bind(activity, dialog, binding, state);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        configureWindow(activity, dialog);
        binding.sourceAuto.requestFocus();
    }

    private static void bind(FragmentActivity activity, Dialog dialog, DialogUpdateSettingsBinding binding, State state) {
        binding.close.setOnClickListener(view -> dialog.dismiss());
        binding.channelGroup.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) state.channel = id == R.id.beta ? Update.CHANNEL_BETA : Update.CHANNEL_STABLE;
        });
        binding.sourceGroup.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            state.source = id == R.id.sourceGithub ? UpdateSource.GITHUB : id == R.id.sourceOci ? UpdateSource.OCI : UpdateSource.AUTO;
        });
        binding.githubModeGroup.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) state.githubMode = id == R.id.githubModeStrip ? GithubProxy.MODE_STRIP_SCHEME : GithubProxy.MODE_FULL_URL;
        });
        binding.githubProxy.setOnClickListener(view -> chooseGithub(activity, binding, state));
        binding.ociMirror.setOnClickListener(view -> chooseOci(activity, binding, state));
        binding.save.setOnClickListener(view -> save(activity, dialog, binding, state));
    }

    private static void chooseGithub(FragmentActivity activity, DialogUpdateSettingsBinding binding, State state) {
        GithubProxy.Preset[] presets = GithubProxy.presets();
        CharSequence[] labels = new CharSequence[presets.length];
        int selected = 0;
        for (int i = 0; i < presets.length; i++) {
            labels[i] = label(activity, presets[i].label, presets[i].id);
            if (presets[i].id.equals(state.githubProxy)) selected = i;
        }
        ChoiceDialog.showSingle(activity, R.string.update_github_proxy, labels, selected, which -> {
            state.githubProxy = presets[which].id;
            renderGithub(activity, binding, state);
        });
    }

    private static void chooseOci(FragmentActivity activity, DialogUpdateSettingsBinding binding, State state) {
        OciMirror.Preset[] presets = OciMirror.presets();
        CharSequence[] labels = new CharSequence[presets.length];
        int selected = 0;
        for (int i = 0; i < presets.length; i++) {
            labels[i] = label(activity, presets[i].label, presets[i].id);
            if (presets[i].id.equals(state.ociMirror)) selected = i;
        }
        ChoiceDialog.showSingle(activity, R.string.update_oci_mirror, labels, selected, which -> {
            state.ociMirror = presets[which].id;
            renderOci(activity, binding, state);
        });
    }

    private static String label(FragmentActivity activity, String label, String id) {
        if (GithubProxy.DIRECT.equals(id) || OciMirror.DIRECT.equals(id)) return activity.getString(R.string.update_proxy_direct);
        if (GithubProxy.CUSTOM.equals(id) || OciMirror.CUSTOM.equals(id)) return activity.getString(R.string.update_proxy_custom);
        return label;
    }

    private static void save(FragmentActivity activity, Dialog dialog, DialogUpdateSettingsBinding binding, State state) {
        state.githubCustom = text(binding.githubCustom.getText());
        state.ociCustom = text(binding.ociCustom.getText());
        binding.githubCustomLayout.setError(null);
        binding.ociCustomLayout.setError(null);
        try {
            if (GithubProxy.CUSTOM.equals(state.githubProxy)) UpdateUrl.requireHttpsOrigin(state.githubCustom);
        } catch (Exception e) {
            binding.githubCustomLayout.setError(activity.getString(R.string.update_proxy_invalid));
            return;
        }
        try {
            if (OciMirror.CUSTOM.equals(state.ociMirror)) UpdateUrl.requireHttpsOrigin(state.ociCustom);
        } catch (Exception e) {
            binding.ociCustomLayout.setError(activity.getString(R.string.update_proxy_invalid));
            return;
        }
        Setting.putUpdateChannel(state.channel);
        Setting.putUpdateSource(state.source);
        Setting.putUpdateFallback(binding.fallback.isChecked());
        Setting.putUpdateGithubProxy(state.githubProxy);
        Setting.putUpdateGithubProxyUrl(state.githubCustom);
        Setting.putUpdateGithubProxyMode(state.githubMode);
        Setting.putUpdateOciMirror(state.ociMirror);
        Setting.putUpdateOciMirrorUrl(state.ociCustom);
        dialog.dismiss();
        Notify.show(R.string.update_settings_saved);
    }

    private static void render(FragmentActivity activity, DialogUpdateSettingsBinding binding, State state) {
        binding.channelGroup.check(Update.CHANNEL_BETA.equals(state.channel) ? R.id.beta : R.id.stable);
        binding.sourceGroup.check(UpdateSource.GITHUB.equals(state.source) ? R.id.sourceGithub : UpdateSource.OCI.equals(state.source) ? R.id.sourceOci : R.id.sourceAuto);
        binding.fallback.setChecked(state.fallback);
        binding.githubCustom.setText(state.githubCustom);
        binding.ociCustom.setText(state.ociCustom);
        binding.githubModeGroup.check(GithubProxy.MODE_STRIP_SCHEME.equals(state.githubMode) ? R.id.githubModeStrip : R.id.githubModeFull);
        renderGithub(activity, binding, state);
        renderOci(activity, binding, state);
    }

    private static void renderGithub(FragmentActivity activity, DialogUpdateSettingsBinding binding, State state) {
        GithubProxy.Preset preset = GithubProxy.find(state.githubProxy);
        binding.githubProxy.setText(activity.getString(R.string.update_github_proxy_value, label(activity, preset.label, preset.id)));
        boolean custom = GithubProxy.CUSTOM.equals(preset.id);
        binding.githubCustomLayout.setVisibility(custom ? View.VISIBLE : View.GONE);
        binding.githubModeGroup.setVisibility(custom ? View.VISIBLE : View.GONE);
    }

    private static void renderOci(FragmentActivity activity, DialogUpdateSettingsBinding binding, State state) {
        OciMirror.Preset preset = OciMirror.find(state.ociMirror);
        binding.ociMirror.setText(activity.getString(R.string.update_oci_mirror_value, label(activity, preset.label, preset.id)));
        binding.ociCustomLayout.setVisibility(OciMirror.CUSTOM.equals(preset.id) ? View.VISIBLE : View.GONE);
    }

    private static String text(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private static void configureWindow(FragmentActivity activity, Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = (int) (ResUtil.getScreenWidth(activity) * (ResUtil.isLand(activity) ? 0.62f : 0.92f));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.CENTER;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setAttributes(params);
        window.setLayout(params.width, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private static final class State {

        private String channel;
        private String source;
        private boolean fallback;
        private String githubProxy;
        private String githubCustom;
        private String githubMode;
        private String ociMirror;
        private String ociCustom;

        private static State load() {
            State state = new State();
            state.channel = Setting.getUpdateChannel();
            state.source = Setting.getUpdateSource();
            state.fallback = Setting.isUpdateFallback();
            state.githubProxy = Setting.getUpdateGithubProxy();
            state.githubCustom = Setting.getUpdateGithubProxyUrl();
            state.githubMode = Setting.getUpdateGithubProxyMode();
            state.ociMirror = Setting.getUpdateOciMirror();
            state.ociCustom = Setting.getUpdateOciMirrorUrl();
            return state;
        }
    }
}
