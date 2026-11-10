package com.kp.p2pvid.view

import org.json.JSONObject

/** Control messages sent over the WebRTC data channel during a call. */
sealed interface Control {
    data class Director(val claim: Boolean, val epoch: Long) : Control
    data class View(val mode: ViewMode, val lockTarget: Party) : Control
    data class Speaker(val who: Party) : Control
    data class Recording(val on: Boolean) : Control
    data object Bye : Control

    fun toJson(): String = when (this) {
        is Director -> JSONObject().put("t", "director").put("claim", claim).put("epoch", epoch)
        is View -> JSONObject().put("t", "view").put("mode", mode.name).put("lock", lockTarget.name)
        is Speaker -> JSONObject().put("t", "speaker").put("who", who.name)
        is Recording -> JSONObject().put("t", "rec").put("on", on)
        Bye -> JSONObject().put("t", "bye")
    }.toString()

    companion object {
        fun parse(text: String): Control? = runCatching {
            val o = JSONObject(text)
            when (o.getString("t")) {
                "director" -> Director(o.getBoolean("claim"), o.getLong("epoch"))
                "view" -> View(ViewMode.valueOf(o.getString("mode")), Party.valueOf(o.getString("lock")))
                "speaker" -> Speaker(Party.valueOf(o.getString("who")))
                "rec" -> Recording(o.getBoolean("on"))
                "bye" -> Bye
                else -> null
            }
        }.getOrNull()
    }
}
