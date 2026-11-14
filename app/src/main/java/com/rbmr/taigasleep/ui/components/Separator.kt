package com.rbmr.taigasleep.ui.components

/** The separator between parts of one line of text. */
const val PART_SEPARATOR = " · "

/** The present parts joined by [PART_SEPARATOR]; null when none is present, never a stray separator. */
fun joinParts(vararg parts: String?): String? = joinParts(parts.asList())

/** The present parts joined by [PART_SEPARATOR]; null when none is present, never a stray separator. */
fun joinParts(parts: List<String?>): String? =
    parts.filterNotNull().filter { it.isNotBlank() }.joinToString(PART_SEPARATOR).ifEmpty { null }
