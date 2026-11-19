package dev.menzo.hammurabi;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * An app that forbids capture, as Netflix or a bank does: pictures of the screen come out black (android/
 * smoke.sh checks Hammurabi says so). In Java: opened on its own, the test APK has no Kotlin library.
 */
public class SecureActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        TextView text = new TextView(this);
        text.setText("Your account balance is private.");
        text.setTextSize(22f);
        text.setTextColor(Color.BLACK);
        text.setBackgroundColor(Color.WHITE);
        text.setPadding(48, 300, 48, 48);
        setContentView(text);
    }
}
