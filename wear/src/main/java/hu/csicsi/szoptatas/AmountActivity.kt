package hu.csicsi.szoptatas

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** ml picker: turn the bezel or tap − / +, then Mentés. */
class AmountActivity : Activity() {

    private lateinit var kind: String
    private var value = 30
    private var step = 5
    private lateinit var number: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = intent.getStringExtra("kind") ?: "Tápszer"
        value = intent.getIntExtra("start", 30)
        step = intent.getIntExtra("step", 5)

        number = TextView(this).apply {
            textSize = 40f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isFocusableInTouchMode = true
            addView(TextView(this@AmountActivity).apply {
                text = kind
                textSize = 14f
                setTextColor(Color.parseColor("#9C95A4"))
                gravity = Gravity.CENTER
            })
            addView(number)
            addView(LinearLayout(this@AmountActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(small("−") { change(-1) })
                addView(small("+") { change(1) })
            })
            addView(Button(this@AmountActivity).apply {
                text = "Mentés"
                isAllCaps = false
                textSize = 15f
                setTextColor(Color.WHITE)
                stateListAnimator = null
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#D6336C"))
                    cornerRadius = dp(18).toFloat()
                }
                setOnClickListener { save() }
            }, LinearLayout.LayoutParams(dp(110), dp(44)).apply { topMargin = dp(8) })
        }
        setContentView(root)
        root.requestFocus()
        show()
    }

    /** Rotating bezel / crown changes the amount. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
            change(if (delta > 0) 1 else -1)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun change(dir: Int) {
        value = (value + dir * step).coerceIn(0, 2000)
        window.decorView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        show()
    }

    private fun show() {
        number.text = "$value ml"
    }

    private fun save() {
        if (value <= 0) return
        WatchState.send(this, "/log/text", "$kind $value ml")
        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        Toast.makeText(this, "Küldve: $kind $value ml", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun small(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 22f
        setTextColor(Color.WHITE)
        stateListAnimator = null
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#3A3740"))
            shape = GradientDrawable.OVAL
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(dp(10), dp(4), dp(10), dp(4)) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
