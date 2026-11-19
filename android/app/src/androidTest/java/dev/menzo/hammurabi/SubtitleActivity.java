package dev.menzo.hammurabi;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * A video with subtitles, as a player shows it: a picture that keeps moving, and an English subtitle at the
 * bottom that changes every few seconds (android/smoke.sh watches live translation with it). In Java: opened
 * on its own, the test APK has no Kotlin library.
 */
public class SubtitleActivity extends Activity {
    static final String[] LINES = {
        "Where did you put the keys?",
        "We have to leave before dawn.",
        "I will never forget this night.",
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView subtitle;
    private int next = 0;

    private final Runnable change = new Runnable() {
        @Override
        public void run() {
            subtitle.setText(LINES[next % LINES.length]);
            next++;
            handler.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.addView(new Picture(this));
        subtitle = new TextView(this);
        subtitle.setTextColor(Color.WHITE);
        subtitle.setTextSize(21f);
        subtitle.setShadowLayer(6f, 0f, 2f, Color.BLACK);
        subtitle.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = 260;
        params.leftMargin = 40;
        params.rightMargin = 40;
        root.addView(subtitle, params);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(change);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(change);
        super.onPause();
    }

    /** A dark picture whose colours keep shifting, as a film's do. */
    static class Picture extends View {
        private float phase = 0f;

        Picture(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            phase += 0.02f;
            int r = (int) (40 + 30 * Math.sin(phase));
            int g = (int) (50 + 30 * Math.sin(phase * 1.3 + 1));
            int b = (int) (70 + 40 * Math.sin(phase * 0.7 + 2));
            canvas.drawColor(Color.rgb(r, g, b));
            postInvalidateDelayed(33);
        }
    }
}
