package xyz.nextalone.nnngram.activity;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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

/**
 * Decoy APK-testing screen shown at startup while the disguise toggle is on.
 * It is purely cosmetic: nothing is inspected and the analysis always fails.
 * Only three quick taps on the analyze button reveal the real app.
 */
public class FakeApkTestActivity extends Activity {

    private static final long TAP_WINDOW_MS = 1500;
    private static final long ANALYSIS_MS = 800;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DisguiseTapGate tapGate = new DisguiseTapGate(TAP_WINDOW_MS);
    private final Runnable failRunnable = this::showAnalysisFailed;

    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

        TextView title = text(LocaleController.getString("FakeApkTitle", R.string.FakeApkTitle), 26, Color.rgb(26, 26, 26));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        body.addView(title, wrap());

        TextView subtitle = text(LocaleController.getString("FakeApkSubtitle", R.string.FakeApkSubtitle), 14, Color.rgb(112, 117, 121));
        LinearLayout.LayoutParams subtitleParams = wrap();
        subtitleParams.topMargin = AndroidUtilities.dp(4);
        body.addView(subtitle, subtitleParams);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(Color.rgb(245, 246, 248));
        cardBackground.setCornerRadius(AndroidUtilities.dp(12));
        card.setBackground(cardBackground);
        int cardPad = AndroidUtilities.dp(16);
        card.setPadding(cardPad, cardPad, cardPad, cardPad);
        LinearLayout.LayoutParams cardParams = wrap();
        cardParams.topMargin = AndroidUtilities.dp(24);
        body.addView(card, cardParams);

        TextView infoLabel = text(LocaleController.getString("FakeApkInfoLabel", R.string.FakeApkInfoLabel), 13, Color.rgb(112, 117, 121));
        infoLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(infoLabel, wrap());

        addInfoRow(card, "Package", "com.android.apktest");
        addInfoRow(card, "Version", "2.4.1 (241)");
        addInfoRow(card, "SDK", "Android 14 · API 34");
        addInfoRow(card, "Size", "18.6 MB");

        statusView = text("", 15, Color.rgb(112, 117, 121));
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = wrap();
        statusParams.topMargin = AndroidUtilities.dp(32);
        body.addView(statusView, statusParams);

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
        buttonBackground.setColor(Color.rgb(51, 144, 236));
        buttonBackground.setCornerRadius(AndroidUtilities.dp(10));
        analyze.setBackground(buttonBackground);
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

    private void addInfoRow(LinearLayout card, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = wrap();
        rowParams.topMargin = AndroidUtilities.dp(10);
        card.addView(row, rowParams);

        TextView labelView = text(label, 13, Color.rgb(112, 117, 121));
        row.addView(labelView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView valueView = text(value, 13, Color.rgb(26, 26, 26));
        row.addView(valueView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void onAnalyze() {
        boolean unlock = tapGate.onTap(SystemClock.elapsedRealtime());
        handler.removeCallbacks(failRunnable);
        if (unlock) {
            openRealApp();
            return;
        }
        showStatus(R.string.FakeApkAnalyzing);
        handler.postDelayed(failRunnable, ANALYSIS_MS);
    }

    private void showAnalysisFailed() {
        showStatus(R.string.FakeApkAnalysisFailed);
    }

    private void showStatus(int res) {
        if (statusView != null) {
            statusView.setText(LocaleController.getString(res));
        }
    }

    private void openRealApp() {
        Intent original = getIntent().getParcelableExtra(DisguiseLauncher.EXTRA_REAL_LAUNCH_INTENT);
        Intent real = original != null ? new Intent(original) : new Intent();
        real.setComponent(new ComponentName(this, LaunchActivity.class));
        DisguiseLauncher.permitNextLaunch();
        real.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(real);
        finish();
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
