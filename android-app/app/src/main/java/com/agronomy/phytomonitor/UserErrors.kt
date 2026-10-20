package com.agronomy.phytomonitor

import android.database.sqlite.SQLiteFullException
import java.io.IOException

/** Turns system failures into a sentence that says what happened, what is kept and what to do. */
object UserErrors {
    const val NO_SPACE = "Недостаточно места в памяти телефона. Действие не выполнено; уже сохранённые осмотры не изменены. Освободите место и повторите."

    /** Below this the camera does not start a capture: a full-size JPEG and its result need several MB. */
    const val CAPTURE_RESERVE_BYTES = 32L * 1024 * 1024

    fun noSpace(error: Throwable): Boolean = generateSequence(error) { it.cause }.any { cause ->
        cause is SQLiteFullException || cause is IOException &&
            cause.message.orEmpty().let { "ENOSPC" in it || "No space left" in it }
    }

    fun describe(error: Throwable, fallback: String): String =
        if (noSpace(error)) NO_SPACE else error.message ?: fallback
}
