package eu.kanade.tachiyomi.ui.base.controller

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnCancel
import androidx.core.animation.doOnEnd
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.changehandler.AnimatorChangeHandler
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlin.math.abs
import kotlin.math.roundToLong

class CrossFadeChangeHandler : AnimatorChangeHandler {
    constructor() : super()
    constructor(removesFromViewOnPush: Boolean) : super(removesFromViewOnPush)
    constructor(duration: Long) : super(duration)
    constructor(duration: Long, removesFromViewOnPush: Boolean) : super(
        duration,
        removesFromViewOnPush,
    )

    override fun getAnimator(
        container: ViewGroup,
        from: View?,
        to: View?,
        isPush: Boolean,
        toAddedToContainer: Boolean,
    ): Animator {
        val animatorSet = AnimatorSet()
        if (to != null) {
            val start = if (toAddedToContainer) 0F else to.alpha
            animatorSet.play(ObjectAnimator.ofFloat(to, View.ALPHA, start, 1f))
        }
        if (from != null) {
            animatorSet.play(ObjectAnimator.ofFloat(from, View.ALPHA, 0f))
        }
        if (isPush) {
            val direction = if ((to ?: from)?.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f
            if (from != null) {
                animatorSet.play(
                    ObjectAnimator.ofFloat(
                        from,
                        View.TRANSLATION_X,
                        -direction * from.width.toFloat() * 0.2f,
                    ),
                )
            }
            if (to != null) {
                animatorSet.play(
                    ObjectAnimator.ofFloat(
                        to,
                        View.TRANSLATION_X,
                        direction * to.width.toFloat() * 0.2f,
                        0f,
                    ),
                )
            }
        } else {
            // Continue in the direction of the predictive-back edge. A right-edge gesture
            // leaves a negative translation; default to the conventional rightward pop when
            // the transition was triggered by a button/system callback without progress.
            val direction = if ((from?.translationX ?: 0f) < 0f) -1f else 1f
            if (from != null) {
                animatorSet.play(
                    ObjectAnimator.ofFloat(
                        from,
                        View.TRANSLATION_X,
                        direction * from.width.toFloat() * 0.2f,
                    ),
                )
            }
            if (to != null) {
                // Allow this to have a nice transition when coming off an aborted push animation or
                // from back gesture
                val fromTranslation = from?.translationX ?: 0f
                animatorSet.play(
                    ObjectAnimator.ofFloat(
                        to,
                        View.TRANSLATION_X,
                        fromTranslation - direction * to.width * 0.2f,
                        0f,
                    ),
                )
            }
        }
        animatorSet.duration = if (isPush) {
            200
        } else {
            try {
                from?.let {
                    val target = (if (it.translationX < 0f) -1f else 1f) * from.width.toFloat() * 0.2f
                    if (target == 0f) {
                        150f
                    } else {
                        (abs(target - it.translationX) / abs(target) * 150f)
                            .coerceIn(1f, 150f)
                    }
                }?.roundToLong()
            } catch (e: IllegalArgumentException) {
                null
            } ?: 150
        }
        animatorSet.doOnCancel { to?.translationX = 0f }
        animatorSet.doOnEnd { to?.translationX = 0f }
        if (!isPush && from != null && from.translationX != 0f &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            animatorSet.interpolator = if (MainActivity.backVelocity != 0f) {
                DecelerateInterpolator(MainActivity.backVelocity)
            } else {
                LinearOutSlowInInterpolator()
            }
        }
        return animatorSet
    }

    override fun resetFromView(from: View) {
        from.translationX = 0f
    }

    override fun copy(): ControllerChangeHandler =
        CrossFadeChangeHandler(animationDuration, removesFromViewOnPush)
}
