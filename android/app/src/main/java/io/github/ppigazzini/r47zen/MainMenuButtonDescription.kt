package io.github.ppigazzini.r47zen

import android.content.Context

// What a screen reader says for the main menu button. While the first-run hint
// shows, the button also carries the hint's welcome, since the hint is a painted
// card no screen reader sees; the text names the menu's place in words, not by
// the colors the card points at.
internal object MainMenuButtonDescription {
    fun of(context: Context, hintShowing: Boolean): String {
        val plain = context.getString(R.string.main_menu_button_content_description)
        if (!hintShowing) {
            return plain
        }
        return "$plain. ${context.getString(R.string.main_menu_button_first_run_hint)}"
    }
}
