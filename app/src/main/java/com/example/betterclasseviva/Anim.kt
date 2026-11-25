package com.example.betterclasseviva

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Utility di animazione condivise. Nessuna dipendenza esterna: solo framework Android.
 */
object Anim {
    /** Curva "emphasized decelerate" di Material 3: parte veloce, si posa dolcemente. */
    val emphasized: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    internal val running = WeakHashMap<View, ValueAnimator>()
    internal val originalHeight = WeakHashMap<View, Int>()
    internal val openTarget = WeakHashMap<View, Boolean>()
}

private fun View.dpF(v: Float): Float = v * resources.displayMetrics.density

// ---------------------------------------------------------------------------------------------
// Entrate
// ---------------------------------------------------------------------------------------------

/** Fade + salita morbida. Ideale per sezioni/card in cascata (stagger). */
fun View.revealUp(delay: Long = 0L, distanceDp: Float = 22f, durationMs: Long = 520L) {
    animate().cancel()
    alpha = 0f
    translationY = dpF(distanceDp)
    animate()
        .alpha(1f)
        .translationY(0f)
        .setStartDelay(delay)
        .setDuration(durationMs)
        .setInterpolator(Anim.emphasized)
        .start()
}

/** Comparsa "a bolla": scala da piccolo con rimbalzo. */
fun View.popIn(delay: Long = 0L, durationMs: Long = 440L, overshoot: Float = 2.2f) {
    animate().cancel()
    alpha = 0f
    scaleX = 0.5f
    scaleY = 0.5f
    animate()
        .alpha(1f)
        .scaleX(1f)
        .scaleY(1f)
        .setStartDelay(delay)
        .setDuration(durationMs)
        .setInterpolator(OvershootInterpolator(overshoot))
        .start()
}

/** Piccolo "battito": rimpicciolisce e torna con rimbalzo. */
fun View.bump(scale: Float = 0.92f) {
    animate().cancel()
    animate()
        .scaleX(scale).scaleY(scale)
        .setStartDelay(0)
        .setDuration(90)
        .setInterpolator(DecelerateInterpolator())
        .withEndAction {
            animate()
                .scaleX(1f).scaleY(1f)
                .setStartDelay(0)
                .setDuration(420)
                .setInterpolator(OvershootInterpolator(3f))
                .start()
        }
        .start()
}

/** Scuotimento orizzontale (errore di login). */
fun View.shake() {
    val d = dpF(1f)
    ObjectAnimator.ofFloat(
        this, View.TRANSLATION_X,
        0f, -14f * d, 14f * d, -10f * d, 10f * d, -5f * d, 5f * d, 0f
    ).apply {
        duration = 480
        start()
    }
}

/** Feedback al tocco: si schiaccia leggermente e torna con una molla. Non consuma il click. */
@SuppressLint("ClickableViewAccessibility")
fun View.addPressScale(scaleDown: Float = 0.96f) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate()
                .scaleX(scaleDown).scaleY(scaleDown)
                .setStartDelay(0)
                .setDuration(110)
                .setInterpolator(DecelerateInterpolator())
                .start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate()
                .scaleX(1f).scaleY(1f)
                .setStartDelay(0)
                .setDuration(380)
                .setInterpolator(OvershootInterpolator(2.5f))
                .start()
        }
        false
    }
}

/** Dissolvenza incrociata tra due contenitori. */
fun crossfade(out: View, into: View, durationMs: Long = 240L) {
    into.animate().cancel()
    into.alpha = 0f
    into.visibility = View.VISIBLE
    into.animate()
        .alpha(1f)
        .setStartDelay(durationMs / 3)
        .setDuration(durationMs)
        .setInterpolator(Anim.emphasized)
        .start()

    out.animate().cancel()
    out.animate()
        .alpha(0f)
        .setStartDelay(0)
        .setDuration(durationMs)
        .withEndAction {
            out.visibility = View.GONE
            out.alpha = 1f
        }
        .start()
}

// ---------------------------------------------------------------------------------------------
// Numeri e colori
// ---------------------------------------------------------------------------------------------

/** Conteggio animato del testo da [from] a [to]. */
fun TextView.animateNumber(
    from: Float,
    to: Float,
    durationMs: Long = 800L,
    delay: Long = 0L,
    onEnd: (() -> Unit)? = null,
    formatter: (Float) -> String
): ValueAnimator {
    text = formatter(from)
    return ValueAnimator.ofFloat(from, to).apply {
        duration = durationMs
        startDelay = delay
        interpolator = Anim.emphasized
        addUpdateListener { text = formatter(it.animatedValue as Float) }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                text = formatter(to)
                onEnd?.invoke()
            }
        })
        start()
    }
}

/** Transizione morbida del colore del testo. */
fun TextView.animateColorTo(to: Int, durationMs: Long = 500L, delay: Long = 0L): ValueAnimator =
    ValueAnimator.ofObject(ArgbEvaluator(), currentTextColor, to).apply {
        duration = durationMs
        startDelay = delay
        addUpdateListener { setTextColor(it.animatedValue as Int) }
        start()
    }

// ---------------------------------------------------------------------------------------------
// Espansione / chiusura fluida (anima l'altezza reale, senza TransitionManager)
// ---------------------------------------------------------------------------------------------

/** true se la vista è aperta (o sta per aprirsi). */
fun View.isOpenTarget(): Boolean = Anim.openTarget[this] ?: (visibility == View.VISIBLE)

fun View.expandSmooth(durationMs: Long = 360L, delay: Long = 0L, onEnd: (() -> Unit)? = null) {
    val view = this
    val lp = layoutParams ?: return
    val parentGroup = parent as? ViewGroup ?: return

    Anim.running.remove(view)?.cancel()
    val original = Anim.originalHeight.getOrPut(view) { lp.height }

    val fullHeight = if (original >= 0) {
        original
    } else {
        val m = lp as? ViewGroup.MarginLayoutParams
        val availW = parentGroup.width - parentGroup.paddingLeft - parentGroup.paddingRight -
                (m?.leftMargin ?: 0) - (m?.rightMargin ?: 0)
        val wSpec = if (availW > 0)
            View.MeasureSpec.makeMeasureSpec(availW, View.MeasureSpec.EXACTLY)
        else
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(wSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.measuredHeight
    }

    val startHeight = if (view.visibility == View.VISIBLE) view.height else 0
    if (view.visibility != View.VISIBLE) {
        view.alpha = 0f
        lp.height = 0
        view.visibility = View.VISIBLE
    }
    Anim.openTarget[view] = true

    view.animate().cancel()
    view.animate()
        .alpha(1f)
        .setStartDelay(delay)
        .setDuration((durationMs * 0.85f).toLong())
        .setInterpolator(Anim.emphasized)
        .start()

    val anim = ValueAnimator.ofInt(startHeight, fullHeight).apply {
        duration = durationMs
        startDelay = delay
        interpolator = Anim.emphasized
        addUpdateListener {
            lp.height = it.animatedValue as Int
            view.requestLayout()
        }
        addListener(object : AnimatorListenerAdapter() {
            var cancelled = false
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) {
                if (cancelled) return
                lp.height = original
                view.requestLayout()
                Anim.originalHeight.remove(view)
                Anim.running.remove(view)
                onEnd?.invoke()
            }
        })
    }
    Anim.running[view] = anim
    anim.start()
}

fun View.collapseSmooth(durationMs: Long = 280L) {
    val view = this
    val lp = layoutParams ?: return
    if (view.visibility != View.VISIBLE) return

    Anim.running.remove(view)?.cancel()
    val original = Anim.originalHeight.getOrPut(view) { lp.height }
    Anim.openTarget[view] = false

    view.animate().cancel()
    view.animate()
        .alpha(0f)
        .setStartDelay(0)
        .setDuration((durationMs * 0.7f).toLong())
        .setInterpolator(DecelerateInterpolator())
        .start()

    val anim = ValueAnimator.ofInt(view.height, 0).apply {
        duration = durationMs
        interpolator = Anim.emphasized
        addUpdateListener {
            lp.height = it.animatedValue as Int
            view.requestLayout()
        }
        addListener(object : AnimatorListenerAdapter() {
            var cancelled = false
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) {
                if (cancelled) return
                view.visibility = View.GONE
                view.alpha = 1f
                lp.height = original
                view.requestLayout()
                Anim.originalHeight.remove(view)
                Anim.running.remove(view)
            }
        })
    }
    Anim.running[view] = anim
    anim.start()
}
