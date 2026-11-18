package dev.menzo.hammurabi

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Another app in front, as a feed shows it: a post in English, one in Turkish, an English one on a dark card,
 * and a button (android/smoke.sh and TranslationLayerTest translate it).
 */
class TextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val feed = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(48, 220, 48, 48)
        }
        fun post(text: String, size: Float, color: Int = Color.BLACK, card: Int? = null) {
            feed.addView(
                TextView(this).apply {
                    this.text = text
                    textSize = size
                    setTextColor(color)
                    setPadding(32, 28, 32, 28)
                    if (card != null) background = GradientDrawable().apply { setColor(card); cornerRadius = 24f }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = 56 },
            )
        }
        post("John Carter", 16f, Color.rgb(60, 60, 60))
        post("Where did you put the keys? We have to leave before dawn.", 20f)
        post("Bugün arkadaşlarla harika bir akşam geçirdik.", 20f)
        post("The weather is beautiful today. Let's go to the beach!", 19f, Color.WHITE, Color.rgb(32, 52, 92))
        post("Follow", 17f, Color.rgb(37, 99, 235))
        setContentView(feed)
    }
}
