package com.example.diabai.data

import java.time.LocalDate
import java.time.ZoneOffset

/** Free-tier requests per day (item 3) -- deliberately small and hardcoded, not user-configurable:
 * this is a courtesy quota against a shared, app-embedded key, not a paid plan. */
const val ONEPROVIDER_FREE_DAILY_REQUEST_LIMIT = 3

/** UTC (not the device's local date, unlike [todayUsageDateKey]) -- the request explicitly calls
 * for a "Reset um Mitternacht UTC", so every device resets this counter at the same absolute
 * instant regardless of its own timezone, rather than each user getting a slightly different
 * reset time. */
fun todayUsageDateKeyUtc(): String = LocalDate.now(ZoneOffset.UTC).toString()

/** Exact wording from the requirement -- shown the instant the local counter hits
 * [ONEPROVIDER_FREE_DAILY_REQUEST_LIMIT], before any network call is even attempted. */
const val ONEPROVIDER_FREE_QUOTA_EXHAUSTED_MESSAGE =
    "Freies Tageskontingent aufgebraucht – bitte eigenen API-Key eintragen."
