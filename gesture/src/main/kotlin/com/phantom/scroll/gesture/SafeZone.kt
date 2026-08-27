package com.phantom.scroll.gesture

/**
 * The vertical band of the screen a swipe may travel — the single authority for
 * "safe zone" geometry across the whole app:
 *
 *  - the gesture engine draws its trajectories inside it ([GestureEngine]);
 *  - the screen-adapted default `distanceRatio` is computed against its height
 *    (SettingsReducer.reconcileInitial in `:app`);
 *  - the overlay's distance label converts ratio → physical px with it
 *    (ParamSteps in `:app`).
 *
 * Keeping the ratio here (inside the algorithm module) guarantees the three
 * consumers can never drift apart: "displayed px == physically swiped px".
 */
object SafeZone {

    /**
     * Safe-zone height as a fraction of the FULL screen height. The zone is
     * vertically centered: top = (1 - HEIGHT_RATIO) / 2, bottom = (1 + HEIGHT_RATIO) / 2.
     * 0.7 ⇒ the middle 15%..85% band, clearing the status bar and navigation bar.
     */
    const val HEIGHT_RATIO = 0.7f

    /** Top edge (y) of the safe zone for a screen of [screenHeight] px. */
    fun top(screenHeight: Int): Float = screenHeight * ((1f - HEIGHT_RATIO) / 2f)

    /** Bottom edge (y) of the safe zone for a screen of [screenHeight] px. */
    fun bottom(screenHeight: Int): Float = screenHeight * ((1f + HEIGHT_RATIO) / 2f)

    /** Height of the safe zone for a screen of [screenHeight] px. */
    fun height(screenHeight: Int): Float = screenHeight * HEIGHT_RATIO
}
