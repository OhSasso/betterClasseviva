package com.example.betterclasseviva

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerialName
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// --- DATA CLASSES ---

@Serializable
data class WhoAmIResponse(
    val id: String = "",
    @SerialName("account_type") val accountType: String = "",
    @SerialName("sede_codice") val sedeCodice: String = "",
    @SerialName("anno_scol") val annoScol: String = "",
    val cognome: String = "",
    val nome: String = "",
    @SerialName("classe_ident") val classeIdent: String = "",
    @SerialName("classe_desc") val classeDesc: String = "",
    @SerialName("data_nascita") val dataNascita: String = "",
    @SerialName("codice_fisc") val codiceFisc: String = "",
    @SerialName("login_type") val loginType: String? = null,
    @SerialName("last_login_at") val lastLoginAt: String? = null,
    val email: String = "",
    val schoolpass: String = ""
)

data class Voto(
    val grade: String,
    val numeric: Double,
    val weight: Double,
    val date: String,
    val description: String = "",
    val type: String = ""
)

data class MedieMateria(val aritmetica: Double, val ponderata: Double, val conteggioVoti: Int)
data class PeriodoScuola(val valutazioni: MutableMap<String, MutableList<Voto>> = mutableMapOf(), val medie: MutableMap<String, MedieMateria> = mutableMapOf())
data class ReportCard(
    val trimestre: PeriodoScuola = PeriodoScuola(),
    val pentamestre: PeriodoScuola = PeriodoScuola(),
    var mediaGeneraleTotale: Double = 0.0,
    var nomeStudente: String = ""
)

// --- SCRAPER CLASS ---

class ClasseVivaScraper {

    private val cookieStore = mutableMapOf<String, Cookie>()

    private val client = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                for (cookie in cookies) {
                    cookieStore[cookie.name] = cookie
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore.values.filter { it.matches(url) }
            }
        })
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val userAgentApp = "CVVS/std/4.1.7 Android/10"
    private val apiKey = "Tg1NWEwNGIgIC0K"
    private val userAgentChrome = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val chromeHeaders = mapOf(
        "User-Agent" to userAgentChrome,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
        "Sec-Ch-Ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1"
    )

    private fun parsingVotoALessicale(votoStr: String, decimalValue: Double): Double? {
        val votoPulito = votoStr.trim()
        if (votoPulito.lowercase().contains("condotta") || votoPulito.isEmpty()) return null

        if (decimalValue > 0.0) return decimalValue

        var modificatore = 0.0
        var voto = votoPulito
        if (voto.contains("½")) { modificatore = 0.5; voto = voto.replace("½", "") }
        else if (voto.contains("+")) { modificatore = 0.25; voto = voto.replace("+", "") }
        else if (voto.contains("-")) { modificatore = -0.25; voto = voto.replace("-", "") }

        val numeroBase = voto.toDoubleOrNull() ?: return null
        return numeroBase + modificatore
    }

    private fun formatName(rawName: String): String {
        if (rawName.isEmpty()) return ""
        return rawName.split(" ").joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
    private fun soloCifre(ident: String) = ident.filter { it.isDigit() }
    suspend fun recuperaValutazioniEMedie(username: String, password: String): ReportCard = withContext(Dispatchers.IO) {
        val reportCard = ReportCard()
        cookieStore.clear()

        var authToken = ""
        val trace = StringBuilder()
        var idStudenteDaLogin: String? = null

        // --- TENTATIVO 1: API UFFICIALE CLASSEVIVA (/rest/v1/auth/login) - --
        try {
            val jsonPayload = JSONObject().apply {
                put("ident", JSONObject.NULL)
                put("uid", username)
                put("pass", password)
                put("pwd", password)
            }.toString()

            val body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())

            val reqApiLogin = Request.Builder()
                .url("https://web.spaggiari.eu/rest/v1/auth/login")
                .post(body)
                .header("User-Agent", userAgentApp)
                .header("Content-Type", "application/json")
                .header("Z-Dev-Apikey", apiKey)
                .build()

            client.newCall(reqApiLogin).execute().use { res ->
                val bodyStr = res.body?.string() ?: ""
                Log.d("CLASSEVIVA_REST", "Rest API Login HTTP ${res.code}: $bodyStr")
                trace.append("REST ${res.code}: ${bodyStr.take(120)} | ")
                if (res.isSuccessful && bodyStr.isNotEmpty() && bodyStr.trim().startsWith("{")) {
                    val jsonObj = JSONObject(bodyStr)
                    val token = jsonObj.optString("token", "")
                    val ident = jsonObj.optString("ident", jsonObj.optString("id", ""))
                    val firstName = jsonObj.optString("firstName", jsonObj.optString("nome", ""))

                    if (firstName.isNotEmpty()) {
                        reportCard.nomeStudente = formatName(firstName)
                    }

                    if (token.isNotEmpty()) {
                        authToken = token
                        if (ident.isNotEmpty()) {
                            idStudenteDaLogin = soloCifre(ident)                        }
                        Log.d("CLASSEVIVA_REST", "Login API ufficiale riuscito! Token: $authToken, Ident: $idStudenteDaLogin")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CLASSEVIVA_REST", "Tentativo API ufficiale fallito", e)
        }

        // --- TENTATIVO 2: WEB LOGIN E AUTHAPI4 ---
        if (authToken.isEmpty()) {
            val reqInitBuilder = Request.Builder().url("https://web.spaggiari.eu/home/app/default/login.php")
            chromeHeaders.forEach { (k, v) -> reqInitBuilder.header(k, v) }
            client.newCall(reqInitBuilder.build()).execute().close()

            val formBodyWeb = FormBody.Builder()
                .add("login", username)
                .add("password", password)
                .add("uid", username)
                .add("pwd", password)
                .add("cid", "")
                .build()

            val reqLoginWebBuilder = Request.Builder()
                .url("https://web.spaggiari.eu/home/app/default/login.php?action=login")
                .post(formBodyWeb)
                .header("Referer", "https://web.spaggiari.eu/home/app/default/login.php")
            chromeHeaders.forEach { (k, v) -> reqLoginWebBuilder.header(k, v) }

            var corpoRisposta = ""
            client.newCall(reqLoginWebBuilder.build()).execute().use { res ->
                corpoRisposta = res.body?.string() ?: ""
                Log.d("CLASSEVIVA_REST", "Web Login HTTP ${res.code}: length ${corpoRisposta.length}")
            }

            val formBodyApi = FormBody.Builder()
                .add("uid", username)
                .add("pwd", password)
                .build()

            val reqLoginApiBuilder = Request.Builder()
                .url("https://web.spaggiari.eu/auth-p7/app/default/AuthApi4.php?a=aLoginPwd")
                .post(formBodyApi)
                .header("Referer", "https://web.spaggiari.eu/auth-p7/app/default/AuthApi4.php")
            chromeHeaders.forEach { (k, v) -> reqLoginApiBuilder.header(k, v) }

            client.newCall(reqLoginApiBuilder.build()).execute().use { res ->
                val resBody = res.body?.string() ?: ""
                Log.d("CLASSEVIVA_REST", "AuthApi4 Login HTTP ${res.code}: $resBody")
                trace.append("AuthApi4 ${res.code}: ${resBody.take(120)} | ")
                if (resBody.isNotEmpty()) {
                    corpoRisposta = resBody
                }
            }

            if (corpoRisposta.contains("errat") || corpoRisposta.contains("fallit") || corpoRisposta.contains("Password non valida")) {
                throw Exception("Credenziali ClasseViva non corrette. Riprova.")
            }

            try {
                val jsonObj = JSONObject(corpoRisposta)
                val dataObj = jsonObj.optJSONObject("data")
                val authObj = dataObj?.optJSONObject("auth")
                if (authObj != null) {
                    authToken = authObj.optString("token", "")

                    val accountInfoObj = authObj.optJSONObject("accountInfo")
                    if (accountInfoObj != null) {
                        val rawId = accountInfoObj.optString("id", "")
                        if (rawId.isNotEmpty()) {
                            idStudenteDaLogin = soloCifre(rawId)
                        }
                        val rawNome = accountInfoObj.optString("nome", "")
                        if (rawNome.isNotEmpty()) {
                            reportCard.nomeStudente = formatName(rawNome)
                        }
                    }

                    if (idStudenteDaLogin.isNullOrEmpty()) {
                        val ident = authObj.optString("identity", "")
                        if (ident.isNotEmpty()) {
                            idStudenteDaLogin = soloCifre(ident)                        }
                    }
                } else if (jsonObj.has("token")) {
                    authToken = jsonObj.optString("token", "")
                }
            } catch (e: Exception) {
                if (e.message?.contains("Credenziali") == true) throw e
            }
        }

        // --- 3. RICHIESTA REST JSON PER ID STUDENTE (whoami) ---
        var idStudente: String? = null

        if (!idStudenteDaLogin.isNullOrEmpty()) {
            idStudente = idStudenteDaLogin
        }

        if (idStudente.isNullOrEmpty()) {
            val reqApiIdBuilder = Request.Builder()
                .url("https://web.spaggiari.eu/rest/w1/misc/whoami")
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", "https://web.spaggiari.eu/")

            chromeHeaders.forEach { (k, v) -> reqApiIdBuilder.header(k, v) }

            if (authToken.isNotEmpty()) {
                reqApiIdBuilder.header("Z-Auth-Token", authToken)
                reqApiIdBuilder.header("Z-Dev-Apikey", apiKey)
            }

            try {
                val jsonStringNome = client.newCall(reqApiIdBuilder.build()).execute().use { res ->
                    val bodyStr = res.body?.string() ?: ""
                    Log.d("CLASSEVIVA_REST", "whoami HTTP ${res.code}: $bodyStr")
                    trace.append("whoami ${res.code}: ${bodyStr.take(120)}")
                    bodyStr
                }

                if (jsonStringNome.isNotEmpty() && jsonStringNome.trim().startsWith("{")) {
                    try {
                        val userProfile = json.decodeFromString<WhoAmIResponse>(jsonStringNome)
                        if (userProfile.id.isNotEmpty()) {
                            idStudente = userProfile.id
                        }
                        if (userProfile.nome.isNotEmpty() && reportCard.nomeStudente.isEmpty()) {
                            reportCard.nomeStudente = formatName(userProfile.nome)
                        }
                    } catch (e: Exception) {
                        Log.e("CLASSEVIVA_REST", "Errore parsing WhoAmIResponse con kotlinx.serialization", e)
                    }

                    if (idStudente.isNullOrEmpty()) {
                        try {
                            val obj = JSONObject(jsonStringNome)
                            val idFound = obj.optString("id", obj.optString("ident", ""))
                            if (idFound.isNotEmpty()) {
                                idStudente = idFound
                            }
                            val nameFound = obj.optString("nome", obj.optString("firstName", ""))
                            if (nameFound.isNotEmpty() && reportCard.nomeStudente.isEmpty()) {
                                reportCard.nomeStudente = formatName(nameFound)
                            }
                        } catch (e: Exception) {
                            Log.e("CLASSEVIVA_REST", "Errore parsing JSONObject whoami", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CLASSEVIVA_REST", "Errore nel recupero dei dati del profilo (whoami)", e)
            }
        }

        Log.d("CLASSEVIVA_REST", "ID Studente Identificato: $idStudente, Nome: ${reportCard.nomeStudente}")

        // Blocco di sicurezza: se non ho l'idStudente non posso procedere a scaricare i voti
        if (idStudente.isNullOrEmpty()) {
            throw Exception("Impossibile recuperare l'ID dello studente.\n$trace")
        }

        // --- 4. SCARICAMENTO DEI VOTI (grades26) ---
        val urlApiVoti = "https://web.spaggiari.eu/rest/v1/students/$idStudente/grades"
        val reqApiBuilder = Request.Builder()
            .url(urlApiVoti)
            .header("User-Agent", userAgentApp)
            .header("Content-Type", "application/json")
            .header("Z-Dev-Apikey", apiKey)
            .header("Z-Auth-Token", authToken)

        if (authToken.isNotEmpty()) {
            reqApiBuilder.header("Z-Auth-Token", authToken)
            reqApiBuilder.header("Z-Dev-Apikey", apiKey)
        }

        val jsonString = client.newCall(reqApiBuilder.build()).execute().use { res ->
            val bodyStr = res.body?.string() ?: ""
            Log.d("CLASSEVIVA_REST", "grades26 HTTP ${res.code}, length: ${bodyStr.length}")
            bodyStr
        }
        val inizio = jsonString.trim().take(1)
        if (inizio != "{" && inizio != "[") {
            throw Exception("Risposta voti non valida: ${jsonString.take(150)}")
        }

        if (jsonString.isNotEmpty()) {
            val jsonArrayVoti = if (jsonString.trim().startsWith("[")) {
                JSONArray(jsonString)
            } else {
                val obj = JSONObject(jsonString)

                if (obj.has("subjects")) {
                    val subjArray = obj.getJSONArray("subjects")
                    for (s in 0 until subjArray.length()) {
                        val subObj = subjArray.getJSONObject(s)
                        val subName = subObj.optString("description", subObj.optString("subjectDesc", "")).lowercase().trim()
                        if (subName.isNotEmpty() && !subName.contains("condotta")) {
                            if (!reportCard.trimestre.valutazioni.containsKey(subName)) {
                                reportCard.trimestre.valutazioni[subName] = mutableListOf()
                            }
                            if (!reportCard.pentamestre.valutazioni.containsKey(subName)) {
                                reportCard.pentamestre.valutazioni[subName] = mutableListOf()
                            }
                        }
                    }
                }

                if (obj.has("grades")) obj.getJSONArray("grades") else JSONArray()
            }

            Log.d("CLASSEVIVA_REST", "Elementi totali intercettati nell'array: ${jsonArrayVoti.length()}")

            for (i in 0 until jsonArrayVoti.length()) {
                val objVoto = jsonArrayVoti.getJSONObject(i)

                val testovoto = objVoto.optString("displayValue", "").trim()
                val nomeMateria = objVoto.optString("subjectDesc", "").lowercase().trim()
                val dataCompleta = objVoto.optString("evtDate", "")
                val dataBreve = if (dataCompleta.length >= 10) dataCompleta.substring(5, 10) else dataCompleta

                val pesoVoto = objVoto.optDouble("weightFactor", 1.0)
                val decimaleReale = objVoto.optDouble("decimalValue", 0.0)

                val descVoto = objVoto.optString("notesForFamily", objVoto.optString("comment", "")).trim()
                val tipoVoto = objVoto.optString("componentDesc", objVoto.optString("displayPos", "")).trim()

                val descPeriodo = objVoto.optString("periodDesc", "").lowercase()
                val periodo = if (descPeriodo.contains("trimestre")) "trimestre" else "pentamestre"

                val votoNumerico = parsingVotoALessicale(testovoto, decimaleReale)

                if (votoNumerico != null && !nomeMateria.contains("condotta") && nomeMateria.isNotEmpty()) {
                    val targetPeriodo = if (periodo == "trimestre") reportCard.trimestre else reportCard.pentamestre

                    if (!targetPeriodo.valutazioni.containsKey(nomeMateria)) {
                        targetPeriodo.valutazioni[nomeMateria] = mutableListOf()
                    }
                    targetPeriodo.valutazioni[nomeMateria]?.add(Voto(testovoto, votoNumerico, pesoVoto, dataBreve, descVoto, tipoVoto))
                }
            }
        }

        // --- 4b. ELENCO COMPLETO DELLE MATERIE (anche quelle senza nessun voto) ---
        // Tentativo "best effort": se l'endpoint non risponde si resta con le materie già trovate.
        for (base in listOf("rest/v1")) {
            try {
                val reqSub = Request.Builder()
                    .url("https://web.spaggiari.eu/$base/students/$idStudente/subjects")
                    .header("User-Agent", userAgentApp)
                    .header("Content-Type", "application/json")
                    .header("Z-Dev-Apikey", apiKey)
                    .header("Z-Auth-Token", authToken)
                if (authToken.isNotEmpty()) {
                    reqSub.header("Z-Auth-Token", authToken)
                    reqSub.header("Z-Dev-Apikey", apiKey)
                }
                val subBody = client.newCall(reqSub.build()).execute().use { res ->
                    if (res.isSuccessful) res.body?.string() ?: "" else ""
                }
                if (subBody.isBlank()) continue
                val subArr = JSONObject(subBody).optJSONArray("subjects") ?: continue
                var aggiunte = 0
                for (k in 0 until subArr.length()) {
                    val o = subArr.getJSONObject(k)
                    val nome = o.optString("description", o.optString("subjectDesc", "")).lowercase().trim()
                    if (nome.isNotEmpty() && !nome.contains("condotta")) {
                        if (!reportCard.trimestre.valutazioni.containsKey(nome)) {
                            reportCard.trimestre.valutazioni[nome] = mutableListOf()
                        }
                        if (!reportCard.pentamestre.valutazioni.containsKey(nome)) {
                            reportCard.pentamestre.valutazioni[nome] = mutableListOf()
                        }
                        aggiunte++
                    }
                }
                Log.d("CLASSEVIVA_REST", "Elenco materie da $base: $aggiunte")
                if (aggiunte > 0) break
            } catch (e: Exception) {
                Log.w("CLASSEVIVA_REST", "Elenco materie non disponibile su $base", e)
            }
        }

        // Sincronizza tutte le materie in entrambi i periodi affinché compaiano anche con 0 voti
        val tutteLeMaterie = (reportCard.trimestre.valutazioni.keys + reportCard.pentamestre.valutazioni.keys).toSet()
        for (materia in tutteLeMaterie) {
            if (!reportCard.trimestre.valutazioni.containsKey(materia)) {
                reportCard.trimestre.valutazioni[materia] = mutableListOf()
            }
            if (!reportCard.pentamestre.valutazioni.containsKey(materia)) {
                reportCard.pentamestre.valutazioni[materia] = mutableListOf()
            }
        }

        // --- 5. CALCOLO MATEMATICO DELLE MEDIE ---
        var sommaMedieGlobali = 0.0
        var numeroMaterieGlobali = 0

        val periodi = listOf("trimestre" to reportCard.trimestre, "pentamestre" to reportCard.pentamestre)
        for ((_, periodoData) in periodi) {
            for (materia in tutteLeMaterie) {
                val listaVoti = periodoData.valutazioni[materia] ?: mutableListOf()
                if (listaVoti.isEmpty()) {
                    periodoData.medie[materia] = MedieMateria(0.0, 0.0, 0)
                    continue
                }

                val sommaSemplice = listaVoti.sumOf { it.numeric }
                val mediaSemplice = sommaSemplice / listaVoti.size

                var sommaProdotti = 0.0
                var sommaPesi = 0.0
                for (v in listaVoti) {
                    sommaProdotti += (v.numeric * v.weight)
                    sommaPesi += v.weight
                }
                val mediaPonderata = if (sommaPesi > 0) sommaProdotti / sommaPesi else mediaSemplice

                val artArrotondata = Math.round(mediaSemplice * 100).toDouble() / 100
                val pondArrotondata = Math.round(mediaPonderata * 100).toDouble() / 100

                periodoData.medie[materia] = MedieMateria(artArrotondata, pondArrotondata, listaVoti.size)

                sommaMedieGlobali += pondArrotondata
                numeroMaterieGlobali++
            }
        }

        reportCard.mediaGeneraleTotale = if (numeroMaterieGlobali > 0) {
            Math.round((sommaMedieGlobali / numeroMaterieGlobali) * 100).toDouble() / 100
        } else {
            0.0
        }

        return@withContext reportCard
    }
}
