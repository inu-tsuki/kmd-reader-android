package com.example.kmd_reader.ui.format

import java.util.concurrent.TimeUnit

/** Shared, clock-injected coarse relative-time formatter for shelf and detail UI. */
fun formatRelativeReadTime(now: Long, past: Long): String {
    val days = TimeUnit.MILLISECONDS.toDays(now - past)
    return when {
        days <= 0L -> "今天"
        days == 1L -> "1 天前"
        days < 30L -> "$days 天前"
        else -> "${days / 30} 个月前"
    }
}
