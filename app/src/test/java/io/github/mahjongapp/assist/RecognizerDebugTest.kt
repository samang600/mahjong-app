package io.github.mahjongapp.assist

import android.graphics.BitmapFactory
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RecognizerDebugTest {
    @Test
    fun debugSavedScreens() {
        val dir = File(System.getenv("RECOGNITION_FAILURE_DIR") ?: System.getProperty("screenshotDir") ?: "build/screenshots")
        val files = dir.listFiles { f -> f.name.startsWith("recognition_failure") }.orEmpty()
        assumeTrue(files.isNotEmpty())
        for (f in files) {
            val bmp = BitmapFactory.decodeFile(f.path)
            val r = HandRecognizer()
            val seen = r.recognize(bmp)
            println("${f.name}: ${r.lastDebug} -> ${seen?.kinds}")
            seen?.tiles?.firstOrNull()?.let { t0 ->
                val box = android.graphics.Rect(t0.rect)
                println("  first tile distances: " + r.debugDistances(bmp, box, listOf(0, 1, 2, 3, 4)))
            }
            // Row profile near the bottom.
            val w = bmp.width; val h = bmp.height
            for (y in (h * 0.6).toInt() until h step (h / 40)) {
                var n = 0
                for (x in 0 until w step 2) if (HandRecognizer.isCream(bmp.getPixel(x, y))) n++
                val c = bmp.getPixel(w / 4, y)
                println("  y=$y frac=${"%.2f".format(n * 2f / w)} sample=(${android.graphics.Color.red(c)},${android.graphics.Color.green(c)},${android.graphics.Color.blue(c)})")
            }
        }
    }
}
