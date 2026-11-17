package app.voicescribe

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class Pairing(
    val name: String, val id: String, val secret: String,
    val remote: String, val lan: String,
) {
    fun toJson() = JSONObject().put("name", name).put("id", id).put("secret", secret)
        .put("remote", remote).put("lan", lan)

    companion object {
        fun fromJson(o: JSONObject) = Pairing(
            o.getString("name"), o.getString("id"), o.getString("secret"),
            o.optString("remote"), o.optString("lan"),
        )

        /** Parses "vs1:<base64url json>" created by the server's manage.py. */
        fun parse(code: String): Pairing {
            val c = code.trim()
            require(c.startsWith("vs1:")) { "Das ist kein gültiger Kopplungscode (muss mit vs1: beginnen)." }
            val o = try {
                JSONObject(String(Base64.decode(c.removePrefix("vs1:"), Base64.URL_SAFE)))
            } catch (e: Exception) { throw IllegalArgumentException("Kopplungscode ist beschädigt oder unvollständig.") }
            require(o.optInt("v") == 1) { "Unbekannte Version des Kopplungscodes." }
            val secret = o.optString("secret")
            require(Regex("[0-9a-f]{64}").matches(secret)) { "Kopplungscode enthält kein gültiges Geheimnis." }
            val p = Pairing(
                o.optString("name", "Server"), o.optString("id"), secret,
                o.optString("remote").trimEnd('/'), o.optString("lan").trimEnd('/'),
            )
            require(p.id.isNotEmpty()) { "Kopplungscode enthält keine Geräte-ID." }
            require(p.remote.isNotEmpty() || p.lan.isNotEmpty()) { "Kopplungscode enthält keine Serveradresse." }
            for (u in listOf(p.remote, p.lan)) require(u.isEmpty() || ServerClient.urlAllowed(u)) {
                "Adresse nicht erlaubt: $u\nExterne Server müssen https verwenden; http nur für private Netzwerk-Adressen."
            }
            return p
        }
    }
}

/**
 * Talks to the transcription server.
 *
 * Requests are signed with HMAC-SHA256 using the per-device secret (the secret itself is never sent):
 *   v1 \n METHOD \n LOGICAL_PATH \n QUERY \n TIMESTAMP \n NONCE \n SHA256(body)
 * Before audio is sent to a local-network address the server must prove it knows the secret
 * (X-Server-Proof), so another device that happens to use the same private IP cannot receive it.
 */
class ServerClient(context: Context) {
    class Result(val text: String, val route: String, val serverSeconds: Double, val audioSeconds: Double)

    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)
    private val rng = SecureRandom()

    fun pairing(): Pairing? = try {
        prefs.getString("pairing", null)?.let { SecureStore.decrypt(it) }?.let { Pairing.fromJson(JSONObject(it)) }
    } catch (e: Exception) { null }

    fun save(p: Pairing) = prefs.edit().putString("pairing", SecureStore.encrypt(p.toJson().toString())).apply()
    fun clear() = prefs.edit().remove("pairing").apply()

    // ---------------------------------------------------------------- transport

    private class Response(val code: Int, val body: String, val proof: String?, val nonce: String)

    private fun request(
        base: String, method: String, path: String, query: Map<String, String>,
        body: ByteArray?, p: Pairing, connectMs: Int, readMs: Int,
    ): Response {
        val q = query.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        val ts = (System.currentTimeMillis() / 1000).toString()
        val nonce = ByteArray(16).also { rng.nextBytes(it) }.toHex()
        val payload = body ?: ByteArray(0)
        val msg = "v1\n$method\n$path\n$q\n$ts\n$nonce\n${sha256Hex(payload)}"
        val sig = hmacHex(p.secret.hexToBytes(), msg)

        val conn = URL(base + path + if (q.isNotEmpty()) "?$q" else "").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.instanceFollowRedirects = false // never follow a redirect with a signed request
            conn.setRequestProperty("X-Device-Id", p.id)
            conn.setRequestProperty("X-Timestamp", ts)
            conn.setRequestProperty("X-Nonce", nonce)
            conn.setRequestProperty("X-Signature", sig)
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { String(it.readBytes()) } ?: ""
            return Response(code, text, conn.getHeaderField("X-Server-Proof"), nonce)
        } finally { conn.disconnect() }
    }

    /** True only if the server answered AND proved that it knows the device secret. */
    private fun probe(base: String, p: Pairing): Boolean = try {
        val r = request(base, "GET", "/v1/ping", emptyMap(), null, p, 1500, 3000)
        val want = hmacHex(p.secret.hexToBytes(), "proof\n${r.nonce}")
        r.code == 200 && r.proof != null &&
            MessageDigest.isEqual(want.toByteArray(), r.proof.lowercase().toByteArray())
    } catch (e: Exception) { false }

    private fun probeRemote(base: String, p: Pairing): Pair<Boolean, String> = try {
        val r = request(base, "GET", "/v1/ping", emptyMap(), null, p, 5000, 8000)
        when (r.code) {
            200 -> true to "ok"
            401 -> false to "Server lehnt die Anmeldung ab (Gerät widerrufen oder Uhrzeit des Handys falsch?)"
            429 -> false to "Zu viele Fehlversuche – später erneut versuchen."
            else -> false to "Server antwortet mit Fehler ${r.code}."
        }
    } catch (e: Exception) { false to "Server nicht erreichbar (${e.javaClass.simpleName})." }

    /** For the "test connection" button: which route works right now? */
    fun test(): String {
        val p = pairing() ?: return "Nicht gekoppelt."
        if (p.lan.isNotEmpty() && probe(p.lan, p)) return "Verbunden über das lokale Netzwerk."
        if (p.remote.isNotEmpty()) {
            val (ok, msg) = probeRemote(p.remote, p)
            return if (ok) "Verbunden über den Server im Internet." else msg
        }
        return "Lokaler Server nicht erreichbar (Heimnetz?)."
    }

    fun transcribe(audio: ByteArray, language: String, onStatus: (String) -> Unit): Result {
        val p = pairing() ?: error("Nicht mit einem Server gekoppelt.")
        require(audio.size <= 25 * 1024 * 1024) { "Audiodatei ist zu groß (max. 25 MB)." }

        onStatus("Suche Server …")
        val (base, route) = when {
            p.lan.isNotEmpty() && probe(p.lan, p) -> p.lan to "LAN"
            p.remote.isNotEmpty() -> p.remote to "Server"
            else -> error("Lokaler Server nicht erreichbar und keine Internet-Adresse hinterlegt.")
        }
        onStatus(if (route == "LAN") "Sende Audio (lokales Netzwerk) …" else "Sende Audio an den Server …")
        val r = try {
            request(base, "POST", "/v1/transcribe", mapOf("language" to language), audio, p, 8000, 300_000)
        } catch (e: java.net.SocketTimeoutException) {
            error("Server antwortet nicht (Zeitüberschreitung).")
        } catch (e: java.io.IOException) {
            error("Server nicht erreichbar: ${e.message ?: e.javaClass.simpleName}")
        }
        when (r.code) {
            200 -> {}
            401 -> error("Anmeldung abgelehnt. Gerät wurde widerrufen, oder die Uhrzeit des Handys ist falsch.")
            413 -> error("Audio ist zu lang oder zu groß für den Server.")
            429 -> error("Zu viele Fehlversuche. Bitte später erneut versuchen.")
            503 -> error("Server ist ausgelastet. Bitte gleich nochmal versuchen.")
            else -> error("Serverfehler (${r.code}).")
        }
        val j = JSONObject(r.body)
        return Result(j.getString("text").trim(), route, j.optDouble("elapsed", 0.0), j.optDouble("duration", 0.0))
    }

    companion object {
        fun urlAllowed(u: String): Boolean {
            val url = try { URL(u) } catch (e: Exception) { return false }
            if (url.protocol == "https") return true
            return url.protocol == "http" && isPrivateIp(url.host)
        }

        /** RFC1918 + CGNAT (e.g. Tailscale 100.64/10) IPv4 literals. */
        fun isPrivateIp(h: String): Boolean {
            val m = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""").matchEntire(h) ?: return false
            val (a, b) = m.groupValues[1].toInt() to m.groupValues[2].toInt()
            return a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 100 && b in 64..127)
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
        private fun String.hexToBytes() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        private fun sha256Hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()
        private fun hmacHex(key: ByteArray, msg: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(msg.toByteArray()).toHex()
        }
    }
}
