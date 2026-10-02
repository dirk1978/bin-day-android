package uk.dirk1978.binday.data

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.dirk1978.binday.core.Address
import uk.dirk1978.binday.core.Council
import uk.dirk1978.binday.core.GardenStreet
import uk.dirk1978.binday.core.Schedule

/** A failure worth showing to the person, already worded for them. */
class CouncilException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The two council websites. Plain HttpURLConnection: three GET/POSTs don't need a client library. */
object CouncilApi {
  private const val TAG = "BinDay"
  private const val USER_AGENT = "BinDay-Android/1.0 (personal bin collection reminder)"
  private const val TIMEOUT_MS = 30_000

  suspend fun findAddresses(postcode: String): List<Address> {
    val pc = Council.normalisePostcode(postcode)
    if (!Council.isValidPostcode(pc)) throw CouncilException("\"$postcode\" doesn't look like a UK postcode.")
    val html = request("${Council.BASE_URL}/", form = "PostCode=${URLEncoder.encode(pc, "UTF-8")}")
    return Council.parseAddresses(html).ifEmpty {
      throw CouncilException("No addresses found for $pc. Bin Day only works for Bromsgrove District postcodes.")
    }
  }

  suspend fun fetchSchedule(uprn: String): Schedule {
    val html = request("${Council.BASE_URL}/BinCollections/Details?uprn=${URLEncoder.encode(uprn, "UTF-8")}")
    return Council.parseSchedule(html).ifEmpty {
      throw CouncilException("No collection dates found. The council's page may have changed.")
    }
  }

  suspend fun fetchGardenStreets(): List<GardenStreet> =
    Council.parseGardenStreets(request(Council.GARDEN_STREETS_URL)).ifEmpty {
      throw CouncilException("Couldn't read the council's garden waste street list.")
    }

  private suspend fun request(url: String, form: String? = null): String = withContext(Dispatchers.IO) {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = TIMEOUT_MS
      connection.readTimeout = TIMEOUT_MS
      connection.setRequestProperty("User-Agent", USER_AGENT)
      if (form != null) {
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.outputStream.use { it.write(form.toByteArray()) }
      }
      val status = connection.responseCode
      if (status !in 200..299) throw CouncilException("The council's site returned $status.")
      connection.inputStream.bufferedReader().use { it.readText() }
    } catch (e: IOException) {
      Log.w(TAG, "Request to $url failed", e)
      throw CouncilException("Couldn't reach the council's site. Check your connection.", e)
    } finally {
      connection.disconnect()
    }
  }
}
