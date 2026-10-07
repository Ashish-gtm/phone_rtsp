package com.phone_rtsp

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader

private class UnsupportedOnvifOperationException(message: String) :
    IllegalArgumentException(message)

internal class OnvifServer(
    private val context: Context,
    private val onListening: () -> Unit,
    private val onFailure: (String) -> Unit,
    private val streamProvider: () -> List<EncodedStream>,
    private val addressProvider: () -> String?,
    private val username: String,
    private val password: String
) {
    @Volatile private var running = false
    private var httpListener: ServerSocket? = null
    private var discoverySocket: MulticastSocket? = null
    private var httpThread: Thread? = null
    private var discoveryThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val endpointId = UUID.randomUUID().toString()

    fun start() {
        val address = addressProvider()
            ?: throw IllegalStateException("Connect the phone to Wi-Fi before enabling ONVIF.")
        val interfaceForAddress = NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.firstOrNull { networkInterface ->
                networkInterface.isUp && networkInterface.supportsMulticast() &&
                    networkInterface.inetAddresses.toList().any {
                        it is Inet4Address && it.hostAddress == address
                    }
            }
            ?: throw IllegalStateException(
                "ONVIF discovery needs a multicast-capable Wi-Fi network."
            )

        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("phone_rtsp_onvif").apply {
            setReferenceCounted(false)
            acquire()
        }

        try {
            httpListener = ServerSocket(HTTP_PORT)
            discoverySocket = MulticastSocket(null as java.net.SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(DISCOVERY_PORT))
                networkInterface = interfaceForAddress
                joinGroup(InetSocketAddress(DISCOVERY_GROUP, DISCOVERY_PORT), interfaceForAddress)
            }
            running = true
            httpThread = Thread(::serveHttp, "onvif-http").also { it.start() }
            discoveryThread = Thread(::serveDiscovery, "onvif-discovery").also { it.start() }
            onListening()
        } catch (error: Exception) {
            stop()
            throw when (error) {
                is java.net.BindException -> IllegalStateException(
                    if (httpListener?.isBound == true) {
                        "ONVIF discovery port 3702 is already in use."
                    } else {
                        "ONVIF service port 8080 is already in use."
                    },
                    error
                )
                else -> error
            }
        }
    }

    fun stop() {
        running = false
        try {
            discoverySocket?.close()
        } catch (_: Exception) {
        }
        try {
            httpListener?.close()
        } catch (_: Exception) {
        }
        discoverySocket = null
        httpListener = null
        multicastLock?.let {
            if (it.isHeld) it.release()
        }
        multicastLock = null
    }

    private fun serveHttp() {
        val listener = httpListener ?: return
        while (running) {
            try {
                val socket = listener.accept()
                Thread({ handleHttp(socket) }, "onvif-http-client").start()
            } catch (error: Exception) {
                if (running) onFailure(
                    error.message ?: "The ONVIF HTTP service stopped unexpectedly."
                )
                return
            }
        }
    }

    private fun handleHttp(socket: Socket) {
        socket.use { client ->
            client.soTimeout = HTTP_TIMEOUT_MS
            var operation: String? = null
            var soap12 = false
            try {
                val (headerText, input) = readHeaders(client.getInputStream()) ?: return
                val lines = headerText.replace("\r\n", "\n").split('\n')
                val request = lines.firstOrNull()?.split(' ').orEmpty()
                if (request.size < 2 || request[0].uppercase() != "POST") {
                    respond(client, 405, "Method Not Allowed")
                    return
                }
                val headers = lines.drop(1).mapNotNull { line ->
                    val separator = line.indexOf(':')
                    if (separator <= 0) null
                    else line.substring(0, separator).trim().lowercase() to
                        line.substring(separator + 1).trim()
                }.toMap()
                val length = headers["content-length"]?.toIntOrNull()
                    ?: throw IllegalArgumentException("Missing ONVIF request body length.")
                require(length in 1..MAX_BODY_BYTES) { "ONVIF request body is too large." }
                val body = readBody(input, length).toString(Charsets.UTF_8)
                val path = URI(request[1]).path?.trimEnd('/')?.lowercase().orEmpty()
                if (path != DEVICE_PATH && path != MEDIA_PATH) {
                    respond(client, 404, "Not Found")
                    return
                }
                val document = parseXml(body)
                soap12 = document.documentElement.namespaceURI == SOAP_12_NAMESPACE
                val currentOperation = operationName(document)
                    ?: throw IllegalArgumentException("Missing ONVIF SOAP operation.")
                operation = currentOperation
                Log.i(TAG, "ONVIF operation $currentOperation at $path.")
                if (!authorized(document, currentOperation)) {
                    Log.i(TAG, "ONVIF operation $currentOperation result: HTTP 401.")
                    respond(
                        client,
                        401,
                        "Unauthorized",
                        extraHeaders = "WWW-Authenticate: Basic realm=\"Phone ONVIF\"\r\n"
                    )
                    return
                }
                val responseBody = soapResponse(path, currentOperation, document)
                respond(
                    client,
                    200,
                    "OK",
                    responseBody,
                    contentType = if (soap12) {
                        "application/soap+xml; charset=utf-8"
                    } else {
                        "text/xml; charset=utf-8"
                    }
                )
                Log.i(TAG, "ONVIF operation $currentOperation result: HTTP 200.")
            } catch (error: UnsupportedOnvifOperationException) {
                Log.i(TAG, "ONVIF operation ${operation ?: "(unknown)"} result: HTTP 500.")
                if (!client.isClosed) {
                    try {
                        respond(
                            client,
                            500,
                            "Internal Server Error",
                            soapFault(error.message ?: "Unsupported ONVIF operation.", soap12),
                            contentType = if (soap12) {
                                "application/soap+xml; charset=utf-8"
                            } else {
                                "text/xml; charset=utf-8"
                            }
                        )
                    } catch (writeError: Exception) {
                        Log.i(TAG, "Unable to send ONVIF SOAP fault: ${writeError.message}")
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "ONVIF request failed.", error)
                if (!client.isClosed) {
                    try {
                        operation?.let {
                            Log.i(TAG, "ONVIF operation $it result: HTTP 400.")
                        }
                        respond(client, 400, "Bad Request")
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun readHeaders(input: InputStream): Pair<String, InputStream>? {
        val bytes = ByteArrayOutputStream()
        var matched = 0
        var previousWasLineFeed = false
        val end = byteArrayOf(13, 10, 13, 10)
        while (bytes.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) break
            bytes.write(value)
            matched = if (value == end[matched].toInt()) matched + 1 else 0
            if (matched == end.size) {
                val text = bytes.toByteArray().toString(Charsets.ISO_8859_1)
                return text.dropLast(4) to input
            }
            if (value == 10 && previousWasLineFeed) {
                val text = bytes.toByteArray().toString(Charsets.ISO_8859_1)
                return text.dropLast(2) to input
            }
            previousWasLineFeed = value == 10
        }
        if (bytes.size() == 0) return null
        throw IllegalArgumentException("Invalid ONVIF HTTP headers.")
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count < 0) throw IllegalArgumentException("Incomplete ONVIF request body.")
            offset += count
        }
        return body
    }

    private fun parseXml(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    private fun authorized(document: Document, operation: String): Boolean {
        if (username.isEmpty() && password.isEmpty()) return true
        if (operation in UNAUTHENTICATED_OPERATIONS) return true
        val token = document.getElementsByTagNameNS("*", "UsernameToken").item(0) as? Element
            ?: return false
        val suppliedUsername = token.firstText("Username") ?: return false
        val suppliedPassword = token.firstElement("Password") ?: return false
        val suppliedPasswordText = suppliedPassword.textContent.orEmpty()
        if (suppliedUsername != username) return false
        val passwordType = suppliedPassword.getAttribute("Type")
        val matches = if (passwordType.endsWith("#PasswordText")) {
            MessageDigest.isEqual(
                password.toByteArray(Charsets.UTF_8),
                suppliedPasswordText.toByteArray(Charsets.UTF_8)
            )
        } else {
            val nonceText = token.firstText("Nonce") ?: return false
            val created = token.firstText("Created") ?: return false
            val createdDate = try {
                parseCreatedDate(created)
            } catch (_: Exception) {
                return false
            }
            if (kotlin.math.abs(System.currentTimeMillis() - createdDate.time) > 300_000) {
                return false
            }
            val nonce = try {
                Base64.decode(nonceText, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                return false
            }
            val digest = MessageDigest.getInstance("SHA-1")
                .digest(nonce + created.toByteArray(Charsets.UTF_8) +
                    password.toByteArray(Charsets.UTF_8))
            MessageDigest.isEqual(
                Base64.encode(digest, Base64.NO_WRAP),
                suppliedPasswordText.toByteArray(Charsets.US_ASCII)
            )
        }
        return matches
    }

    private fun operationName(document: Document): String? {
        val body = document.getElementsByTagNameNS("*", "Body").item(0) ?: return null
        var child = body.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) return child.localName ?: child.nodeName
            child = child.nextSibling
        }
        return null
    }

    private fun soapResponse(path: String, operation: String, request: Document): String {
        val address = addressProvider() ?: throw IllegalStateException("No Wi-Fi address is available.")
        val streams = streamProvider()
        val main = streams.firstOrNull { it.path == "/live" }
            ?: throw IllegalStateException("The main RTSP stream is not ready.")
        val profiles = buildList {
            add(Profile("Profile_1", "Main stream", main))
            streams.firstOrNull { it.path == "/sub" }?.let {
                add(Profile("Profile_2", "Sub stream", it))
            }
        }
        val validOperation = when (path) {
            DEVICE_PATH -> operation in setOf(
                "GetDeviceInformation",
                "GetServices",
                "GetCapabilities",
                "GetSystemDateAndTime",
                "GetScopes",
                "GetServiceCapabilities"
            )
            MEDIA_PATH -> operation in setOf(
                "GetProfiles",
                "GetProfile",
                "GetStreamUri",
                "GetVideoSources",
                "GetVideoSourceConfigurations",
                "GetVideoEncoderConfigurations",
                "GetVideoEncoderConfiguration",
                "GetVideoEncoderConfigurationOptions",
                "GetServiceCapabilities"
            )
            else -> false
        }
        if (!validOperation) {
            throw UnsupportedOnvifOperationException(
                "Unsupported ONVIF operation: $operation"
            )
        }
        val token = request.firstText("ProfileToken")
            ?: request.firstText("ConfigurationToken")
        val profile = if (token == null) {
            profiles.first()
        } else {
            profiles.firstOrNull {
                it.token == token || "VideoEncoder_${it.token}" == token
            } ?: throw IllegalArgumentException("Unknown ONVIF profile token.")
        }
        val result = when (operation) {
            "GetDeviceInformation" ->
                "<tds:GetDeviceInformationResponse><tds:Manufacturer>Phone RTSP</tds:Manufacturer>" +
                    "<tds:Model>Android Camera</tds:Model><tds:FirmwareVersion>1.0</tds:FirmwareVersion>" +
                    "<tds:SerialNumber>$endpointId</tds:SerialNumber><tds:HardwareId>Phone</tds:HardwareId>" +
                    "</tds:GetDeviceInformationResponse>"
            "GetServices" ->
                "<tds:GetServicesResponse>" +
                    serviceEntry("device_service", "http://www.onvif.org/ver10/device/wsdl", address) +
                    serviceEntry("media_service", "http://www.onvif.org/ver10/media/wsdl", address) +
                    "</tds:GetServicesResponse>"
            "GetCapabilities" ->
                "<tds:GetCapabilitiesResponse><tds:Capabilities>" +
                    "<tt:Device><tt:XAddr>${xmlEscape(deviceUrl(address))}</tt:XAddr><tt:Network>" +
                    "<tt:IPFilter>false</tt:IPFilter><tt:ZeroConfiguration>false</tt:ZeroConfiguration>" +
                    "<tt:IPVersion6>false</tt:IPVersion6><tt:DynDNS>false</tt:DynDNS>" +
                    "</tt:Network></tt:Device><tt:Media><tt:XAddr>${xmlEscape(mediaUrl(address))}</tt:XAddr>" +
                    "<tt:StreamingCapabilities><tt:RTPMulticast>false</tt:RTPMulticast>" +
                    "<tt:RTP_TCP>true</tt:RTP_TCP><tt:RTP_RTSP_TCP>true</tt:RTP_RTSP_TCP>" +
                    "</tt:StreamingCapabilities></tt:Media></tds:Capabilities>" +
                    "</tds:GetCapabilitiesResponse>"
            "GetProfiles" ->
                "<trt:GetProfilesResponse>${profiles.joinToString("") { profileXml(it) }}" +
                    "</trt:GetProfilesResponse>"
            "GetProfile" ->
                "<trt:GetProfileResponse>${profileXml(profile)
                    .replace("<trt:Profiles", "<trt:Profile")
                    .replace("</trt:Profiles>", "</trt:Profile>")}" +
                    "</trt:GetProfileResponse>"
            "GetStreamUri" -> {
                val uri = streamUrl(address, profile.stream.path)
                "<trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>${xmlEscape(uri)}</tt:Uri>" +
                    "<tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>" +
                    "<tt:InvalidAfterReboot>false</tt:InvalidAfterReboot>" +
                    "<tt:Timeout>PT0S</tt:Timeout></trt:MediaUri></trt:GetStreamUriResponse>"
            }
            "GetVideoSources" ->
                "<trt:GetVideoSourcesResponse><trt:VideoSources token=\"VideoSource_1\">" +
                    "<tt:Framerate>${main.frameRate}</tt:Framerate><tt:Resolution>" +
                    "<tt:Width>${main.width}</tt:Width><tt:Height>${main.height}</tt:Height>" +
                    "</tt:Resolution></trt:VideoSources></trt:GetVideoSourcesResponse>"
            "GetVideoSourceConfigurations" ->
                "<trt:GetVideoSourceConfigurationsResponse><trt:Configurations token=\"VideoSource_1\">" +
                    "<tt:Name>Phone camera</tt:Name><tt:UseCount>${profiles.size}</tt:UseCount>" +
                    "<tt:SourceToken>VideoSource_1</tt:SourceToken><tt:Bounds x=\"0\" y=\"0\" " +
                    "width=\"${main.width}\" height=\"${main.height}\"/></trt:Configurations>" +
                    "</trt:GetVideoSourceConfigurationsResponse>"
            "GetVideoEncoderConfigurations" ->
                "<trt:GetVideoEncoderConfigurationsResponse>${profiles.joinToString("") {
                    encoderConfiguration(it)
                }}</trt:GetVideoEncoderConfigurationsResponse>"
            "GetVideoEncoderConfiguration" ->
                "<trt:GetVideoEncoderConfigurationResponse>${encoderConfiguration(profile)}" +
                    "</trt:GetVideoEncoderConfigurationResponse>"
            "GetVideoEncoderConfigurationOptions" ->
                "<trt:GetVideoEncoderConfigurationOptionsResponse>" +
                    "<trt:Options><tt:QualityRange><tt:Min>1</tt:Min><tt:Max>5</tt:Max>" +
                    "</tt:QualityRange><tt:H264>" +
                    profiles.joinToString("") {
                        "<tt:ResolutionsAvailable><tt:Width>${it.stream.width}</tt:Width>" +
                            "<tt:Height>${it.stream.height}</tt:Height></tt:ResolutionsAvailable>"
                    } +
                    "<tt:GovLengthRange><tt:Min>1</tt:Min><tt:Max>300</tt:Max>" +
                    "</tt:GovLengthRange><tt:FrameRateRange><tt:Min>1</tt:Min>" +
                    "<tt:Max>${profiles.maxOf { it.stream.frameRate }}</tt:Max>" +
                    "</tt:FrameRateRange><tt:EncodingIntervalRange><tt:Min>1</tt:Min>" +
                    "<tt:Max>1</tt:Max></tt:EncodingIntervalRange>" +
                    "</tt:H264></trt:Options></trt:GetVideoEncoderConfigurationOptionsResponse>"
            "GetSystemDateAndTime" -> systemDateAndTime()
            "GetScopes" ->
                "<tds:GetScopesResponse>${listOf(
                    "onvif://www.onvif.org/type/video_encoder",
                    "onvif://www.onvif.org/type/video_system",
                    "onvif://www.onvif.org/name/Phone%20RTSP",
                    "onvif://www.onvif.org/hardware/Android"
                ).joinToString("") { "<tds:Scopes><tds:ScopeDef>Fixed</tds:ScopeDef><tds:Scope>${xmlEscape(it)}</tds:Scope></tds:Scopes>" }}" +
                    "</tds:GetScopesResponse>"
            "GetServiceCapabilities" -> if (path == DEVICE_PATH) {
                "<tds:GetServiceCapabilitiesResponse><tds:Capabilities DiscoveryResolve=\"false\" " +
                    "DiscoveryBye=\"false\" RemoteDiscovery=\"false\" SystemBackup=\"false\" " +
                    "SystemLogging=\"false\" FirmwareUpgrade=\"false\"/></tds:GetServiceCapabilitiesResponse>"
            } else {
                "<trt:GetServiceCapabilitiesResponse><trt:Capabilities SnapshotUri=\"false\" " +
                    "Rotation=\"false\" VideoSourceMode=\"false\" OSD=\"false\" " +
                    "TemporaryOSDText=\"false\" EXICompression=\"false\"/></trt:GetServiceCapabilitiesResponse>"
            }
            else -> throw UnsupportedOnvifOperationException(
                "Unsupported ONVIF operation: $operation"
            )
        }
        return soapEnvelope(
            result,
            request.documentElement.namespaceURI == SOAP_12_NAMESPACE
        )
    }

    private fun profileXml(profile: Profile): String =
        "<trt:Profiles token=\"${profile.token}\" fixed=\"true\"><tt:Name>${profile.name}</tt:Name>" +
            "<tt:VideoSourceConfiguration token=\"VideoSource_1\"><tt:Name>Phone camera</tt:Name>" +
            "<tt:UseCount>1</tt:UseCount><tt:SourceToken>VideoSource_1</tt:SourceToken>" +
            "<tt:Bounds x=\"0\" y=\"0\" width=\"${profile.stream.width}\" " +
            "height=\"${profile.stream.height}\"/></tt:VideoSourceConfiguration>" +
            "<tt:VideoEncoderConfiguration token=\"VideoEncoder_${profile.token}\" " +
            "UseCount=\"1\"><tt:Name>${profile.name}</tt:Name><tt:UseCount>1</tt:UseCount>" +
            "<tt:Encoding>H264</tt:Encoding><tt:Resolution><tt:Width>${profile.stream.width}</tt:Width>" +
            "<tt:Height>${profile.stream.height}</tt:Height></tt:Resolution><tt:Quality>4</tt:Quality>" +
            "<tt:RateControl><tt:FrameRateLimit>${profile.stream.frameRate}</tt:FrameRateLimit>" +
            "<tt:EncodingInterval>1</tt:EncodingInterval><tt:BitrateLimit>" +
            "${profile.stream.bitRate / 1_000_000}</tt:BitrateLimit></tt:RateControl>" +
            "<tt:H264><tt:GovLength>${profile.stream.frameRate * 2}</tt:GovLength>" +
            "<tt:H264Profile>Baseline</tt:H264Profile></tt:H264>" +
            "<tt:SessionTimeout>PT60S</tt:SessionTimeout></tt:VideoEncoderConfiguration>" +
            "</trt:Profiles>"

    private fun encoderConfiguration(profile: Profile): String =
        "<trt:Configurations token=\"VideoEncoder_${profile.token}\" UseCount=\"1\">" +
            "<tt:Name>${profile.name}</tt:Name><tt:UseCount>1</tt:UseCount>" +
            "<tt:Encoding>H264</tt:Encoding><tt:Resolution><tt:Width>${profile.stream.width}</tt:Width>" +
            "<tt:Height>${profile.stream.height}</tt:Height></tt:Resolution><tt:Quality>4</tt:Quality>" +
            "<tt:RateControl><tt:FrameRateLimit>${profile.stream.frameRate}</tt:FrameRateLimit>" +
            "<tt:EncodingInterval>1</tt:EncodingInterval><tt:BitrateLimit>" +
            "${profile.stream.bitRate / 1_000_000}</tt:BitrateLimit></tt:RateControl>" +
            "<tt:H264><tt:GovLength>${profile.stream.frameRate * 2}</tt:GovLength>" +
            "<tt:H264Profile>Baseline</tt:H264Profile></tt:H264>" +
            "<tt:SessionTimeout>PT60S</tt:SessionTimeout></trt:Configurations>"

    private fun systemDateAndTime(): String {
        val local = Calendar.getInstance()
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = local.timeInMillis
        }
        val timezone = TimeZone.getDefault().id
        return "<tds:GetSystemDateAndTimeResponse><tds:SystemDateAndTime>" +
            "<tt:DateTimeType>Manual</tt:DateTimeType><tt:DaylightSavings>false</tt:DaylightSavings>" +
            "<tt:TimeZone><tt:TZ>${xmlEscape(timezone)}</tt:TZ></tt:TimeZone>" +
            "<tt:UTCDateTime><tt:Time><tt:Hour>${utc.get(Calendar.HOUR_OF_DAY)}</tt:Hour>" +
            "<tt:Minute>${utc.get(Calendar.MINUTE)}</tt:Minute>" +
            "<tt:Second>${utc.get(Calendar.SECOND)}</tt:Second></tt:Time>" +
            "<tt:Date><tt:Year>${utc.get(Calendar.YEAR)}</tt:Year>" +
            "<tt:Month>${utc.get(Calendar.MONTH) + 1}</tt:Month>" +
            "<tt:Day>${utc.get(Calendar.DAY_OF_MONTH)}</tt:Day>" +
            "</tt:Date></tt:UTCDateTime></tds:SystemDateAndTime></tds:GetSystemDateAndTimeResponse>"
    }

    private fun parseCreatedDate(value: String): Date {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in patterns) {
            val parser = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            try {
                return parser.parse(value) ?: continue
            } catch (_: java.text.ParseException) {
                continue
            }
        }
        throw IllegalArgumentException("Invalid ONVIF UsernameToken timestamp.")
    }

    private fun serviceEntry(name: String, namespace: String, address: String): String =
        "<tds:Service><tds:Namespace>$namespace</tds:Namespace>" +
            "<tds:XAddr>${xmlEscape("http://$address:$HTTP_PORT/onvif/$name")}</tds:XAddr>" +
            "<tds:Version><tt:Major>2</tt:Major><tt:Minor>0</tt:Minor></tds:Version></tds:Service>"

    private fun streamUrl(address: String, path: String): String {
        val userInfo = if (username.isEmpty()) "" else
            "${urlEncode(username)}:${urlEncode(password)}@"
        return "rtsp://${userInfo}$address:$RTSP_PORT${path}"
    }

    private fun urlEncode(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val char = byte.toInt() and 0xff
            if (char in 'a'.code..'z'.code || char in 'A'.code..'Z'.code ||
                char in '0'.code..'9'.code || char in "-._~".map { it.code }
            ) append(char.toChar())
            else append("%%%02X".format(char))
        }
    }

    private fun deviceUrl(address: String) =
        "http://$address:$HTTP_PORT/onvif/device_service"

    private fun mediaUrl(address: String) =
        "http://$address:$HTTP_PORT/onvif/media_service"

    private fun soapEnvelope(body: String, soap12: Boolean): String {
        val namespace = if (soap12) SOAP_12_NAMESPACE else SOAP_11_NAMESPACE
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<s:Envelope xmlns:s=\"$namespace\" " +
            "xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\" " +
            "xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\" " +
            "xmlns:tt=\"http://www.onvif.org/ver10/schema\"><s:Body>$body</s:Body></s:Envelope>"
    }

    private fun soapFault(message: String, soap12: Boolean): String {
        val fault = if (soap12) {
            "<s:Fault><s:Code><s:Value>s:Sender</s:Value></s:Code>" +
                "<s:Reason><s:Text xml:lang=\"en\">${xmlEscape(message)}</s:Text>" +
                "</s:Reason></s:Fault>"
        } else {
            "<s:Fault><faultcode>s:Client</faultcode>" +
                "<faultstring>${xmlEscape(message)}</faultstring></s:Fault>"
        }
        return soapEnvelope(fault, soap12)
    }

    private fun serveDiscovery() {
        val socket = discoverySocket ?: return
        val buffer = ByteArray(8192)
        while (running) {
            try {
                val request = DatagramPacket(buffer, buffer.size)
                socket.receive(request)
                val probe = String(request.data, request.offset, request.length, Charsets.UTF_8)
                if (probe.contains(":Probe") || probe.contains("<Probe")) {
                    val messageId = Regex(
                        """<[^>]*MessageID[^>]*>([^<]+)</[^>]*MessageID>""",
                        RegexOption.IGNORE_CASE
                    ).find(probe)?.groupValues?.get(1) ?: "urn:uuid:${UUID.randomUUID()}"
                    val address = addressProvider() ?: continue
                    val legacyDialect = probe.contains(
                        "http://schemas.xmlsoap.org/ws/2005/04/discovery"
                    )
                    val response = probeMatches(messageId, address, legacyDialect)
                        .toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(response, response.size, request.address, request.port))
                    Log.i(
                        TAG,
                        "Answered ${if (legacyDialect) "legacy " else ""}" +
                            "WS-Discovery probe from ${request.address.hostAddress} " +
                            "on $address."
                    )
                }
            } catch (error: Exception) {
                if (running) onFailure(
                    error.message ?: "ONVIF device discovery stopped unexpectedly."
                )
                return
            }
        }
    }

    private fun probeMatches(
        messageId: String,
        address: String,
        legacyDialect: Boolean
    ): String {
        val discoveryNamespace = if (legacyDialect) {
            "http://schemas.xmlsoap.org/ws/2005/04/discovery"
        } else {
            "http://docs.oasis-open.org/ws-dd/ns/discovery/2009/01"
        }
        val addressingNamespace = if (legacyDialect) {
            "http://schemas.xmlsoap.org/ws/2004/08/addressing"
        } else {
            "http://www.w3.org/2005/08/addressing"
        }
        val anonymousAddress = if (legacyDialect) {
            "$addressingNamespace/role/anonymous"
        } else {
            "$addressingNamespace/anonymous"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" " +
            "xmlns:a=\"$addressingNamespace\" " +
            "xmlns:d=\"$discoveryNamespace\" " +
            "xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">" +
            "<s:Header><a:MessageID>urn:uuid:${UUID.randomUUID()}</a:MessageID>" +
            "<a:RelatesTo>${xmlEscape(messageId)}</a:RelatesTo>" +
            "<a:To>$anonymousAddress</a:To>" +
            "<a:Action>$discoveryNamespace/ProbeMatches</a:Action>" +
            "</s:Header><s:Body><d:ProbeMatches><d:ProbeMatch>" +
            "<a:EndpointReference><a:Address>urn:uuid:$endpointId</a:Address></a:EndpointReference>" +
            "<d:Types>dn:NetworkVideoTransmitter</d:Types><d:Scopes>" +
            "onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/type/video_system " +
            "onvif://www.onvif.org/name/Phone%20RTSP</d:Scopes>" +
            "<d:XAddrs>${xmlEscape(deviceUrl(address))}</d:XAddrs><d:MetadataVersion>1</d:MetadataVersion>" +
            "</d:ProbeMatch></d:ProbeMatches></s:Body></s:Envelope>"
    }

    private fun respond(
        socket: Socket,
        code: Int,
        reason: String,
        body: String = "",
        extraHeaders: String = "",
        contentType: String = "application/soap+xml; charset=utf-8"
    ) {
        Log.i(TAG, "HTTP response: $code $reason.")
        val bytes = body.toByteArray(Charsets.UTF_8)
        val responseContentType = if (body.isEmpty()) "" else
            "Content-Type: $contentType\r\n"
        val headers = "HTTP/1.1 $code $reason\r\n" +
            "Connection: close\r\n$responseContentType$extraHeaders" +
            "Content-Length: ${bytes.size}\r\n\r\n"
        socket.getOutputStream().apply {
            write(headers.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    private fun Element.firstElement(name: String): Element? {
        val elements = getElementsByTagNameNS("*", name)
        return elements.item(0) as? Element
    }

    private fun Element.firstText(name: String): String? =
        firstElement(name)?.textContent?.trim()

    private fun Document.firstText(name: String): String? {
        val elements = getElementsByTagNameNS("*", name)
        return elements.item(0)?.textContent?.trim()
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private data class Profile(val token: String, val name: String, val stream: EncodedStream)

    companion object {
        private const val HTTP_PORT = 8080
        private const val DISCOVERY_PORT = 3702
        private const val RTSP_PORT = 8554
        private const val HTTP_TIMEOUT_MS = 5_000
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 1024 * 1024
        private const val DEVICE_PATH = "/onvif/device_service"
        private const val MEDIA_PATH = "/onvif/media_service"
        private const val SOAP_11_NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/"
        private const val SOAP_12_NAMESPACE = "http://www.w3.org/2003/05/soap-envelope"
        private val UNAUTHENTICATED_OPERATIONS = setOf(
            "GetSystemDateAndTime",
            "GetCapabilities",
            "GetServices",
            "GetServiceCapabilities",
            "GetScopes"
        )
        private const val TAG = "PhoneOnvifServer"
        private val DISCOVERY_GROUP = InetAddress.getByName("239.255.255.250")
    }
}
