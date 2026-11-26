package hu.csicsi.szoptatas

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Apps targeting Android 15+ draw edge-to-edge: content would sit under the status
 * and navigation bars. Adds the bar sizes as padding on top of the view's own padding.
 */
fun View.padForSystemBars() {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(left + b.left, top + b.top, right + b.right, bottom + b.bottom)
        } else {
            @Suppress("DEPRECATION")
            v.setPadding(
                left + insets.systemWindowInsetLeft, top + insets.systemWindowInsetTop,
                right + insets.systemWindowInsetRight, bottom + insets.systemWindowInsetBottom,
            )
        }
        insets
    }
    requestApplyInsets()
}
