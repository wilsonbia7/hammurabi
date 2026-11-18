package dev.menzo.hammurabi

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** Another app in front, showing a post in English and one in Turkish (android/smoke.sh opens it). */
class TextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                setBackgroundColor(Color.WHITE)
                setTextColor(Color.BLACK)
                textSize = 20f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(48, 48, 48, 48)
                text = "Where did you put the keys? We have to leave before dawn.\n\n\n" +
                    "Bugün arkadaşlarla harika bir akşam geçirdik."
            },
        )
    }
}
