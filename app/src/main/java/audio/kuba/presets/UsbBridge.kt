package audio.kuba.presets

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BridgeError(val errName: String, message: String) : Exception(message)

private class HidState(val device: UsbDevice, val iface: UsbInterface, val info: HidInfo) {
    val epIn: UsbEndpoint?
    val epOut: UsbEndpoint?
    @Volatile var running = false
    var reader: Thread? = null

    init {
        var i: UsbEndpoint? = null
        var o: UsbEndpoint? = null
        for (k in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(k)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) { if (i == null) i = ep } else { if (o == null) o = ep }
        }
        epIn = i
        epOut = o
    }
}

private class UsbState(val device: UsbDevice, var cfg: UsbConfiguration?) {
    val claimed = HashMap<Int, UsbInterface>()
}

/**
 * Ponte entre o JavaScript do site (polyfill de navigator.hid / navigator.usb)
 * e a API USB Host do Android. Não interpreta nem altera nenhum dado: apenas
 * repassa os bytes que o site envia/recebe.
 */
class UsbBridge(private val activity: Activity, private val webView: WebView) {

    @Volatile var trusted = false

    private val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    private val io = Executors.newCachedThreadPool()
    private val conns = HashMap<String, UsbDeviceConnection>()
    private val users = HashMap<String, MutableSet<String>>()
    private val waiters = ConcurrentHashMap<String, CopyOnWriteArrayList<(Boolean) -> Unit>>()
    private val infoCache = ConcurrentHashMap<String, HidInfo>()
    private val hid = ConcurrentHashMap<String, HidState>()
    private val usbs = ConcurrentHashMap<String, UsbState>()

    companion object {
        private const val TAG = "KubaBridge"
        private const val ACTION_PERM = "audio.kuba.presets.USB_PERMISSION"
    }

    // ---------------------------------------------------------------- ciclo de vida

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, i: Intent) {
            val dev = IntentCompat.getParcelableExtra(i, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
            when (i.action) {
                ACTION_PERM -> waiters.remove(dev.deviceName)
                    ?.forEach { it(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> io.execute { onDetached(dev) }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> io.execute { onAttached(dev) }
            }
        }
    }

    fun register() {
        val f = IntentFilter().apply {
            addAction(ACTION_PERM)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(activity, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun unregister() {
        try { activity.unregisterReceiver(receiver) } catch (_: Exception) {}
        hid.keys.toList().forEach { closeHid(it) }
        usbs.clear()
        synchronized(conns) { conns.values.forEach { it.close() }; conns.clear(); users.clear() }
        io.shutdownNow()
    }

    private fun onAttached(d: UsbDevice) {
        if (!usb.hasPermission(d)) return
        val h = hidDevices(d)
        if (h.length() > 0) emit("hid", "connect", JSONObject().put("devices", h))
        emit("usb", "connect", JSONObject().put("devices", JSONArray().put(usbJson(d))))
    }

    private fun onDetached(d: UsbDevice) {
        hid.keys.filter { it.startsWith(d.deviceName + "|") }.forEach { closeHid(it) }
        usbs.remove(d.deviceName)
        synchronized(conns) { users.remove(d.deviceName); conns.remove(d.deviceName)?.close() }
        infoCache.keys.removeAll { it.startsWith(d.deviceName + "|") }
        val p = JSONObject().put("name", d.deviceName)
        emit("hid", "disconnect", p)
        emit("usb", "disconnect", p)
    }

    // ---------------------------------------------------------------- JS <-> nativo

    @JavascriptInterface
    fun call(id: Int, method: String, args: String) {
        if (!trusted) { resolve(id, false, err("SecurityError", "Origem não confiável")); return }
        io.execute {
            try {
                Log.d(TAG, "-> $method")
                resolve(id, true, dispatch(method, JSONObject(args)))
            } catch (e: BridgeError) {
                Log.w(TAG, "$method: ${e.errName}: ${e.message}")
                resolve(id, false, err(e.errName, e.message ?: ""))
            } catch (e: Exception) {
                Log.e(TAG, "$method falhou", e)
                resolve(id, false, err("UnknownError", e.toString()))
            }
        }
    }

    private fun err(name: String, msg: String) = JSONObject().put("name", name).put("message", msg)

    private fun resolve(id: Int, ok: Boolean, p: JSONObject) {
        val js = "window.__kubaResolve($id,$ok,$p)"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    private fun emit(kind: String, type: String, p: JSONObject) {
        val js = "window.__kubaEvent&&window.__kubaEvent(${JSONObject.quote(kind)},${JSONObject.quote(type)},$p)"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private fun dispatch(m: String, a: JSONObject): JSONObject = when (m) {
        "list" -> list(a.getString("api"))
        "request" -> request(a)
        "hidOpen" -> hidOpen(a.getString("id"))
        "hidClose" -> { closeHid(a.getString("id")); JSONObject() }
        "hidSend" -> hidSend(a, false)
        "hidSendFeature" -> hidSend(a, true)
        "hidGetFeature" -> hidGetFeature(a)
        "usbOpen" -> usbOpen(a.getString("id"))
        "usbClose" -> { usbClose(a.getString("id")); JSONObject() }
        "usbSelectConfig" -> usbSelectConfig(a)
        "usbClaim" -> usbClaim(a)
        "usbRelease" -> usbRelease(a)
        "usbAlt" -> usbAlt(a)
        "usbCtlIn" -> usbCtlIn(a)
        "usbCtlOut" -> usbCtlOut(a)
        "usbIn" -> usbIn(a)
        "usbOut" -> usbOut(a)
        // UsbDeviceConnection.resetDevice() é API oculta: reset() do WebUSB não é suportado no Android.
        "usbReset" -> throw BridgeError("NotSupportedError", "USBDevice.reset() não é suportado no Android.")
        "usbClearHalt" -> JSONObject()
        else -> throw BridgeError("NotSupportedError", "Método desconhecido: $m")
    }

    // ---------------------------------------------------------------- conexões e permissão

    private fun acquire(d: UsbDevice, user: String): UsbDeviceConnection = synchronized(conns) {
        val c = conns[d.deviceName]
            ?: (usb.openDevice(d) ?: throw BridgeError("NetworkError", "Não foi possível abrir o dispositivo USB."))
                .also { conns[d.deviceName] = it }
        users.getOrPut(d.deviceName) { mutableSetOf() }.add(user)
        c
    }

    private fun release(d: UsbDevice, user: String) {
        synchronized(conns) {
            val s = users[d.deviceName]
            s?.remove(user)
            if (s == null || s.isEmpty()) {
                users.remove(d.deviceName)
                conns.remove(d.deviceName)?.close()
            }
        }
    }

    private fun connOf(d: UsbDevice): UsbDeviceConnection? = synchronized(conns) { conns[d.deviceName] }

    private fun conn(d: UsbDevice) =
        connOf(d) ?: throw BridgeError("InvalidStateError", "The device must be opened first.")

    private fun awaitPermission(d: UsbDevice): Boolean {
        if (usb.hasPermission(d)) return true
        val latch = CountDownLatch(1)
        var granted = false
        waiters.getOrPut(d.deviceName) { CopyOnWriteArrayList() }.add { granted = it; latch.countDown() }
        val pi = PendingIntent.getBroadcast(
            activity, 0,
            Intent(ACTION_PERM).setPackage(activity.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        usb.requestPermission(d, pi)
        latch.await(90, TimeUnit.SECONDS)
        return granted && usb.hasPermission(d)
    }

    // ---------------------------------------------------------------- listagem / seleção

    private fun hidIfaces(d: UsbDevice): List<UsbInterface> =
        (0 until d.interfaceCount).map { d.getInterface(it) }
            .filter { it.interfaceClass == UsbConstants.USB_CLASS_HID && it.alternateSetting == 0 }

    private fun hidId(d: UsbDevice, itf: UsbInterface) = "${d.deviceName}|${itf.id}"

    private fun info(d: UsbDevice, itf: UsbInterface): HidInfo =
        infoCache.getOrPut(hidId(d, itf)) { readDescriptor(d, itf) }

    private fun readDescriptor(d: UsbDevice, itf: UsbInterface): HidInfo {
        val c = acquire(d, "describe")
        try {
            val owned = hid.containsKey(hidId(d, itf))
            if (!owned) c.claimInterface(itf, true)
            val buf = ByteArray(4096)
            var n = c.controlTransfer(0x81, 0x06, 0x2200, itf.id, buf, buf.size, 1000)
            if (n <= 0) n = c.controlTransfer(0x81, 0x06, 0x2200, itf.id, buf, 512, 1000)
            if (!owned) c.releaseInterface(itf)
            return if (n > 0) HidInfo.parse(buf.copyOf(n)) else HidInfo.empty()
        } finally {
            release(d, "describe")
        }
    }

    private fun hidJson(d: UsbDevice, itf: UsbInterface): JSONObject = JSONObject()
        .put("id", hidId(d, itf))
        .put("vendorId", d.vendorId)
        .put("productId", d.productId)
        .put("productName", d.productName ?: "USB HID Device")
        .put("collections", info(d, itf).collectionsJson())

    private fun hidDevices(d: UsbDevice): JSONArray {
        val arr = JSONArray()
        hidIfaces(d).forEach { arr.put(hidJson(d, it)) }
        return arr
    }

    private fun list(api: String): JSONObject {
        val arr = JSONArray()
        for (d in usb.deviceList.values) {
            if (!usb.hasPermission(d)) continue
            if (api == "hid") {
                val h = hidDevices(d)
                for (k in 0 until h.length()) arr.put(h.get(k))
            } else arr.put(usbJson(d))
        }
        return JSONObject().put("devices", arr)
    }

    private fun matchBasic(f: JSONObject, d: UsbDevice): Boolean {
        if (f.has("vendorId") && f.getInt("vendorId") != d.vendorId) return false
        if (f.has("productId") && f.getInt("productId") != d.productId) return false
        val ifs = (0 until d.interfaceCount).map { d.getInterface(it) }
        if (f.has("classCode")) {
            val v = f.getInt("classCode")
            if (d.deviceClass != v && ifs.none { it.interfaceClass == v }) return false
        }
        if (f.has("subclassCode")) {
            val v = f.getInt("subclassCode")
            if (d.deviceSubclass != v && ifs.none { it.interfaceSubclass == v }) return false
        }
        if (f.has("protocolCode")) {
            val v = f.getInt("protocolCode")
            if (d.deviceProtocol != v && ifs.none { it.interfaceProtocol == v }) return false
        }
        return true
    }

    private fun matchUsage(f: JSONObject, inf: HidInfo): Boolean {
        if (!f.has("usagePage") && !f.has("usage")) return true
        return inf.collections.any {
            (!f.has("usagePage") || it.usagePage == f.getInt("usagePage")) &&
                (!f.has("usage") || it.usage == f.getInt("usage"))
        }
    }

    private fun request(a: JSONObject): JSONObject {
        val api = a.getString("api")
        val filters = a.optJSONArray("filters") ?: JSONArray()
        val excl = a.optJSONArray("exclusionFilters") ?: JSONArray()
        val notFound = BridgeError("NotFoundError", "No device selected.")

        val cands = usb.deviceList.values.filter { d ->
            (api != "hid" || hidIfaces(d).isNotEmpty()) &&
                (filters.length() == 0 || (0 until filters.length()).any { matchBasic(filters.getJSONObject(it), d) }) &&
                (0 until excl.length()).none { e ->
                    val x = excl.getJSONObject(e)
                    !x.has("usagePage") && !x.has("usage") && matchBasic(x, d)
                }
        }
        if (cands.isEmpty()) throw notFound
        val picked = (if (cands.size == 1) cands[0] else choose(cands)) ?: throw notFound
        if (!awaitPermission(picked)) throw notFound

        val arr = JSONArray()
        if (api == "hid") {
            for (itf in hidIfaces(picked)) {
                val inf = info(picked, itf)
                val ok = filters.length() == 0 || (0 until filters.length()).any {
                    val f = filters.getJSONObject(it)
                    matchBasic(f, picked) && matchUsage(f, inf)
                }
                if (ok) arr.put(hidJson(picked, itf))
            }
        } else arr.put(usbJson(picked))
        if (arr.length() == 0) throw notFound
        return JSONObject().put("devices", arr)
    }

    private fun choose(list: List<UsbDevice>): UsbDevice? {
        val latch = CountDownLatch(1)
        var result: UsbDevice? = null
        activity.runOnUiThread {
            val names = list.map {
                "${it.productName ?: it.deviceName}  (%04X:%04X)".format(it.vendorId, it.productId)
            }.toTypedArray<CharSequence>()
            AlertDialog.Builder(activity)
                .setTitle("Selecionar dispositivo")
                .setItems(names) { _, which -> result = list[which]; latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        latch.await(120, TimeUnit.SECONDS)
        return result
    }

    // ---------------------------------------------------------------- HID

    private fun findHid(id: String): Pair<UsbDevice, UsbInterface> {
        val (name, num) = id.split("|").let { it[0] to it[1].toInt() }
        val d = usb.deviceList[name] ?: throw BridgeError("NotFoundError", "Dispositivo desconectado.")
        val itf = hidIfaces(d).firstOrNull { it.id == num }
            ?: throw BridgeError("NotFoundError", "Interface HID não encontrada.")
        return d to itf
    }

    private fun hidOpen(id: String): JSONObject {
        if (hid.containsKey(id)) return JSONObject()
        val (d, itf) = findHid(id)
        if (!usb.hasPermission(d)) throw BridgeError("SecurityError", "Sem permissão USB.")
        val inf = info(d, itf) // antes de reivindicar a interface
        val c = acquire(d, id)
        if (!c.claimInterface(itf, true)) {
            release(d, id)
            throw BridgeError("NetworkError", "Unable to claim HID interface.")
        }
        val st = HidState(d, itf, inf)
        hid[id] = st
        st.epIn?.let { ep ->
            st.running = true
            st.reader = Thread { readLoop(id, st, c, ep) }.apply { isDaemon = true; start() }
        }
        return JSONObject()
    }

    private fun readLoop(id: String, st: HidState, c: UsbDeviceConnection, ep: UsbEndpoint) {
        val buf = ByteArray(maxOf(ep.maxPacketSize, 64))
        while (st.running) {
            val n = c.bulkTransfer(ep, buf, buf.size, 200)
            if (n > 0) {
                val ids = st.info.usesReportIds
                val rid = if (ids) buf[0].toInt() and 0xFF else 0
                val data = if (ids) buf.copyOfRange(1, n) else buf.copyOf(n)
                emit("hid", "inputreport", JSONObject().put("id", id).put("reportId", rid).put("data", b64(data)))
            } else if (n < 0 && !usb.deviceList.containsKey(st.device.deviceName)) break
        }
    }

    private fun closeHid(id: String) {
        val st = hid.remove(id) ?: return
        st.running = false
        try { st.reader?.join(500) } catch (_: Exception) {}
        try { connOf(st.device)?.releaseInterface(st.iface) } catch (_: Exception) {}
        release(st.device, id)
    }

    private fun hidSt(id: String) =
        hid[id] ?: throw BridgeError("InvalidStateError", "The device must be opened first.")

    private fun hidSend(a: JSONObject, feature: Boolean): JSONObject {
        val st = hidSt(a.getString("id"))
        val c = conn(st.device)
        val rid = a.getInt("reportId")
        val body = unb64(a.getString("data"))
        val payload = if (rid != 0) byteArrayOf(rid.toByte()) + body else body
        val out = st.epOut
        val n = if (!feature && out != null) {
            c.bulkTransfer(out, payload, payload.size, 1000) // Interrupt OUT, como hidraw/Windows
        } else {
            val type = if (feature) 3 else 2
            c.controlTransfer(0x21, 0x09, (type shl 8) or rid, st.iface.id, payload, payload.size, 1000)
        }
        if (n < 0) throw BridgeError("NetworkError", "Failed to write the report.")
        return JSONObject()
    }

    private fun hidGetFeature(a: JSONObject): JSONObject {
        val st = hidSt(a.getString("id"))
        val c = conn(st.device)
        val rid = a.getInt("reportId")
        val want = (st.info.featureBytes[rid] ?: 64) + (if (rid != 0) 1 else 0)
        val buf = ByteArray(want)
        val n = c.controlTransfer(0xA1, 0x01, (3 shl 8) or rid, st.iface.id, buf, want, 1000)
        if (n < 0) throw BridgeError("NetworkError", "Failed to receive the feature report.")
        val data = if (rid != 0 && n > 0) buf.copyOfRange(1, n) else buf.copyOf(maxOf(n, 0))
        return JSONObject().put("data", b64(data))
    }

    // ---------------------------------------------------------------- WebUSB

    private fun altJson(itf: UsbInterface): JSONObject {
        val eps = JSONArray()
        for (e in 0 until itf.endpointCount) {
            val ep = itf.getEndpoint(e)
            eps.put(
                JSONObject()
                    .put("endpointNumber", ep.endpointNumber)
                    .put("direction", if (ep.direction == UsbConstants.USB_DIR_IN) "in" else "out")
                    .put("type", when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
                        else -> "isochronous"
                    })
                    .put("packetSize", ep.maxPacketSize)
            )
        }
        return JSONObject()
            .put("alternateSetting", itf.alternateSetting)
            .put("interfaceClass", itf.interfaceClass)
            .put("interfaceSubclass", itf.interfaceSubclass)
            .put("interfaceProtocol", itf.interfaceProtocol)
            .put("interfaceName", itf.name)
            .put("endpoints", eps)
    }

    private fun usbJson(d: UsbDevice): JSONObject {
        val cfgs = JSONArray()
        for (ci in 0 until d.configurationCount) {
            val cfg = d.getConfiguration(ci)
            val byId = LinkedHashMap<Int, JSONArray>()
            for (ii in 0 until cfg.interfaceCount) {
                val itf = cfg.getInterface(ii)
                byId.getOrPut(itf.id) { JSONArray() }.put(altJson(itf))
            }
            val ifs = JSONArray()
            byId.forEach { (n, alts) -> ifs.put(JSONObject().put("interfaceNumber", n).put("alternates", alts)) }
            cfgs.put(
                JSONObject().put("configurationValue", cfg.id)
                    .put("configurationName", cfg.name).put("interfaces", ifs)
            )
        }
        val ver = (d.version ?: "0.0").split(".")
        val frac = ver.getOrElse(1) { "00" }.padEnd(2, '0')
        return JSONObject()
            .put("id", d.deviceName)
            .put("deviceClass", d.deviceClass).put("deviceSubclass", d.deviceSubclass)
            .put("deviceProtocol", d.deviceProtocol)
            .put("vendorId", d.vendorId).put("productId", d.productId)
            .put("deviceVersionMajor", ver[0].toIntOrNull() ?: 0)
            .put("deviceVersionMinor", frac[0].digitToIntOrNull() ?: 0)
            .put("deviceVersionSubminor", frac[1].digitToIntOrNull() ?: 0)
            .put("manufacturerName", d.manufacturerName)
            .put("productName", d.productName)
            .put("serialNumber", if (usb.hasPermission(d)) d.serialNumber else null)
            .put("configurations", cfgs)
    }

    private fun findUsb(id: String) =
        usb.deviceList[id] ?: throw BridgeError("NotFoundError", "Dispositivo desconectado.")

    private fun usbSt(id: String) =
        usbs[id] ?: throw BridgeError("InvalidStateError", "The device must be opened first.")

    private fun usbOpen(id: String): JSONObject {
        val d = findUsb(id)
        if (!usb.hasPermission(d)) throw BridgeError("SecurityError", "Sem permissão USB.")
        acquire(d, id)
        val cfg = if (d.configurationCount > 0) d.getConfiguration(0) else null
        usbs[id] = UsbState(d, cfg)
        return JSONObject().put("configurationValue", cfg?.id ?: JSONObject.NULL)
    }

    private fun usbClose(id: String) {
        val st = usbs.remove(id) ?: return
        val c = connOf(st.device)
        st.claimed.values.forEach { try { c?.releaseInterface(it) } catch (_: Exception) {} }
        release(st.device, id)
    }

    private fun ifacesOf(cfg: UsbConfiguration?) =
        (0 until (cfg?.interfaceCount ?: 0)).map { cfg!!.getInterface(it) }

    private fun usbSelectConfig(a: JSONObject): JSONObject {
        val st = usbSt(a.getString("id"))
        val c = conn(st.device)
        val v = a.getInt("configurationValue")
        val cfg = (0 until st.device.configurationCount).map { st.device.getConfiguration(it) }
            .firstOrNull { it.id == v } ?: throw BridgeError("NotFoundError", "Configuração inexistente.")
        st.claimed.values.forEach { c.releaseInterface(it) }
        st.claimed.clear()
        if (!c.setConfiguration(cfg)) throw BridgeError("NetworkError", "Falha ao selecionar a configuração.")
        st.cfg = cfg
        return JSONObject()
    }

    private fun usbClaim(a: JSONObject): JSONObject {
        val st = usbSt(a.getString("id"))
        val c = conn(st.device)
        val n = a.getInt("interfaceNumber")
        val itf = ifacesOf(st.cfg).firstOrNull { it.id == n && it.alternateSetting == 0 }
            ?: throw BridgeError("NotFoundError", "Interface inexistente.")
        if (!c.claimInterface(itf, true)) throw BridgeError("NetworkError", "Unable to claim interface.")
        st.claimed[n] = itf
        return JSONObject()
    }

    private fun usbRelease(a: JSONObject): JSONObject {
        val st = usbSt(a.getString("id"))
        st.claimed.remove(a.getInt("interfaceNumber"))?.let { conn(st.device).releaseInterface(it) }
        return JSONObject()
    }

    private fun usbAlt(a: JSONObject): JSONObject {
        val st = usbSt(a.getString("id"))
        val n = a.getInt("interfaceNumber")
        val alt = a.getInt("alternateSetting")
        val itf = ifacesOf(st.cfg).firstOrNull { it.id == n && it.alternateSetting == alt }
            ?: throw BridgeError("NotFoundError", "Alternate setting inexistente.")
        if (!conn(st.device).setInterface(itf)) throw BridgeError("NetworkError", "Falha em setInterface.")
        st.claimed[n] = itf
        return JSONObject()
    }

    private fun reqType(a: JSONObject, dirIn: Boolean): Int {
        val t = when (a.getString("requestType")) {
            "standard" -> 0; "class" -> 1; "vendor" -> 2
            else -> throw BridgeError("TypeError", "requestType inválido")
        }
        val r = when (a.getString("recipient")) {
            "device" -> 0; "interface" -> 1; "endpoint" -> 2; "other" -> 3
            else -> throw BridgeError("TypeError", "recipient inválido")
        }
        return (if (dirIn) 0x80 else 0) or (t shl 5) or r
    }

    private fun usbCtlIn(a: JSONObject): JSONObject {
        val st = usbSt(a.getString("id"))
        val len = a.getInt("length")
        val buf = ByteArray(len)
        val n = conn(st.device).controlTransfer(
            reqType(a, true), a.getInt("request"), a.getInt("value"), a.getInt("index"), buf, len, 5000
        )
        if (n < 0) throw BridgeError("NetworkError", "controlTransferIn failed.")
        return JSONObject().put("status", "ok").put("data", b64(buf.copyOf(n)))
    }

    private fun usbCtlOut(a: JSONObject): JSONObject {
        val st = usbSt(a.getString("id"))
        val body = if (a.has("data")) unb64(a.getString("data")) else ByteArray(0)
        val n = conn(st.device).controlTransfer(
            reqType(a, false), a.getInt("request"), a.getInt("value"), a.getInt("index"), body, body.size, 5000
        )
        if (n < 0) throw BridgeError("NetworkError", "controlTransferOut failed.")
        return JSONObject().put("status", "ok").put("bytesWritten", n)
    }

    private fun findEp(st: UsbState, num: Int, dirIn: Boolean): UsbEndpoint {
        for (itf in st.claimed.values) for (i in 0 until itf.endpointCount) {
            val ep = itf.getEndpoint(i)
            if (ep.endpointNumber == num && (ep.direction == UsbConstants.USB_DIR_IN) == dirIn) return ep
        }
        throw BridgeError("NotFoundError", "Endpoint não encontrado (interface reivindicada?).")
    }

    private fun usbIn(a: JSONObject): JSONObject {
        val st = usbSt(a.getString("id"))
        val ep = findEp(st, a.getInt("endpointNumber"), true)
        val len = a.getInt("length")
        val buf = ByteArray(len)
        val n = conn(st.device).bulkTransfer(ep, buf, len, 0)
        if (n < 0) throw BridgeError("NetworkError", "transferIn failed.")
        return JSONObject().put("status", "ok").put("data", b64(buf.copyOf(n)))
    }

    private fun usbOut(a: JSONObject): JSONObject {
        val st = usbSt(a.getString("id"))
        val ep = findEp(st, a.getInt("endpointNumber"), false)
        val body = unb64(a.getString("data"))
        val n = conn(st.device).bulkTransfer(ep, body, body.size, 5000)
        if (n < 0) throw BridgeError("NetworkError", "transferOut failed.")
        return JSONObject().put("status", "ok").put("bytesWritten", n)
    }
}
