package com.cosmotune.podio

import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a station is drawn on the radio map; [approximate] when the directory has no coordinates for it. */
data class PodioPoint(val latitude: Double, val longitude: Double, val approximate: Boolean)

/** The place a station is drawn at: its own coordinates, or else scattered round its country's capital. */
fun RadioStation.mapPoint(): PodioPoint? {
    val lat = latitude
    val lon = longitude
    // The directory stores 0,0 for "not set"; a station in the Gulf of Guinea is not a station.
    if (lat != null && lon != null && !(lat == 0.0 && lon == 0.0)) return PodioPoint(lat, lon, false)
    val code = countryCode.trim().uppercase(Locale.ROOT)
    // Most Indian stations name a city or a state but carry no coordinates: draw them there, not on Delhi.
    val indian = if (code == "IN") PodioPlaces.indiaPlace(state, name, language) else null
    val capital = indian?.let { it.first to it.second } ?: PodioPlaces.capital(code) ?: return null
    // A fixed scatter per station, so the map does not move between visits and a country does not pile up on one dot.
    val spread = indian?.third ?: PodioPlaces.spreadDegrees(code)
    val hash = id.fold(7L) { total, char -> (total * 131 + char.code) and 0x7fffffff }
    val angle = (hash % 3600) / 3600.0 * 2 * PI
    val radius = sqrt(((hash / 3600) % 1000) / 1000.0) * spread
    return PodioPoint((capital.first + radius * sin(angle)).coerceIn(-85.0, 85.0), capital.second + radius * cos(angle) / cos(Math.toRadians(capital.first)).coerceAtLeast(.3), true)
}

/** A group of stations drawn as one dot. */
data class PodioPlace(val latitude: Double, val longitude: Double, val stations: List<RadioStation>, val approximate: Boolean) {
    /** What the dot is called: the directory's region when the stations share one, else their country. */
    val name: String get() {
        val region = stations.map { it.state.trim() }.filter(String::isNotBlank).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val country = stations.firstOrNull { it.country.isNotBlank() }?.country.orEmpty()
        return listOf(region.orEmpty().takeIf { !approximate }.orEmpty(), country).filter(String::isNotBlank).distinct().joinToString(", ").ifBlank { "Unknown place" }
    }
}

object PodioPlaces {
    /** Stations within about 5 km share a dot (a city's stations are one place). */
    fun group(stations: List<RadioStation>): List<PodioPlace> = stations.mapNotNull { station -> station.mapPoint()?.let { station to it } }
        .groupBy { (_, point) -> Triple(Math.round(point.latitude * 20), Math.round(point.longitude * 20), point.approximate) }
        .values.map { entries ->
            PodioPlace(entries.map { it.second.latitude }.average(), entries.map { it.second.longitude }.average(),
                entries.map { it.first }, entries.first().second.approximate)
        }

    /** City or state named by the station's region field, else by its name: (latitude, longitude, scatter in degrees). */
    fun indiaPlace(state: String, name: String, language: String = ""): Triple<Double, Double, Double>? {
        fun find(text: String): Triple<Double, Double, Double>? {
            val key = text.lowercase(Locale.ROOT)
            if (key.isBlank()) return null
            return india.entries.firstOrNull { (place, _) -> Regex("(^|[^a-z])" + Regex.escape(place) + "($|[^a-z])").containsMatchIn(key) }?.value
        }
        // No city and no state: the language says roughly where (a Tamil station without a place is drawn in Tamil Nadu).
        val home = language.lowercase(Locale.ROOT).let { l -> listOf("tamil" to "tamil nadu", "telugu" to "andhra pradesh", "malayalam" to "kerala", "kannada" to "karnataka",
            "marathi" to "maharashtra", "bengali" to "west bengal", "gujarati" to "gujarat", "punjabi" to "punjab").firstOrNull { l.contains(it.first) }?.second }
        return find(state) ?: find(name) ?: home?.let(::find)
    }

    private val zones = mapOf("IN" to "Asia/Kolkata", "LK" to "Asia/Colombo", "PK" to "Asia/Karachi", "BD" to "Asia/Dhaka", "NP" to "Asia/Kathmandu", "AE" to "Asia/Dubai",
        "SA" to "Asia/Riyadh", "SG" to "Asia/Singapore", "MY" to "Asia/Kuala_Lumpur", "TH" to "Asia/Bangkok", "VN" to "Asia/Ho_Chi_Minh", "PH" to "Asia/Manila",
        "JP" to "Asia/Tokyo", "KR" to "Asia/Seoul", "CN" to "Asia/Shanghai", "HK" to "Asia/Hong_Kong", "TW" to "Asia/Taipei", "GB" to "Europe/London", "IE" to "Europe/Dublin",
        "FR" to "Europe/Paris", "DE" to "Europe/Berlin", "ES" to "Europe/Madrid", "IT" to "Europe/Rome", "NL" to "Europe/Amsterdam", "TR" to "Europe/Istanbul",
        "PL" to "Europe/Warsaw", "GR" to "Europe/Athens", "EG" to "Africa/Cairo", "ZA" to "Africa/Johannesburg", "NG" to "Africa/Lagos", "KE" to "Africa/Nairobi",
        "IL" to "Asia/Jerusalem", "IR" to "Asia/Tehran", "AR" to "America/Argentina/Buenos_Aires", "CO" to "America/Bogota", "PE" to "America/Lima", "CL" to "America/Santiago")

    /** The place's clock, "HH:mm": its country's zone when it has one, else the zone its longitude falls in (an hour per 15 degrees). */
    fun localTime(countryCode: String, longitude: Double, now: java.time.Instant = java.time.Instant.now()): String {
        val zone = zones[countryCode.trim().uppercase(Locale.ROOT)]?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() }
            ?: java.time.ZoneOffset.ofHours(Math.round(longitude / 15.0).toInt().coerceIn(-12, 14))
        return java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(now.atZone(zone))
    }

    fun capital(countryCode: String): Pair<Double, Double>? = capitals[countryCode]

    fun spreadDegrees(countryCode: String): Double = when (countryCode) {
        "RU" -> 8.0; "US", "CN", "BR", "CA", "AU" -> 6.0; "IN", "AR", "ID", "MX", "KZ" -> 4.0
        "SA", "DZ", "CD", "LY", "IR", "ZA" -> 3.0; "PK", "TR", "NG", "PE", "EG", "CO" -> 2.5
        "SG", "HK", "MO", "MT", "BH", "QA", "LU", "MC", "SM", "VA", "LI", "AD" -> .1
        else -> .7
    }

    private val capitals: Map<String, Pair<Double, Double>> by lazy {
        TABLE.split(';').mapNotNull { entry ->
            val (code, point) = entry.trim().split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val (lat, lon) = point.split(',').takeIf { it.size == 2 } ?: return@mapNotNull null
            code to (lat.toDouble() to lon.toDouble())
        }.toMap()
    }

    // Cities first (a station in Chennai is in Chennai, not "Tamil Nadu"), then states; the scatter is 0.12 degrees for a city, wider for a state.
    private val india: Map<String, Triple<Double, Double, Double>> by lazy {
        val cities = "chennai:13.08,80.27;coimbatore:11.02,76.96;madurai:9.93,78.12;trichy:10.79,78.69;tiruchirappalli:10.79,78.69;salem:11.66,78.15;" +
            "tirunelveli:8.73,77.70;tiruppur:11.11,77.34;erode:11.34,77.72;vellore:12.92,79.13;thanjavur:10.79,79.14;puducherry:11.94,79.81;" +
            "pondicherry:11.94,79.81;bengaluru:12.97,77.59;bangalore:12.97,77.59;mysuru:12.30,76.64;mysore:12.30,76.64;mangalore:12.91,74.86;" +
            "hyderabad:17.39,78.49;vijayawada:16.51,80.65;visakhapatnam:17.69,83.22;kochi:9.93,76.27;cochin:9.93,76.27;thiruvananthapuram:8.52,76.94;" +
            "trivandrum:8.52,76.94;kozhikode:11.26,75.78;calicut:11.26,75.78;mumbai:19.08,72.88;pune:18.52,73.86;nagpur:21.15,79.09;delhi:28.61,77.21;" +
            "new delhi:28.61,77.21;kolkata:22.57,88.36;ahmedabad:23.02,72.57;surat:21.17,72.83;jaipur:26.91,75.79;lucknow:26.85,80.95;bhopal:23.26,77.41;" +
            "indore:22.72,75.86;patna:25.59,85.14;chandigarh:30.73,76.78;amritsar:31.63,74.87;guwahati:26.14,91.74;bhubaneswar:20.30,85.82;" +
            "arani:12.67,79.28;arni:12.67,79.28;gudiyatham:12.95,78.87;gudiyattam:12.95,78.87;ranipet:12.93,79.33;ambur:12.79,78.72;vaniyambadi:12.68,78.62;" +
            "tiruvannamalai:12.23,79.07;katpadi:12.97,79.14;walajapet:12.93,79.37;tirupattur:12.50,78.57;krishnagiri:12.52,78.21;dharmapuri:12.13,78.16;" +
            "kanchipuram:12.83,79.70;cuddalore:11.75,79.77;karur:10.96,78.08;dindigul:10.37,77.98;nagercoil:8.18,77.43;kanyakumari:8.08,77.54;" +
            "tuticorin:8.76,78.13;thoothukudi:8.76,78.13;sivakasi:9.45,77.80;hosur:12.74,77.83;ooty:11.41,76.70;kumbakonam:10.96,79.38;namakkal:11.22,78.17;" +
            "pollachi:10.66,77.00;theni:10.01,77.48;pudukkottai:10.38,78.82;nagapattinam:10.77,79.84;virudhunagar:9.59,77.96;villupuram:11.94,79.49;" +
            "ranchi:23.34,85.31;raipur:21.25,81.63;dehradun:30.32,78.03;srinagar:34.08,74.80;shimla:31.10,77.17;panaji:15.50,73.83"
        val states = "tamil nadu:11.1,78.65;tamilnadu:11.1,78.65;kerala:10.5,76.3;karnataka:14.5,75.7;andhra pradesh:15.9,79.7;telangana:17.9,79.0;" +
            "maharashtra:19.7,75.7;gujarat:22.3,71.7;rajasthan:26.6,73.8;uttar pradesh:26.8,80.9;madhya pradesh:23.5,78.0;west bengal:23.0,87.9;" +
            "bihar:25.7,85.6;odisha:20.5,84.4;orissa:20.5,84.4;punjab:31.0,75.3;haryana:29.1,76.1;assam:26.4,92.9;jharkhand:23.6,85.3;" +
            "chhattisgarh:21.3,81.8;uttarakhand:30.1,79.3;himachal pradesh:31.9,77.2;jammu and kashmir:33.8,76.6;goa:15.3,74.0"
        buildMap {
            cities.split(';').forEach { entry -> val (n, p) = entry.split(':'); val (a, b) = p.split(','); put(n, Triple(a.toDouble(), b.toDouble(), .12)) }
            states.split(';').forEach { entry -> val (n, p) = entry.split(':'); val (a, b) = p.split(','); put(n, Triple(a.toDouble(), b.toDouble(), 1.4)) }
        }
    }

    private const val TABLE = "AF:34.5,69.2;AL:41.3,19.8;DZ:36.8,3.1;AD:42.5,1.5;AO:-8.8,13.2;AR:-34.6,-58.4;AM:40.2,44.5;AU:-35.3,149.1;" +
        "AT:48.2,16.4;AZ:40.4,49.9;BS:25.0,-77.4;BH:26.2,50.6;BD:23.8,90.4;BB:13.1,-59.6;BY:53.9,27.6;BE:50.8,4.4;BZ:17.3,-88.8;" +
        "BJ:6.5,2.6;BT:27.5,89.6;BO:-16.5,-68.1;BA:43.9,18.4;BW:-24.7,25.9;BR:-15.8,-47.9;BN:4.9,114.9;BG:42.7,23.3;BF:12.4,-1.5;" +
        "BI:-3.4,29.4;KH:11.6,104.9;CM:3.9,11.5;CA:45.4,-75.7;CV:14.9,-23.5;CF:4.4,18.6;TD:12.1,15.0;CL:-33.4,-70.7;CN:39.9,116.4;" +
        "CO:4.7,-74.1;KM:-11.7,43.3;CG:-4.3,15.3;CD:-4.3,15.3;CR:9.9,-84.1;CI:6.8,-5.3;HR:45.8,16.0;CU:23.1,-82.4;CY:35.2,33.4;" +
        "CZ:50.1,14.4;DK:55.7,12.6;DJ:11.6,43.1;DO:18.5,-69.9;EC:-0.2,-78.5;EG:30.0,31.2;SV:13.7,-89.2;GQ:3.8,8.8;ER:15.3,38.9;" +
        "EE:59.4,24.7;SZ:-26.3,31.1;ET:9.0,38.7;FJ:-18.1,178.4;FI:60.2,24.9;FR:48.9,2.4;GA:0.4,9.5;GM:13.5,-16.6;GE:41.7,44.8;" +
        "DE:52.5,13.4;GH:5.6,-0.2;GR:38.0,23.7;GT:14.6,-90.5;GN:9.5,-13.7;GW:11.9,-15.6;GY:6.8,-58.2;HT:18.5,-72.3;HN:14.1,-87.2;" +
        "HK:22.3,114.2;HU:47.5,19.0;IS:64.1,-21.9;IN:28.6,77.2;ID:-6.2,106.8;IR:35.7,51.4;IQ:33.3,44.4;IE:53.3,-6.3;IL:31.8,35.2;" +
        "IT:41.9,12.5;JM:18.0,-76.8;JP:35.7,139.7;JO:31.9,35.9;KZ:51.2,71.4;KE:-1.3,36.8;KW:29.4,48.0;KG:42.9,74.6;LA:18.0,102.6;" +
        "LV:56.9,24.1;LB:33.9,35.5;LS:-29.3,27.5;LR:6.3,-10.8;LY:32.9,13.2;LI:47.2,9.5;LT:54.7,25.3;LU:49.6,6.1;MO:22.2,113.5;" +
        "MG:-18.9,47.5;MW:-14.0,33.8;MY:3.1,101.7;MV:4.2,73.5;ML:12.6,-8.0;MT:35.9,14.5;MR:18.1,-15.9;MU:-20.2,57.5;MX:19.4,-99.1;" +
        "MD:47.0,28.9;MC:43.7,7.4;MN:47.9,106.9;ME:42.4,19.3;MA:34.0,-6.8;MZ:-26.0,32.6;MM:19.8,96.1;NA:-22.6,17.1;NP:27.7,85.3;" +
        "NL:52.4,4.9;NZ:-41.3,174.8;NI:12.1,-86.3;NE:13.5,2.1;NG:9.1,7.5;MK:42.0,21.4;NO:59.9,10.8;OM:23.6,58.5;PK:33.7,73.1;" +
        "PS:31.9,35.2;PA:9.0,-79.5;PG:-9.4,147.2;PY:-25.3,-57.6;PE:-12.0,-77.0;PH:14.6,121.0;PL:52.2,21.0;PT:38.7,-9.1;PR:18.5,-66.1;" +
        "QA:25.3,51.5;RO:44.4,26.1;RU:55.8,37.6;RW:-1.9,30.1;SA:24.7,46.7;SN:14.7,-17.5;RS:44.8,20.5;SC:-4.6,55.5;SL:8.5,-13.2;" +
        "SG:1.35,103.8;SK:48.1,17.1;SI:46.1,14.5;SO:2.0,45.3;ZA:-25.7,28.2;KR:37.6,127.0;SS:4.9,31.6;ES:40.4,-3.7;LK:6.9,79.9;" +
        "SD:15.6,32.5;SR:5.9,-55.2;SE:59.3,18.1;CH:46.9,7.4;SY:33.5,36.3;TW:25.0,121.5;TJ:38.6,68.8;TZ:-6.2,35.7;TH:13.8,100.5;" +
        "TL:-8.6,125.6;TG:6.1,1.2;TT:10.7,-61.5;TN:36.8,10.2;TR:39.9,32.9;TM:37.9,58.4;UG:0.3,32.6;UA:50.5,30.5;AE:24.5,54.4;" +
        "GB:51.5,-0.1;US:38.9,-77.0;UY:-34.9,-56.2;UZ:41.3,69.2;VE:10.5,-66.9;VN:21.0,105.8;YE:15.4,44.2;ZM:-15.4,28.3;ZW:-17.8,31.0;" +
        "KP:39.0,125.8;XK:42.7,21.2;MQ:14.6,-61.1;GP:16.2,-61.5;RE:-21.1,55.5;NC:-22.3,166.5;PF:-17.5,-149.6;GU:13.4,144.8;" +
        "CW:12.1,-68.9;AW:12.5,-70.0;BM:32.3,-64.8;KY:19.3,-81.4;FO:62.0,-6.8;GL:64.2,-51.7;GI:36.1,-5.4;JE:49.2,-2.1;GG:49.5,-2.5;" +
        "IM:54.2,-4.5;SM:43.9,12.4;VA:41.9,12.5"
}
