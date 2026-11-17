package com.meenai.mina.ui.accounts

import android.content.Context
import com.meenai.mina.browser.BrowserFavorites
import com.meenai.mina.cap.web.WebLogin
import com.meenai.mina.cap.web.WebSites
import com.meenai.mina.cap.web.WebTab

/**
 * Logins tab ke entries ka asli data: har login ka login page, uska site(可注册域名),
 * aur wahi session Cookie jinke milne par hum keh sakte hain ki login ho chuka hai.
 * Sab kuch yahi se chalta hai: tab pe "Login" dabane par ye page Mina ke browser me khulta hai,
 * aur status bhi inhi ke asli saboot (site registry + session Cookie) se aata hai, koi fake nahi.
 */
data class LoginEntry(
    /** Tap karne par Mina ke browser me khulne wala asli login page. */
    val loginUrl: String,
    /** Site ki pehchaan: registrable domain, site registry aur Cookie dono isi se dekhte hain. */
    val siteKey: String,
    /** Ye session Cookie(s) mile to login pakka maano; khali ho to sirf site registry ka saboot chalega. */
    val sessionCookies: List<String>,
    /** Phone-number login wale site (abhi sirf WhatsApp): desktop page pairing code dikhata hai, user apne phone ke WhatsApp me daalta hai. */
    val phoneCodeLogin: Boolean = false,
)

object LoginCatalog {

    val entries: Map<String, LoginEntry> = mapOf(
        "whatsapp" to LoginEntry("https://web.whatsapp.com/", "whatsapp.com", emptyList(), phoneCodeLogin = true),
        "instagram" to LoginEntry("https://www.instagram.com/accounts/login/", "instagram.com", listOf("sessionid", "ds_user_id")),
        "facebook" to LoginEntry("https://www.facebook.com/login/", "facebook.com", listOf("c_user", "xs")),
        "x" to LoginEntry("https://x.com/i/flow/login", "x.com", listOf("auth_token")),
        "youtube" to LoginEntry(
            "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fwww.youtube.com%2F",
            "youtube.com",
            listOf("SID", "HSID", "SSID", "SAPISID", "APISID"),
        ),
        "telegram" to LoginEntry("https://web.telegram.org/k/", "telegram.org", emptyList()),
        "reddit" to LoginEntry("https://www.reddit.com/login/", "reddit.com", listOf("token_v2", "reddit_session")),
        "linkedin" to LoginEntry("https://www.linkedin.com/login/", "linkedin.com", listOf("li_at")),
        "netflix" to LoginEntry("https://www.netflix.com/in/login", "netflix.com", listOf("NetflixId", "SecureNetflixId")),
        "amazon" to LoginEntry("https://www.amazon.in/ap/signin", "amazon.in", listOf("session-id", "at-main", "x-main")),
        "flipkart" to LoginEntry("https://www.flipkart.com/account/login", "flipkart.com", emptyList()),
        "myntra" to LoginEntry("https://www.myntra.com/login", "myntra.com", emptyList()),
        "ajio" to LoginEntry("https://www.ajio.com/login", "ajio.com", emptyList()),
        "swiggy" to LoginEntry("https://www.swiggy.com/", "swiggy.com", emptyList()),
        "zomato" to LoginEntry("https://www.zomato.com/", "zomato.com", emptyList()),
        "paytm" to LoginEntry("https://paytm.com/", "paytm.com", emptyList()),
        "phonepe" to LoginEntry("https://www.phonepe.com/", "phonepe.com", emptyList()),
        "groww" to LoginEntry("https://groww.in/login", "groww.in", emptyList()),
        "zerodha" to LoginEntry("https://kite.zerodha.com/", "zerodha.com", emptyList()),
        "makemytrip" to LoginEntry("https://www.makemytrip.com/", "makemytrip.com", emptyList()),
        "uber" to LoginEntry("https://auth.uber.com/login/", "uber.com", emptyList()),
        "ola" to LoginEntry("https://book.olacabs.com/", "olacabs.com", emptyList()),
    )

    fun entry(id: String): LoginEntry? = entries[id]

    /**
     * Is login ki ASLI status, do real saboot se:
     * 1) Site registry me ye site "login hua" mark hai (LoginProbe ne page se dekha tha, ya tumne confirm kiya tha), ya
     * 2) mina browser profile me is site ke session Cookie maujood hain (sirf inhi naam ke milne par login maano).
     * Dono me se kuch nahi mila to login NAHI hai: status wahi NOT_CONNECTED rahega, hum kabhi jhooth nahi bolte.
     * Main thread se bulao (CookieManager main thread ka hai).
     */
    fun statusOf(context: Context, id: String): AccountStatus {
        val e = entry(id) ?: return AccountStatus.NOT_CONNECTED
        val loggedInInRegistry = WebSites.registry(context.applicationContext).list().any { s ->
            s.loggedInByUser && BrowserFavorites.siteKey(s.host) == e.siteKey
        }
        if (loggedInInRegistry) return AccountStatus.CONNECTED
        if (e.sessionCookies.isNotEmpty()) {
            val cookies = WebTab.cookieManager().getCookie("https://${e.siteKey}/")
                ?: WebTab.cookieManager().getCookie(e.loginUrl)
            val names = WebLogin.cookieNames(cookies)
            if (names.any { n -> e.sessionCookies.any { n.equals(it, ignoreCase = true) } }) {
                return AccountStatus.CONNECTED
            }
        }
        return AccountStatus.NOT_CONNECTED
    }

    /** Login tab pe trash dabane par asli logout: is site ke sab hosts ka Cookie + web storage expire, registry entry hat Jaye. */
    fun logout(context: Context, id: String) {
        val e = entry(id) ?: return
        val app = context.applicationContext
        val registry = WebSites.registry(app)
        val hosts = registry.list().filter { BrowserFavorites.siteKey(it.host) == e.siteKey }.map { it.host }
        val login = WebSites.login(app)
        for (h in hosts) login.logout(h)
        if (hosts.isEmpty()) login.logout(e.siteKey)
    }
}
