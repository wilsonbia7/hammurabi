package dev.menzo.hammurabi;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Another app in front, as a feed shows it: a post in English, one in Turkish, an English one on a dark
 * card, and a button (android/smoke.sh and TranslationLayerTest translate it). In Java: opened on its own,
 * the test APK has no Kotlin library (the app's is used only while the tests run).
 */
public class TextActivity extends Activity {
    private LinearLayout feed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        feed = new LinearLayout(this);
        feed.setOrientation(LinearLayout.VERTICAL);
        feed.setBackgroundColor(Color.WHITE);
        feed.setPadding(48, 220, 48, 48);
        post("John Carter", 16f, Color.rgb(60, 60, 60), null);
        post("Where did you put the keys? We have to leave before dawn.", 20f, Color.BLACK, null);
        post("Bugün arkadaşlarla harika bir akşam geçirdik.", 20f, Color.BLACK, null);
        post("The weather is beautiful today. Let's go to the beach!", 19f, Color.WHITE, Color.rgb(32, 52, 92));
        post("Follow", 17f, Color.rgb(37, 99, 235), null);
        setContentView(feed);
    }

    private void post(String text, float size, int color, Integer card) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(32, 28, 32, 28);
        if (card != null) {
            GradientDrawable background = new GradientDrawable();
            background.setColor(card);
            background.setCornerRadius(24f);
            view.setBackground(background);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = 56;
        feed.addView(view, params);
    }
}
