package com.phone_rtsp

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import android.util.Base64
import android.util.Xml
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import java.util.LinkedHashMap
import java.util.Locale
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.UUID
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

internal class OnvifServer(
    context: Context,
    private val addressProvider: () -> String?,
    private val streams: Map<String, EncodedStream>,
    private val username: String,
    private val password: String,
    private val onFailure: (String) -> Unit
) {
    @Volatile private var running = false
    @Volatile private var httpListener: ServerSocket? = null
    @Volatile private var discoverySocket: MulticastSocket? = null
    private val endpointId = "urn:uuid:${UUID.randomUUID()}"
    private val usedTokens = LinkedHashMap<String, Long>()
    private val httpDigestNonce = newHttpDigestNonce()
    private val highestHttpNonceCounts = HashMap<String, Long>()
    private val multicastLock =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("phone_rtsp_onvif")

    fun start() {
        running = true
        try {
            multicastLock.setReferenceCounted(false)
            multicastLock.acquire()
        } catch (error: RuntimeException) {
            reportFailure("Unable to enable ONVIF multicast discovery: ${error.message}")
        }
        Thread({ serveHttp() }, "onvif-http").start()
        Thread({ serveDiscovery() }, "onvif-discovery").start()
    }

    fun stop() {
        running = false
        try {
            httpListener?.close()
        } catch (_: IOException) {
        }
        httpListener = null
        try {
            discoverySocket?.close()
        } catch (_: IOException) {
        }
        discoverySocket = null
        if (multicastLock.isHeld) multicastLock.release()
    }

    private fun serveHttp() {
        try {
            val listener = ServerSocket(HTTP_PORT)
            httpListener = listener
            while (running) {
                val client = listener.accept()
                Thread({ handleHttpClient(client) }, "onvif-http-client").start()
            }
        } catch (error: Exception) {
            if (running) {
                reportFailure(
                    if (error is java.net.BindException) {
                        "ONVIF HTTP port $HTTP_PORT is already in use."
                    } else {
                        error.message ?: "Unable to start the ONVIF HTTP service."
                    }
                )
            }
        }
    }

    private fun handleHttpClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = CLIENT_TIMEOUT_MS
                val input = BufferedInputStream(client.getInputStream())
                val requestLine = readAsciiLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readAsciiLine(input) ?: return
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        headers[line.substring(0, separator).trim().lowercase()] =
                            line.substring(separator + 1).trim()
                    }
                }
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength < 0 || contentLength > MAX_REQUEST_BYTES) {
                    writeHttpResponse(client, 413, "Request too large", "")
                    return
                }
                val requestBody = ByteArray(contentLength)
                var offset = 0
                while (offset < requestBody.size) {
                    val count = input.read(requestBody, offset, requestBody.size - offset)
                    if (count < 0) return
                    offset += count
                }
                val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?')
                if (path != DEVICE_PATH && path != MEDIA_PATH) {
                    writeHttpResponse(client, 404, "Not Found", "")
                    return
                }
                val requestXml = String(requestBody, Charsets.UTF_8)
                val requestParts = requestLine.split(' ')
                val method = requestParts.firstOrNull()?.uppercase().orEmpty()
                val requestUri = requestParts.getOrNull(1).orEmpty()
                if (!isUnauthenticatedTimeRequest(path, requestXml) &&
                    !isAuthorized(
                        requestXml,
                        headers["authorization"],
                        method,
                        requestUri,
                        headers["host"]
                    )
                ) {
                    writeHttpResponse(
                        client,
                        401,
                        "Unauthorized",
                        authenticationFault(),
                        authenticationChallengeHeaders()
                    )
                    return
                }
                val body = handleSoap(path, requestXml)
                if (body == null) {
                    writeHttpResponse(client, 500, "Unsupported ONVIF operation", soapFault())
                } else {
                    writeHttpResponse(client, 200, "OK", body)
                }
            } catch (error: IOException) {
                Log.d(TAG, "ONVIF client disconnected: ${error.message}")
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to handle an ONVIF request.", error)
            }
        }
    }

    private data class UsernameToken(
        val username: String,
        val password: String,
        val passwordType: String?,
        val nonce: String?,
        val created: String?
    )

    private fun isUnauthenticatedTimeRequest(path: String, requestXml: String): Boolean =
        path == DEVICE_PATH &&
            OPERATION_REGEX.findAll(requestXml)
                .map { it.groupValues[1] }
                .firstOrNull { it in SUPPORTED_OPERATIONS } == "GetSystemDateAndTime"

    private fun isAuthorized(
        requestXml: String,
        authorization: String?,
        method: String,
        requestUri: String,
        requestHost: String?
    ): Boolean {
        if (username.isEmpty() && password.isEmpty()) return true
        if (authorization != null &&
            isHttpAuthorized(authorization, method, requestUri, requestHost)
        ) {
            return true
        }
        val token = try {
            parseUsernameToken(requestXml)
        } catch (_: IOException) {
            null
        } catch (_: XmlPullParserException) {
            null
        } ?: return false
        if (!MessageDigest.isEqual(
                username.toByteArray(Charsets.UTF_8),
                token.username.toByteArray(Charsets.UTF_8)
            )
        ) {
            return false
        }

        val passwordType = token.passwordType?.substringAfterLast('#')
        val now = System.currentTimeMillis()
        val validPassword = when (passwordType) {
            "PasswordDigest" -> {
                val created = token.created ?: return false
                val createdAt = parseCreatedTime(created) ?: return false
                if (createdAt < now - TOKEN_CLOCK_SKEW_MS ||
                    createdAt > now + TOKEN_CLOCK_SKEW_MS
                ) {
                    return false
                }
                val nonce = token.nonce?.takeIf { it.length <= MAX_NONCE_TEXT_LENGTH }
                    ?: return false
                val nonceBytes = try {
                    Base64.decode(nonce, Base64.DEFAULT)
                } catch (_: IllegalArgumentException) {
                    return false
                }
                if (nonceBytes.size !in MIN_NONCE_BYTES..MAX_NONCE_BYTES) return false
                val suppliedDigest = try {
                    Base64.decode(token.password, Base64.DEFAULT)
                } catch (_: IllegalArgumentException) {
                    return false
                }
                val expectedDigest = MessageDigest.getInstance("SHA-1").digest(
                    nonceBytes + created.toByteArray(Charsets.UTF_8) +
                        password.toByteArray(Charsets.UTF_8)
                )
                MessageDigest.isEqual(expectedDigest, suppliedDigest)
            }
            "PasswordText" -> {
                token.created?.let { created ->
                    val createdAt = parseCreatedTime(created) ?: return false
                    if (createdAt < now - TOKEN_CLOCK_SKEW_MS ||
                        createdAt > now + TOKEN_CLOCK_SKEW_MS
                    ) {
                        return false
                    }
                }
                MessageDigest.isEqual(
                    password.toByteArray(Charsets.UTF_8),
                    token.password.toByteArray(Charsets.UTF_8)
                )
            }
            else -> false
        }
        if (!validPassword) return false

        if (passwordType == "PasswordDigest") {
            val created = token.created ?: return false
            val nonce = token.nonce ?: return false
            val replayKey = "$username:$created:$nonce"
            synchronized(usedTokens) {
                val expired = usedTokens.entries.iterator()
                while (expired.hasNext()) {
                    if (now - expired.next().value > TOKEN_CLOCK_SKEW_MS) expired.remove()
                }
                if (usedTokens.containsKey(replayKey)) return false
                if (usedTokens.size >= MAX_CACHED_TOKENS) {
                    usedTokens.remove(usedTokens.keys.first())
                }
                usedTokens[replayKey] = now
            }
        }
        return true
    }

    private fun isHttpAuthorized(
        authorization: String,
        method: String,
        requestUri: String,
        requestHost: String?
    ): Boolean {
        if (authorization.startsWith("Basic ", ignoreCase = true)) {
            val decoded = try {
                String(
                    Base64.decode(authorization.substringAfter(' ').trim(), Base64.DEFAULT),
                    Charsets.UTF_8
                )
            } catch (_: IllegalArgumentException) {
                return false
            }
            val separator = decoded.indexOf(':')
            if (separator < 0) return false
            return MessageDigest.isEqual(
                "$username:$password".toByteArray(Charsets.UTF_8),
                decoded.toByteArray(Charsets.UTF_8)
            )
        }
        if (!authorization.startsWith("Digest ", ignoreCase = true)) return false

        val values = parseDigestAuthorization(authorization.substringAfter(' '))
        val digestUri = values["uri"] ?: return false
        val qop = values["qop"]
        if (values["username"] != username ||
            values["realm"] != HTTP_DIGEST_REALM ||
            values["nonce"] != httpDigestNonce ||
            !digestUriMatches(requestUri, digestUri, requestHost) ||
            !values["algorithm"].orEmpty().equals("MD5", ignoreCase = true) &&
                values.containsKey("algorithm") ||
            (qop != null && !qop.equals("auth", ignoreCase = true))
        ) {
            return false
        }

        val suppliedResponse = values["response"] ?: return false
        val ha1 = md5Hex("$username:$HTTP_DIGEST_REALM:$password")
        val ha2 = md5Hex("$method:$digestUri")
        val nonceCount: String?
        val clientNonce: String?
        val expected = if (qop.equals("auth", ignoreCase = true)) {
            nonceCount = values["nc"] ?: return false
            clientNonce = values["cnonce"] ?: return false
            if (!nonceCount.matches(Regex("[0-9a-fA-F]{8}"))) return false
            md5Hex("$ha1:$httpDigestNonce:$nonceCount:$clientNonce:auth:$ha2")
        } else {
            nonceCount = null
            clientNonce = null
            md5Hex("$ha1:$httpDigestNonce:$ha2")
        }
        if (!MessageDigest.isEqual(
                expected.lowercase().toByteArray(Charsets.US_ASCII),
                suppliedResponse.lowercase().toByteArray(Charsets.US_ASCII)
            )
        ) {
            return false
        }

        if (nonceCount != null && clientNonce != null) {
            val count = nonceCount.toLongOrNull(16) ?: return false
            val key = "$username:$clientNonce"
            synchronized(highestHttpNonceCounts) {
                if (count <= (highestHttpNonceCounts[key] ?: 0L)) return false
                highestHttpNonceCounts[key] = count
            }
        }
        return true
    }

    private fun digestUriMatches(
        requestUri: String,
        digestUri: String,
        requestHost: String?
    ): Boolean {
        if (requestUri == digestUri) return true
        val request = try {
            URI(requestUri)
        } catch (_: Exception) {
            return false
        }
        val digest = try {
            URI(digestUri)
        } catch (_: Exception) {
            return false
        }
        if (request.rawPath != digest.rawPath || request.rawQuery != digest.rawQuery) {
            return false
        }
        if (!digest.isAbsolute) return digest.rawAuthority == null

        val target = if (request.isAbsolute) {
            request
        } else {
            val host = requestHost ?: return false
            try {
                URI("http://$host")
            } catch (_: Exception) {
                return false
            }
        }
        return digest.scheme.equals(target.scheme, ignoreCase = true) &&
            digest.host.equals(target.host, ignoreCase = true) &&
            effectiveHttpPort(digest) == effectiveHttpPort(target)
    }

    private fun effectiveHttpPort(uri: URI): Int =
        if (uri.port == -1) HTTP_DEFAULT_PORT else uri.port

    private fun parseDigestAuthorization(value: String): Map<String, String> {
        val result = HashMap<String, String>()
        val matcher = Regex("""([A-Za-z][A-Za-z0-9_-]*)\s*=\s*(?:"([^"]*)"|([^,\s]+))""")
            .findAll(value)
        matcher.forEach { match ->
            result[match.groupValues[1].lowercase()] =
                match.groupValues[2].ifEmpty { match.groupValues[3] }
        }
        return result
    }

    private fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun newHttpDigestNonce(): String =
        ByteArray(24).apply { SecureRandom().nextBytes(this) }.let {
            Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }

    private fun authenticationChallengeHeaders(): List<Pair<String, String>> = listOf(
        "WWW-Authenticate" to
            "Digest realm=\"$HTTP_DIGEST_REALM\", nonce=\"$httpDigestNonce\", " +
                "qop=\"auth\", algorithm=MD5",
        "WWW-Authenticate" to "Basic realm=\"$HTTP_DIGEST_REALM\", charset=\"UTF-8\""
    )

    @Throws(IOException::class, XmlPullParserException::class)
    private fun parseUsernameToken(xml: String): UsernameToken? {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(StringReader(xml))
        var tokenDepth = -1
        var tokenUsername: String? = null
        var tokenPassword: String? = null
        var passwordType: String? = null
        var nonce: String? = null
        var created: String? = null

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.substringAfter(':')
                    if (name == "UsernameToken") {
                        tokenDepth = parser.depth
                    } else if (tokenDepth >= 0 && parser.depth == tokenDepth + 1) {
                        when (name) {
                            "Username" -> tokenUsername = parser.nextText()
                            "Password" -> {
                                passwordType = parser.getAttributeValue(null, "Type")
                                tokenPassword = parser.nextText()
                            }
                            "Nonce" -> nonce = parser.nextText().trim()
                            "Created" -> created = parser.nextText().trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.substringAfter(':') == "UsernameToken" &&
                        parser.depth == tokenDepth
                    ) {
                        tokenDepth = -1
                    }
                }
            }
        }
        val parsedUsername = tokenUsername ?: return null
        val parsedPassword = tokenPassword ?: return null
        return UsernameToken(
            username = parsedUsername,
            password = parsedPassword,
            passwordType = passwordType,
            nonce = nonce,
            created = created
        )
    }

    private fun parseCreatedTime(created: String): Long? {
        val match = CREATED_TIME_REGEX.matchEntire(created) ?: return null
        val baseTime = match.groupValues[1]
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val position = ParsePosition(0)
        val parsed = formatter.parse(baseTime, position) ?: return null
        if (position.index != baseTime.length) return null
        val fraction = match.groupValues[2]
            .padEnd(3, '0')
            .take(3)
            .toIntOrNull() ?: 0
        val timezone = match.groupValues[3]
        if (timezone.isEmpty() || timezone == "Z") return parsed.time + fraction

        val offsetHours = timezone.substring(1, 3).toIntOrNull() ?: return null
        val offsetMinutes = timezone.substring(4, 6).toIntOrNull() ?: return null
        if (offsetHours > MAX_TIMEZONE_OFFSET_HOURS ||
            offsetMinutes > 59 ||
            (offsetHours == MAX_TIMEZONE_OFFSET_HOURS && offsetMinutes != 0)
        ) {
            return null
        }
        val offsetMillis =
            (offsetHours * 60L + offsetMinutes) * MILLIS_PER_MINUTE *
                (if (timezone[0] == '+') 1 else -1)
        return parsed.time + fraction - offsetMillis
    }

    private fun handleSoap(path: String, request: String): String? {
        val operation = OPERATION_REGEX.findAll(request)
            .map { it.groupValues[1] }
            .firstOrNull { it in SUPPORTED_OPERATIONS }
            ?: return null
        val host = addressProvider() ?: return null
        val deviceUrl = "http://${host}:$HTTP_PORT$DEVICE_PATH"
        val mediaUrl = "http://${host}:$HTTP_PORT$MEDIA_PATH"
        val body = when (operation) {
            "GetDeviceInformation" -> """
                <tds:GetDeviceInformationResponse>
                  <tds:Manufacturer>Phone RTSP</tds:Manufacturer>
                  <tds:Model>Phone Camera</tds:Model>
                  <tds:FirmwareVersion>1.0</tds:FirmwareVersion>
                  <tds:SerialNumber>$endpointId</tds:SerialNumber>
                  <tds:HardwareId>Android</tds:HardwareId>
                </tds:GetDeviceInformationResponse>
            """.trimIndent()
            "GetCapabilities" -> """
                <tds:GetCapabilitiesResponse>
                  <tds:Capabilities>
                    <tt:Device><tt:XAddr>$deviceUrl</tt:XAddr></tt:Device>
                    <tt:Media><tt:XAddr>$mediaUrl</tt:XAddr></tt:Media>
                  </tds:Capabilities>
                </tds:GetCapabilitiesResponse>
            """.trimIndent()
            "GetServices" -> """
                <tds:GetServicesResponse>
                  <tds:Service>
                    <tds:Namespace>http://www.onvif.org/ver10/device/wsdl</tds:Namespace>
                    <tds:XAddr>$deviceUrl</tds:XAddr>
                    <tds:Version><tds:Major>1</tds:Major><tds:Minor>3</tds:Minor></tds:Version>
                  </tds:Service>
                  <tds:Service>
                    <tds:Namespace>http://www.onvif.org/ver10/media/wsdl</tds:Namespace>
                    <tds:XAddr>$mediaUrl</tds:XAddr>
                    <tds:Version><tds:Major>1</tds:Major><tds:Minor>0</tds:Minor></tds:Version>
                  </tds:Service>
                </tds:GetServicesResponse>
            """.trimIndent()
            "GetSystemDateAndTime" -> systemDateAndTime()
            "GetProfiles" -> profiles(path)
            "GetStreamUri" -> streamUri(request)
            else -> null
        } ?: return null
        return soapEnvelope(body, path == MEDIA_PATH)
    }

    private fun profiles(path: String): String? {
        if (path != MEDIA_PATH) return null
        val profileXml = buildString {
            streams["/live"]?.let { append(profile("profile_0", "Main stream", it)) }
            streams["/sub"]?.let { append(profile("profile_1", "Substream", it)) }
        }
        return "<trt:GetProfilesResponse>$profileXml</trt:GetProfilesResponse>"
    }

    private fun profile(token: String, name: String, stream: EncodedStream): String = """
        <trt:Profiles token="$token" fixed="true">
          <tt:Name>$name</tt:Name>
          <tt:VideoSourceConfiguration token="${token}_source">
            <tt:Name>Camera</tt:Name><tt:UseCount>1</tt:UseCount>
            <tt:SourceToken>video_source_0</tt:SourceToken>
            <tt:Bounds x="0" y="0" width="${stream.width}" height="${stream.height}"/>
          </tt:VideoSourceConfiguration>
          <tt:VideoEncoderConfiguration token="${token}_encoder">
            <tt:Name>H.264</tt:Name><tt:UseCount>1</tt:UseCount>
            <tt:Encoding>H264</tt:Encoding>
            <tt:Resolution><tt:Width>${stream.width}</tt:Width><tt:Height>${stream.height}</tt:Height></tt:Resolution>
            <tt:Quality>5</tt:Quality>
            <tt:RateControl><tt:FrameRateLimit>${stream.frameRate}</tt:FrameRateLimit><tt:EncodingInterval>1</tt:EncodingInterval><tt:BitrateLimit>${stream.bitRate / 1_000_000}</tt:BitrateLimit></tt:RateControl>
            <tt:H264><tt:GovLength>${stream.frameRate * 2}</tt:GovLength><tt:H264Profile>Baseline</tt:H264Profile></tt:H264>
          </tt:VideoEncoderConfiguration>
        </trt:Profiles>
    """.trimIndent()

    private fun streamUri(request: String): String? {
        val token = PROFILE_TOKEN_REGEX.find(request)?.groupValues?.getOrNull(1)
        val path = if (token == "profile_1" && streams.containsKey("/sub")) "/sub" else "/live"
        val host = addressProvider() ?: return null
        return """
            <trt:GetStreamUriResponse>
              <trt:MediaUri>
                <tt:Uri>rtsp://${escapeXml(host)}:8554${escapeXml(path)}</tt:Uri>
                <tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>
                <tt:InvalidAfterReboot>false</tt:InvalidAfterReboot>
                <tt:Timeout>PT0S</tt:Timeout>
              </trt:MediaUri>
            </trt:GetStreamUriResponse>
        """.trimIndent()
    }

    private fun systemDateAndTime(): String {
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        return """
            <tds:GetSystemDateAndTimeResponse>
              <tds:SystemDateAndTime>
                <tt:DateTimeType>Manual</tt:DateTimeType>
                <tt:DaylightSavings>false</tt:DaylightSavings>
                <tt:TimeZone><tt:TZ>UTC</tt:TZ></tt:TimeZone>
                <tt:UTCDateTime>
                  <tt:Time>
                    <tt:Hour>${now.get(Calendar.HOUR_OF_DAY)}</tt:Hour>
                    <tt:Minute>${now.get(Calendar.MINUTE)}</tt:Minute>
                    <tt:Second>${now.get(Calendar.SECOND)}</tt:Second>
                  </tt:Time>
                  <tt:Date>
                    <tt:Year>${now.get(Calendar.YEAR)}</tt:Year>
                    <tt:Month>${now.get(Calendar.MONTH) + 1}</tt:Month>
                    <tt:Day>${now.get(Calendar.DAY_OF_MONTH)}</tt:Day>
                  </tt:Date>
                </tt:UTCDateTime>
              </tds:SystemDateAndTime>
            </tds:GetSystemDateAndTimeResponse>
        """.trimIndent()
    }

    private fun soapEnvelope(body: String, isMedia: Boolean): String {
        val serviceNamespace = if (isMedia) MEDIA_NAMESPACE else DEVICE_NAMESPACE
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
              xmlns:tds="$DEVICE_NAMESPACE" xmlns:trt="$MEDIA_NAMESPACE"
              xmlns:tt="http://www.onvif.org/ver10/schema"
              xmlns:tns="$serviceNamespace">
              <s:Body>$body</s:Body>
            </s:Envelope>
        """.trimIndent()
    }

    private fun soapFault(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">
          <s:Body><s:Fault><s:Code><s:Value>s:Receiver</s:Value></s:Code>
          <s:Reason><s:Text xml:lang="en">Unsupported ONVIF operation</s:Text></s:Reason>
          </s:Fault></s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun authenticationFault(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
          xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
          <s:Body><s:Fault>
            <s:Code><s:Value>s:Sender</s:Value><s:Subcode><s:Value>wsse:FailedAuthentication</s:Value></s:Subcode></s:Code>
            <s:Reason><s:Text xml:lang="en">Invalid ONVIF credentials</s:Text></s:Reason>
          </s:Fault></s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun writeHttpResponse(
        socket: Socket,
        status: Int,
        reason: String,
        body: String,
        extraHeaders: List<Pair<String, String>> = emptyList()
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: application/soap+xml; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            extraHeaders.forEach { (name, value) ->
                append("$name: $value\r\n")
            }
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        socket.getOutputStream().apply {
            write(header)
            write(bytes)
            flush()
        }
    }

    private fun serveDiscovery() {
        var socket: MulticastSocket? = null
        try {
            val group = InetAddress.getByName(DISCOVERY_ADDRESS)
            val localAddress = addressProvider()?.let { InetAddress.getByName(it) }
            val networkInterface = localAddress?.let { NetworkInterface.getByInetAddress(it) }
                ?: NetworkInterface.getNetworkInterfaces().toList()
                    .firstOrNull { it.isUp && it.supportsMulticast() }
                ?: throw IOException("No multicast-capable network interface is available.")
            socket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(DISCOVERY_PORT))
                timeToLive = 2
                soTimeout = DISCOVERY_TIMEOUT_MS
                joinGroup(InetSocketAddress(group, DISCOVERY_PORT), networkInterface)
            }
            discoverySocket = socket
            val buffer = ByteArray(MAX_DISCOVERY_BYTES)
            while (running) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val request = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    if (!request.contains("Probe")) continue
                    val messageId = MESSAGE_ID_REGEX.find(request)?.groupValues?.getOrNull(1)
                        ?: continue
                    val response = probeMatch(messageId).toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(response, response.size, packet.address, packet.port))
                } catch (_: SocketTimeoutException) {
                    continue
                }
            }
        } catch (error: Exception) {
            if (running) {
                reportFailure(
                    if (error is SocketException && error.message?.contains("Address already in use") == true) {
                        "ONVIF discovery port $DISCOVERY_PORT is already in use."
                    } else {
                        error.message ?: "Unable to start ONVIF discovery."
                    }
                )
            }
        } finally {
            socket?.close()
            if (discoverySocket === socket) discoverySocket = null
        }
    }

    private fun probeMatch(messageId: String): String {
        val host = addressProvider() ?: return ""
        val xaddr = "http://${host}:$HTTP_PORT$DEVICE_PATH"
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
              xmlns:a="http://www.w3.org/2005/08/addressing"
              xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
              xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
              <s:Header>
                <a:MessageID>urn:uuid:${UUID.randomUUID()}</a:MessageID>
                <a:RelatesTo>${escapeXml(messageId)}</a:RelatesTo>
                <a:To>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:To>
                <a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches</a:Action>
              </s:Header>
              <s:Body><d:ProbeMatches><d:ProbeMatch>
                <a:EndpointReference><a:Address>$endpointId</a:Address></a:EndpointReference>
                <d:Types>dn:NetworkVideoTransmitter</d:Types>
                <d:Scopes>onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/name/Phone_RTSP</d:Scopes>
                <d:XAddrs>$xaddr</d:XAddrs><d:MetadataVersion>1</d:MetadataVersion>
              </d:ProbeMatch></d:ProbeMatches></s:Body>
            </s:Envelope>
        """.trimIndent()
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val line = ByteArrayOutputStream()
        while (line.size() <= MAX_HEADER_LINE_BYTES) {
            val byte = input.read()
            if (byte < 0) return if (line.size() == 0) null else line.toString("US-ASCII")
            if (byte == '\n'.code) return line.toString("US-ASCII").trimEnd('\r')
            line.write(byte)
        }
        throw IOException("ONVIF request header line is too long.")
    }

    private fun reportFailure(message: String) {
        Log.w(TAG, message)
        onFailure(message)
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    companion object {
        private const val TAG = "PhoneRtspOnvif"
        private const val HTTP_PORT = 8000
        private const val HTTP_DEFAULT_PORT = 80
        private const val DISCOVERY_PORT = 3702
        private const val DISCOVERY_ADDRESS = "239.255.255.250"
        private const val DEVICE_NAMESPACE = "http://www.onvif.org/ver10/device/wsdl"
        private const val MEDIA_NAMESPACE = "http://www.onvif.org/ver10/media/wsdl"
        private const val HTTP_DIGEST_REALM = "Phone RTSP ONVIF"
        private const val DEVICE_PATH = "/onvif/device_service"
        private const val MEDIA_PATH = "/onvif/media_service"
        private const val CLIENT_TIMEOUT_MS = 5_000
        private const val DISCOVERY_TIMEOUT_MS = 1_000
        private const val MAX_HEADER_LINE_BYTES = 8_192
        private const val MAX_REQUEST_BYTES = 1_048_576
        private const val MAX_DISCOVERY_BYTES = 65_507
        private const val TOKEN_CLOCK_SKEW_MS = 300_000L
        private const val MAX_CACHED_TOKENS = 512
        private const val MAX_NONCE_TEXT_LENGTH = 1_024
        private const val MIN_NONCE_BYTES = 8
        private const val MAX_NONCE_BYTES = 256
        private const val MAX_TIMEZONE_OFFSET_HOURS = 14
        private const val MILLIS_PER_MINUTE = 60_000L
        private val CREATED_TIME_REGEX = Regex(
            """(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})?"""
        )
        private val OPERATION_REGEX = Regex("<(?:[A-Za-z_][\\w.-]*:)?([A-Za-z_][\\w.-]*)(?=[\\s>/])")
        private val PROFILE_TOKEN_REGEX =
            Regex("<(?:[A-Za-z_][\\w.-]*:)?ProfileToken(?:\\s[^>]*)?>([^<]+)</")
        private val MESSAGE_ID_REGEX =
            Regex("<(?:[A-Za-z_][\\w.-]*:)?MessageID(?:\\s[^>]*)?>([^<]+)</")
        private val SUPPORTED_OPERATIONS = setOf(
            "GetDeviceInformation",
            "GetCapabilities",
            "GetServices",
            "GetSystemDateAndTime",
            "GetProfiles",
            "GetStreamUri"
        )
    }
}
