package xyz.nextalone.nnngram.activity;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.LaunchActivity;

import xyz.nextalone.nnngram.helpers.DisguiseLauncher;
import xyz.nextalone.nnngram.helpers.DisguiseTapGate;
import xyz.nextalone.nnngram.helpers.LauncherNameController;

/**
 * Decoy APK-testing screen shown at startup while the disguise toggle is on.
 * It is purely cosmetic: nothing is inspected and the analysis always fails.
 */
public class FakeApkTestActivity extends Activity {

    private static final int ACCENT_COLOR = 0xFF2271C2;
    private static final int TEXT_COLOR = 0xFF1A1A1A;
    private static final int SECONDARY_TEXT_COLOR = 0xFF61686F;
    private static final long TAP_WINDOW_MS = 1500;
    private static final long ANALYSIS_MS = 800;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DisguiseTapGate tapGate = new DisguiseTapGate(TAP_WINDOW_MS);
    private final Runnable failRunnable = this::showAnalysisFailed;

    private TextView statusView;
    private ProgressBar analysisProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LauncherNameController.applyTaskLabel(this);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(buildContent());
        showStatus(R.string.FakeApkStatus);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(failRunnable);
        super.onDestroy();
    }

    // Back needs no override: the decoy lives in its own task with nothing real
    // beneath it, so the default back closes it and returns to the home screen.

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(24);
        body.setPadding(pad, AndroidUtilities.dp(24), pad, pad);

        TextView title = text(LocaleController.getString("FakeApkTitle", R.string.FakeApkTitle), 26, TEXT_COLOR);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        body.addView(title, wrap());

        TextView subtitle = text(LocaleController.getString("FakeApkSubtitle", R.string.FakeApkSubtitle), 14, SECONDARY_TEXT_COLOR);
        LinearLayout.LayoutParams subtitleParams = wrap();
        subtitleParams.topMargin = AndroidUtilities.dp(4);
        body.addView(subtitle, subtitleParams);

        LinearLayout card = addCard(body, R.string.FakeApkInfoLabel);
        addInfoRow(card, "Package", "com.android.apktest");
        addInfoRow(card, "Version", "2.4.1 (241)");
        addInfoRow(card, "Min SDK", "Android 8.0 · API 26");
        addInfoRow(card, "Target SDK", "Android 14 · API 34");
        addInfoRow(card, "ABI", "arm64-v8a");
        LinearLayout sizeRow = addInfoRow(card, "Size", "18.6 MB");
        sizeRow.setOnClickListener(v -> {
            if (tapGate.onTap(SystemClock.elapsedRealtime())) {
                openRealApp();
            }
        });
        sizeRow.setOnLongClickListener(v -> {
            tapGate.onLongPress(SystemClock.elapsedRealtime());
            return true;
        });
        sizeRow.setSoundEffectsEnabled(false);
        sizeRow.setHapticFeedbackEnabled(false);

        LinearLayout analysisCard = addCard(body, R.string.FakeApkAnalysisLabel);
        statusView = text("", 16, TEXT_COLOR);
        LinearLayout.LayoutParams statusParams = wrap();
        statusParams.topMargin = AndroidUtilities.dp(8);
        analysisCard.addView(statusView, statusParams);

        TextView analysisScope = text(LocaleController.getString(R.string.FakeApkAnalysisScope), 13, SECONDARY_TEXT_COLOR);
        LinearLayout.LayoutParams scopeParams = wrap();
        scopeParams.topMargin = AndroidUtilities.dp(4);
        analysisCard.addView(analysisScope, scopeParams);

        analysisProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        analysisProgress.setIndeterminate(true);
        analysisProgress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT_COLOR));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(6));
        progressParams.topMargin = AndroidUtilities.dp(12);
        analysisCard.addView(analysisProgress, progressParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button analyze = new Button(this);
        analyze.setText(LocaleController.getString("FakeApkAnalyze", R.string.FakeApkAnalyze));
        analyze.setAllCaps(false);
        analyze.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        analyze.setTextColor(Color.WHITE);
        GradientDrawable buttonBackground = new GradientDrawable();
        buttonBackground.setColor(ACCENT_COLOR);
        buttonBackground.setCornerRadius(AndroidUtilities.dp(10));
        analyze.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), buttonBackground, null));
        analyze.setOnClickListener(v -> onAnalyze());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(48));
        buttonParams.setMargins(pad, 0, pad, pad);
        root.addView(analyze, buttonParams);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        return root;
    }

    private LinearLayout addCard(LinearLayout body, int titleRes) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFF5F6F8);
        background.setCornerRadius(AndroidUtilities.dp(12));
        card.setBackground(background);
        int pad = AndroidUtilities.dp(16);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams cardParams = wrap();
        cardParams.topMargin = AndroidUtilities.dp(16);
        body.addView(card, cardParams);

        TextView title = text(LocaleController.getString(titleRes), 13, SECONDARY_TEXT_COLOR);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(title, wrap());
        return card;
    }

    private LinearLayout addInfoRow(LinearLayout card, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(AndroidUtilities.dp(44));
        card.addView(row, wrap());

        TextView labelView = text(label, 13, SECONDARY_TEXT_COLOR);
        row.addView(labelView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView valueView = text(value, 13, TEXT_COLOR);
        row.addView(valueView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void onAnalyze() {
        handler.removeCallbacks(failRunnable);
        showStatus(R.string.FakeApkAnalyzing);
        handler.postDelayed(failRunnable, ANALYSIS_MS);
    }

    private void showAnalysisFailed() {
        showStatus(R.string.FakeApkAnalysisFailed);
    }

    private void showStatus(int res) {
        if (statusView != null) {
            statusView.setText(LocaleController.getString(res));
            analysisProgress.setVisibility(res == R.string.FakeApkAnalyzing ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void openRealApp() {
        Intent original = getIntent().getParcelableExtra(DisguiseLauncher.EXTRA_REAL_LAUNCH_INTENT);
        Intent real = original != null ? new Intent(original) : new Intent();
        real.setComponent(new ComponentName(this, LaunchActivity.class));
        DisguiseLauncher.permitNextLaunch();
        real.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(real);
        finishAndRemoveTask();
    }

    private TextView text(String value, float sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        return view;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
