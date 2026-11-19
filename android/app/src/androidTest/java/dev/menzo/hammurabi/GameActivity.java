package dev.menzo.hammurabi;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * A game's dialogue, as many show it: a box at the bottom whose text is written out letter by letter, in
 * capitals, then stays (android/smoke.sh checks live translation waits for the whole line). In Java: opened
 * on its own, the test APK has no Kotlin library.
 */
public class GameActivity extends Activity {
    static final String[] LINES = {
        "THE CASTLE GATES ARE CLOSED. WE MUST FIND ANOTHER WAY IN.",
        "Do you still have the map I gave you yesterday?",
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView box;
    private int line = 0;
    private int shown = 0;

    private final Runnable type = new Runnable() {
        @Override
        public void run() {
            String text = LINES[line % LINES.length];
            if (shown < text.length()) {
                shown++;
                box.setText(text.substring(0, shown));
                handler.postDelayed(this, 45);
            } else {
                // The whole line stays a while, then the next one is written out.
                line++;
                shown = 0;
                handler.postDelayed(this, 7000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(24, 40, 30));
        box = new TextView(this);
        box.setTextColor(Color.WHITE);
        box.setTextSize(19f);
        box.setPadding(40, 32, 40, 32);
        box.setMinLines(3);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(20, 28, 70));
        background.setStroke(4, Color.rgb(200, 180, 120));
        background.setCornerRadius(20f);
        box.setBackground(background);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        params.setMargins(40, 0, 40, 220);
        root.addView(box, params);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(type);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(type);
        super.onPause();
    }
}
