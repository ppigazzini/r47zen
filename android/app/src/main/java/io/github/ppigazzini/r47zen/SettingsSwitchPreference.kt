package io.github.ppigazzini.r47zen

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.color.MaterialColors

internal class SettingsSwitchPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SwitchPreferenceCompat(context, attrs) {
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val switchWidget = holder.findViewById(androidx.preference.R.id.switchWidget) as? SwitchCompat
            ?: return

        switchWidget.trackTintList = buildTrackTintList(switchWidget.context)
        switchWidget.thumbTintList = buildThumbTintList(switchWidget.context)
    }

    private fun buildTrackTintList(context: Context): ColorStateList {
        val checkedTrack = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorPrimary,
            Color.parseColor("#FFC36F"),
        )
        val uncheckedTrack = ColorUtils.setAlphaComponent(
            MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorOnSurface,
                Color.parseColor("#F7F3EA"),
            ),
            92,
        )

        return ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_enabled),
                intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_checked),
                intArrayOf(),
            ),
            intArrayOf(
                checkedTrack,
                uncheckedTrack,
                ColorUtils.setAlphaComponent(checkedTrack, 110),
                ColorUtils.setAlphaComponent(uncheckedTrack, 110),
            ),
        )
    }

    private fun buildThumbTintList(context: Context): ColorStateList {
        val checkedThumb = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSecondary,
            Color.parseColor("#8EDAFE"),
        )
        val uncheckedThumb = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnSurface,
            Color.parseColor("#F7F3EA"),
        )

        return ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_enabled),
                intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_checked),
                intArrayOf(),
            ),
            intArrayOf(
                checkedThumb,
                uncheckedThumb,
                ColorUtils.setAlphaComponent(checkedThumb, 140),
                ColorUtils.setAlphaComponent(uncheckedThumb, 140),
            ),
        )
    }
}
