package com.hrvballoon

import android.content.Context
import java.util.Locale

/** Stored and drawn numbers use Locale.ROOT, like the pixel HUD; spoken (TalkBack) text follows the device locale. */
internal fun Context.fmt(id: Int, vararg args: Any?) = String.format(Locale.ROOT, getString(id), *args)
