package com.tuxundown.android.core

/**
 * Collision-free file naming (audit round 28) — pure,
 * unit-tested.
 *
 * Picks the first name [taken] does not claim: the name itself,
 * then "name (1).ext", "name (2).ext", … The numbered series is
 * BOUNDED ([MAX_NUMBERED]): the pre-fix loop in PublicStorage
 * spun without a cap, one storage query per candidate, so a
 * folder stuffed with numbered copies made finalize grind
 * through binder calls for as long as the pile was deep.
 *
 * Past the cap the fallback is a timestamp suffix
 * ("name-<millis>.ext"), which cannot collide with the numbered
 * series; if even the timestamped name and its own numbered
 * variants are all taken, this throws rather than returning a
 * name that would overwrite somebody's file. Overwriting or
 * deleting an existing file to make room is NEVER an option
 * here (audit A1's red line).
 */
object FreeName {

    const val MAX_NUMBERED = 999

    fun pick(name: String, nowMs: Long, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (n in 1..MAX_NUMBERED) {
            val candidate = "$stem ($n)$ext"
            if (!taken(candidate)) return candidate
        }
        val stamped = "$stem-$nowMs"
        if (!taken(stamped + ext)) return stamped + ext
        for (n in 1..MAX_NUMBERED) {
            val candidate = "$stamped ($n)$ext"
            if (!taken(candidate)) return candidate
        }
        throw IllegalStateException("同名文件太多了，起不出不重名的新名字")
    }
}
