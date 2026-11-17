package com.meenai.mina.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phone-number login (WhatsApp) ke pairing code ka kaam: site ka desktop page ek 8-character code
 * dikhata hai, user apne phone ke WhatsApp (Settings > Linked devices > Link a device > Log in with
 * phone number) me wo code daalta hai aur device link ho jata hai. Yahan sirf page padha jata hai:
 * code nikalna aur page par login ke asli nishani dekhna; linking wala kadam user ka apna hai.
 */
object PhoneCodeLogin {
    /** Jis site ka desktop page code dikhata hai; code aur login nishani wahi pe dekhte hain. */
    const val HOST = "web.whatsapp.com"

    /** Page padhne wala script, ek hi line me: sirf DOM padhta hai, kuch nahi badalta. */
    const val SCRIPT = "(function(){var t='';var w=false;try{t=document.body?document.body.innerText:'';" +
        "w=!!(document.querySelector('#side')||document.querySelector('div[data-tab]'))}catch(e){};" +
        "return JSON.stringify({text:t,inApp:w});})()"

    /** [SCRIPT] ka jawab: page ka text aur app-UI ki nishani. */
    data class Reading(val code: String?, val inApp: Boolean)

    /** JSON jawab se pairing code aur app-UI nishani nikaalo; padh na paye to null. */
    fun parse(raw: String?): Reading? {
        if (raw.isNullOrEmpty()) return null
        val o = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val text = runCatching { o["text"]!!.jsonPrimitive.content }.getOrNull() ?: return null
        val inApp = runCatching { o["inApp"]!!.jsonPrimitive.content == "true" }.getOrDefault(false)
        // WhatsApp ka code "AB12-CD34" jaisa hota hai; hyphen na mile to 8 aage-piche characters.
        // Dono me kam se kam ek digit hona zaroori, warna page ke kisi aur lafz ko code samajh baithe.
        Regex("\\b[A-Z0-9]{4}-[A-Z0-9]{4}\\b").findAll(text).forEach { m ->
            if (m.value.any { it.isDigit() }) return Reading(m.value, inApp)
        }
        Regex("\\b[A-Z0-9]{8}\\b").findAll(text).forEach { m ->
            val c = m.value
            if (c.any { it.isDigit() } && c.any { it.isLetter() }) return Reading(c, inApp)
        }
        return Reading(null, inApp)
    }
}
