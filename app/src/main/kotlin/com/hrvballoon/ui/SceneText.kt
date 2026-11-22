package com.hrvballoon.ui

import com.hrvballoon.R

/** Every text the pixel scenes draw (scene_* and hud_* string resources); [get] is Resources.getString in the app. */
class SceneText(get: (Int) -> String) {
    val hudLine = get(R.string.hud_line)
    val hudLineStatus = get(R.string.hud_line_status)
    val bestSessions = get(R.string.scene_best_sessions)
    val colSession = get(R.string.scene_col_session)
    val colStars = get(R.string.scene_col_stars)
    val noRanked = get(R.string.scene_no_ranked)
    val noSessions = get(R.string.scene_no_sessions)
    val minToRank = get(R.string.scene_min_to_rank)
    val tapRow = get(R.string.scene_tap_row)
    val tapClose = get(R.string.scene_tap_close)
    val sessionDone = get(R.string.scene_session_done)
    val resonance = get(R.string.scene_resonance)
    val noClearPeak = get(R.string.scene_no_clear_peak)
    val noEstimate = get(R.string.scene_no_estimate)
    val bestSoFar = get(R.string.scene_best_so_far)
    val startRate = get(R.string.scene_start_rate)
    val estimateRate = get(R.string.scene_estimate_rate)
    val noProbes = get(R.string.scene_no_probes)
    val tapToUse = get(R.string.scene_tap_to_use)
    val backToSkip = get(R.string.scene_back_to_skip)
    val tapForRanks = get(R.string.scene_tap_for_ranks)
    val stars = get(R.string.scene_stars)
    val notRanked = get(R.string.scene_not_ranked)
    val mostStars = get(R.string.scene_most_stars)
    val firstSession = get(R.string.scene_first_session)
    val rankOf = get(R.string.scene_rank_of)
    val rhythm = get(R.string.scene_rhythm)
    val lifetime = get(R.string.scene_lifetime)
    val axisRate = get(R.string.scene_axis_rate)
}
