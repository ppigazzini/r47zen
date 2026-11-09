package io.github.ppigazzini.r47zen

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// A core warning in a dialog: upstream's run to five lines, and a Toast shows two.
internal fun showCalculatorWarning(activityContext: Context, text: String) {
    MaterialAlertDialogBuilder(activityContext)
        .setMessage(text)
        .setPositiveButton(android.R.string.ok, null)
        .show()
}
