/*
 * Copyright (C) 2019-2025 qwq233 <qwq233@qwq2333.top>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package xyz.nextalone.nnngram.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import xyz.nextalone.nnngram.helpers.XrayProxyHelper;
import xraybridge.Profile;
import xraybridge.Xraybridge;

/**
 * Adds or edits a Shadowsocks / VMess / VLESS proxy handled by the bundled
 * Xray core.
 *
 * <p>The share link is the whole configuration; the Go bridge owns parsing, so
 * this screen only shows the parsed summary and never re-implements a parser.
 * Errors are localised and never echo the link.
 */
public class XrayProxyActivity extends BaseFragment {

    private static final int done_button = 1;
    private static final long PARSE_DEBOUNCE = 300;
    /**
     * Upper bound for the UI-side check only. The bridge enforces the exact
     * UTF-8 limit (16 KiB); this rejects obviously oversized pastes before they
     * are copied across JNI. Never truncates.
     */
    private static final int MAX_LINK_LENGTH = 16 * 1024;

    private final SharedConfig.ProxyInfo editingInfo;

    private EditTextBoldCursor linkField;
    private TextInfoPrivacyCell errorCell;
    private TextSettingsCell protocolCell;
    private TextSettingsCell serverCell;
    private TextSettingsCell transportCell;
    private TextSettingsCell pasteCell;
    private TextSettingsCell versionCell;
    private LinearLayout parsedSection;
    private ActionBarMenuItem doneItem;

    private int revision;
    private boolean parseOk;
    private String parsedLink;
    private String coreVersion;

    private final Runnable parseRunnable = this::parseCurrentLink;

    public XrayProxyActivity() {
        editingInfo = null;
    }

    public XrayProxyActivity(SharedConfig.ProxyInfo info) {
        editingInfo = info;
    }

    /** Adds a proxy from an already known link (QR scan). */
    public XrayProxyActivity(String link) {
        editingInfo = null;
        prefilledLink = link;
    }

    private String prefilledLink;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(LocaleController.getString(editingInfo == null ? R.string.XrayProxyAddTitle : R.string.XrayProxyEditTitle));
        if (parentLayout != null && parentLayout.isLayersLayout()) {
            actionBar.setOccupyStatusBar(false);
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == done_button) {
                    saveProxy();
                }
            }
        });
        doneItem = actionBar.createMenu().addItemWithWidth(done_button, R.drawable.ic_ab_done, AndroidUtilities.dp(56));
        doneItem.setContentDescription(LocaleController.getString(R.string.Done));

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        final FrameLayout frameLayout = (FrameLayout) fragmentView;

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        AndroidUtilities.setScrollViewEdgeEffectColor(scrollView, Theme.getColor(Theme.key_actionBarDefault));
        frameLayout.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        scrollView.setClipToPadding(false);
        final int[] bottomInset = new int[1];
        ViewCompat.setOnApplyWindowInsetsListener(scrollView, (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            if (bottomInset[0] != bottom) {
                bottomInset[0] = bottom;
                scrollView.setPadding(0, 0, 0, bottom);
            }
            return insets;
        });

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final HeaderCell headerCell = new HeaderCell(context);
        headerCell.setText(LocaleController.getString(R.string.XrayProxyLink));
        content.addView(headerCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final FrameLayout inputContainer = new FrameLayout(context);
        inputContainer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        content.addView(inputContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));

        linkField = new EditTextBoldCursor(context);
        linkField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        linkField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        linkField.setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        linkField.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        linkField.setCursorSize(AndroidUtilities.dp(20));
        linkField.setCursorWidth(1.5f);
        linkField.setBackground(null);
        linkField.setSingleLine(true);
        linkField.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        linkField.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        linkField.setTransformHintToHeader(true);
        linkField.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                Theme.getColor(Theme.key_text_RedRegular));
        linkField.setHintText(LocaleController.getString(R.string.XrayProxyLinkHint));
        // No personalized learning: the field receives secrets (SS password,
        // VMess UUID) and must not feed the IME's suggestion model. The flag
        // belongs to imeOptions, not inputType.
        linkField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_URI);
        linkField.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        linkField.setPadding(0, 0, 0, 0);
        inputContainer.addView(linkField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 17, 12, 17, 0));
        linkField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                onLinkChanged();
            }
        });
        linkField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                AndroidUtilities.hideKeyboard(linkField);
                return true;
            }
            return false;
        });

        pasteCell = new TextSettingsCell(context);
        pasteCell.setBackground(Theme.getSelectorDrawable(true));
        pasteCell.setText(LocaleController.getString(R.string.XrayProxyPaste), false);
        pasteCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        pasteCell.setOnClickListener(v -> pasteFromClipboard());
        content.addView(pasteCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 14, 0, 0));

        final ShadowSectionCell shadowCell = new ShadowSectionCell(context);
        content.addView(shadowCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        parsedSection = new LinearLayout(context);
        parsedSection.setOrientation(LinearLayout.VERTICAL);
        parsedSection.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        content.addView(parsedSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        protocolCell = new TextSettingsCell(context);
        parsedSection.addView(protocolCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        serverCell = new TextSettingsCell(context);
        parsedSection.addView(serverCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        transportCell = new TextSettingsCell(context);
        parsedSection.addView(transportCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        errorCell = new TextInfoPrivacyCell(context);
        errorCell.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        errorCell.setBackground(Theme.getThemedDrawable(context, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
        content.addView(errorCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextInfoPrivacyCell infoCell = new TextInfoPrivacyCell(context);
        infoCell.setText(LocaleController.getString(R.string.XrayProxyInfo));
        content.addView(infoCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        versionCell = new TextSettingsCell(context);
        versionCell.setBackground(Theme.getSelectorDrawable(true));
        versionCell.setOnClickListener(v -> showLicense());
        content.addView(versionCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        updateVersionCell();

        if (editingInfo != null) {
            linkField.setText(editingInfo.settings.getSecret());
            linkField.setSelection(linkField.length());
        } else if (!TextUtils.isEmpty(prefilledLink)) {
            linkField.setText(prefilledLink);
            linkField.setSelection(linkField.length());
        }
        onLinkChanged();

        if (editingInfo == null && TextUtils.isEmpty(prefilledLink)) {
            linkField.requestFocus();
            AndroidUtilities.showKeyboard(linkField);
        }

        return fragmentView;
    }

    private void updateVersionCell() {
        if (versionCell == null) {
            return;
        }
        final String version = coreVersion;
        versionCell.setTextAndValue(
                LocaleController.getString(R.string.XrayProxyLicense),
                version == null ? "" : "Xray-core " + version + " · MPL-2.0",
                false);
    }

    private void loadVersion() {
        if (coreVersion != null) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            final String version = XrayProxyHelper.version();
            AndroidUtilities.runOnUIThread(() -> {
                if (isFinished) {
                    return;
                }
                coreVersion = version;
                updateVersionCell();
            });
        });
    }

    private void showLicense() {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.XrayProxyLicense));
        final TextView textView = new TextView(getParentActivity());
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        textView.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), AndroidUtilities.dp(8));
        textView.setMovementMethod(new ScrollingMovementMethod());
        textView.setText(LocaleController.getString(R.string.XrayProxyLicenseLoading));
        builder.setView(textView);
        builder.setNegativeButton(LocaleController.getString(R.string.Close), null);
        final AlertDialog dialog = builder.create();
        showDialog(dialog);
        Utilities.globalQueue.postRunnable(() -> {
            final String license = XrayProxyHelper.licenseText();
            AndroidUtilities.runOnUIThread(() -> {
                if (dialog.isShowing()) {
                    textView.setText(license != null ? license : LocaleController.getString(R.string.XrayProxyLicenseMissing));
                }
            });
        });
    }

    @Override
    public boolean onFragmentCreate() {
        final boolean result = super.onFragmentCreate();
        loadVersion();
        return result;
    }

    @Override
    public void onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(parseRunnable);
        AndroidUtilities.hideKeyboard(linkField);
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
    }

    private void pasteFromClipboard() {
        if (getParentActivity() == null) {
            return;
        }
        final ClipboardManager clipboard = (ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        final ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return;
        }
        CharSequence text;
        try {
            text = clip.getItemAt(0).coerceToText(getParentActivity());
        } catch (Exception e) {
            text = null;
        }
        if (TextUtils.isEmpty(text)) {
            return;
        }
        linkField.setText(text.toString().trim());
        linkField.setSelection(linkField.length());
    }

    private void onLinkChanged() {
        revision++;
        parseOk = false;
        parsedLink = null;
        setParsedVisible(false);
        setDoneEnabled(false);
        AndroidUtilities.cancelRunOnUIThread(parseRunnable);
        final String text = linkField.getText().toString();
        if (text.length() > MAX_LINK_LENGTH) {
            // Reject before the string is copied into the Go heap. The exact
            // UTF-8 limit is still enforced by the bridge.
            errorCell.setVisibility(View.VISIBLE);
            errorCell.setText(LocaleController.getString(R.string.XrayProxyTooLong));
            return;
        }
        errorCell.setVisibility(View.GONE);
        if (!TextUtils.isEmpty(text)) {
            AndroidUtilities.runOnUIThread(parseRunnable, PARSE_DEBOUNCE);
        }
    }

    private void setParsedVisible(boolean visible) {
        parsedSection.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void setDoneEnabled(boolean enabled) {
        if (doneItem != null) {
            doneItem.setEnabled(enabled);
            doneItem.setAlpha(enabled ? 1f : 0.5f);
        }
    }

    private void parseCurrentLink() {
        final String link = linkField.getText().toString().trim();
        if (TextUtils.isEmpty(link)) {
            return;
        }
        final int requestRevision = revision;
        Utilities.globalQueue.postRunnable(() -> {
            Profile profile = null;
            try {
                profile = Xraybridge.parse(link);
            } catch (Throwable ignore) {
                // The bridge reports categories only; nothing secret is exposed.
            }
            final Profile result = profile;
            AndroidUtilities.runOnUIThread(() -> onParsed(requestRevision, link, result));
        });
    }

    private void onParsed(int requestRevision, String link, Profile profile) {
        // A newer keystroke, a finished screen or a changed field always wins.
        if (isFinished || linkField == null || requestRevision != revision) {
            return;
        }
        if (!link.equals(linkField.getText().toString().trim())) {
            return;
        }
        parseOk = profile != null;
        parsedLink = parseOk ? link : null;
        errorCell.setVisibility(parseOk ? View.GONE : View.VISIBLE);
        errorCell.setText(parseOk ? null : LocaleController.getString(R.string.XrayProxyInvalid));
        if (parseOk) {
            final String protocol = profile.protocol();
            final boolean vless = "vless".equals(protocol);
            protocolCell.setTextAndValue(LocaleController.getString(R.string.XrayProxyProtocol),
                    "shadowsocks".equals(protocol) ? "Shadowsocks"
                            : vless ? "VLESS" : "VMess", true);
            serverCell.setTextAndValue(LocaleController.getString(R.string.XrayProxyServer),
                    profile.address() + ":" + profile.port(), true);
            String transport = profile.transport();
            if (vless) {
                // REALITY is its own security layer, never shown as plain TLS.
                final String security = profile.security();
                if ("reality".equals(security)) {
                    transport += " + REALITY";
                } else if ("tls".equals(security)) {
                    transport += " + TLS";
                }
            } else if (profile.tls()) {
                transport += " + TLS";
            }
            transportCell.setTextAndValue(LocaleController.getString(R.string.XrayProxyTransport), transport, false);
        }
        setParsedVisible(parseOk);
        setDoneEnabled(parseOk);
    }

    private void saveProxy() {
        if (!parseOk || parsedLink == null || getParentActivity() == null) {
            return;
        }
        // Re-parse on the bridge before persisting so the stored server matches
        // what the core will actually use.
        final String link = parsedLink;
        final int requestRevision = revision;
        setDoneEnabled(false);
        Utilities.globalQueue.postRunnable(() -> {
            Profile profile = null;
            try {
                profile = Xraybridge.parse(link);
            } catch (Throwable ignore) {
            }
            final Profile result = profile;
            AndroidUtilities.runOnUIThread(() -> {
                if (isFinished || result == null || requestRevision != revision) {
                    if (!isFinished && requestRevision == revision) {
                        setDoneEnabled(true);
                    }
                    return;
                }
                applyProxy(link, result);
            });
        });
    }

    private void applyProxy(String link, Profile profile) {
        SharedConfig.loadProxyList();
        final ProxySettings settings = ProxySettings.builder()
                .setType(ProxySettings.Type.XRAY)
                .setAddress(profile.address())
                .setPort((int) profile.port())
                .setSecret(link)
                .build();
        final SharedConfig.ProxyInfo candidate = new SharedConfig.ProxyInfo(settings);

        final android.content.SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        final android.content.SharedPreferences.Editor editor = preferences.edit();

        if (editingInfo == null) {
            final SharedConfig.ProxyInfo canonical = SharedConfig.addProxy(candidate);
            SharedConfig.currentProxy = canonical;
            editor.putBoolean("proxy_enabled", true);
            canonical.settings.toSharedPreferences(editor);
            editor.commit();
            ConnectionsManager.setProxySettings(true, canonical.settings);
        } else {
            final boolean wasCurrent = SharedConfig.currentProxy == editingInfo;
            final int index = SharedConfig.proxyList.indexOf(editingInfo);
            if (index >= 0) {
                SharedConfig.proxyList.set(index, candidate);
            } else {
                SharedConfig.proxyList.add(0, candidate);
            }
            SharedConfig.saveProxyList();
            if (wasCurrent) {
                // A fresh object keeps a stale availability callback from writing
                // to the configuration the user just replaced.
                SharedConfig.currentProxy = candidate;
                final boolean enabled = preferences.getBoolean("proxy_enabled", true);
                editor.putBoolean("proxy_enabled", enabled);
                candidate.settings.toSharedPreferences(editor);
                editor.commit();
                ConnectionsManager.setProxySettings(enabled, candidate.settings);
            }
        }

        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        finishFragment();
    }
}
