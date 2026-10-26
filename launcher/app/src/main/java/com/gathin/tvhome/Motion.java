package com.gathin.tvhome;

import android.view.View;
import android.view.animation.PathInterpolator;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** Reduced motion (config.xml reduced_motion): the one spring and the one time limit every animation uses then. */
final class Motion {

    /** The longest any reduced-motion animation runs, in milliseconds. */
    static final int REDUCED_MS = 90;
    /** Critically damped (no bounce) and stiff enough that any move, even across the screen, settles in 5 frames. */
    static final float REDUCED_STIFFNESS = 15000f;
    /** The spring stops once it is within this many pixels of its target. */
    static final float REDUCED_MIN_CHANGE = 2f;

    private Motion() {
    }

    static void reduce(SpringAnimation spring) {
        spring.setSpring(new SpringForce().setStiffness(REDUCED_STIFFNESS).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        spring.setMinimumVisibleChange(REDUCED_MIN_CHANGE);
    }
    /** Keep velocity when focus changes again before the previous movement has settled. */
    static final class Focus {
        private final SpringAnimation x, y, lift, elevation;
        Focus(View view, boolean bounce) {
            x = spring(view, DynamicAnimation.SCALE_X, bounce);
            y = spring(view, DynamicAnimation.SCALE_Y, bounce);
            lift = spring(view, DynamicAnimation.TRANSLATION_Y, bounce);
            elevation = spring(view, DynamicAnimation.TRANSLATION_Z, false);
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {}
                @Override public void onViewDetachedFromWindow(View v) { cancel(); }
            });
        }
        private static SpringAnimation spring(View view, DynamicAnimation.ViewProperty property, boolean bounce) {
            SpringAnimation animation = new SpringAnimation(view, property);
            animation.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(bounce ? 0.96f : 1f));
            return animation;
        }
        void to(float scale, float translation, float z) {
            x.animateToFinalPosition(scale);
            y.animateToFinalPosition(scale);
            lift.animateToFinalPosition(translation);
            elevation.animateToFinalPosition(z);
        }
        void cancel() { x.cancel(); y.cancel(); lift.cancel(); elevation.cancel(); }
    }

    static void reveal(View panel, boolean animations, boolean reducedMotion) {
        if (!animations) return;
        panel.setAlpha(0f);
        if (!reducedMotion) {
            panel.setPivotX(panel.getWidth());
            panel.setPivotY(0f);
            panel.setScaleX(0.98f);
            panel.setScaleY(0.98f);
            panel.setTranslationY(-4 * panel.getResources().getDisplayMetrics().density);
        }
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(reducedMotion ? REDUCED_MS : 360)
                .setInterpolator(new PathInterpolator(0.2f, 0.8f, 0.2f, 1f)).start();
    }

}
