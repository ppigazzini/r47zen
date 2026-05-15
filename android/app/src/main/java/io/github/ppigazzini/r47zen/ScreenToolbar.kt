package io.github.ppigazzini.r47zen

import android.view.MenuItem
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors

internal fun MaterialToolbar.configureScreenToolbar(
    titleText: CharSequence,
    actionLabel: CharSequence? = null,
    onNavigateUp: () -> Unit,
    onAction: (() -> Unit)? = null,
) {
    title = titleText
    setTitleTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))

    navigationIcon = AppCompatResources.getDrawable(
        context,
        androidx.appcompat.R.drawable.abc_ic_ab_back_material,
    )?.mutate()
    navigationIcon?.setTint(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
    setNavigationOnClickListener { onNavigateUp() }

    menu.clear()
    if (actionLabel != null && onAction != null) {
        menu.add(actionLabel).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        }
        setOnMenuItemClickListener {
            onAction()
            true
        }
    } else {
        setOnMenuItemClickListener(null)
    }
}
