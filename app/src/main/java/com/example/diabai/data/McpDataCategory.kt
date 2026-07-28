package com.example.diabai.data

import kotlinx.serialization.Serializable

/** What kind of data an [McpServerConfig] provides -- purely descriptive (routing to the right
 * tool is still done by tool name, same as before), but lets the settings screen and the
 * Übersicht tab's data-source filter show explicitly which server is "the sport tracker" vs.
 * "the glucose/treatments source" instead of an anonymous list of URLs. */
@Serializable
enum class McpDataCategory(val label: String, val shortLabel: String) {
    GLUCOSE_TREATMENTS("Glukose & Behandlungen (Nightscout/Glooko)", "Diabetes"),
    ACTIVITY("Sport / Bewegung (Google Fit / Health Connect)", "Sport"),
    BODY_METRICS("Körperzusammensetzung (Withings/Health Connect)", "Körper"),
}
