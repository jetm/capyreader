package com.capyreader.app.preferences

import com.capyreader.app.R
import com.jocmp.capy.ArticleStatus

enum class StartupStatus {
    LAST_USED,
    ALL,
    UNREAD;

    val translationKey: Int
        get() = when (this) {
            LAST_USED -> R.string.settings_startup_status_last_used
            ALL -> R.string.filter_all
            UNREAD -> R.string.filter_unread
        }

    val articleStatus: ArticleStatus?
        get() = when (this) {
            LAST_USED -> null
            ALL -> ArticleStatus.ALL
            UNREAD -> ArticleStatus.UNREAD
        }

    companion object {
        val default = LAST_USED
    }
}
