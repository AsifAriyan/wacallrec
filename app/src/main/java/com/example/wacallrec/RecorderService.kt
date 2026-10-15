package com.example.wacallrec

import android.app.*
import android.content.Intent
import android.net.LocalServerSocket
import android.os.IBinder
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Runs scrcpy-server twice via su (playback + mic), receives raw PCM
 * (s16le, 48 kHz, stereo) over abstract local sockets, saves each as WAV,
 * then mixes them into one WAV.
 */
class RecorderService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_VERSION = "version"
        const val EXTRA_ASROOT = "asroot"
        @Volatile var running = false
        @Volatile var status = "Idle"
    }

    private val procs = CopyOnWriteArrayList<Process>()
    private val servers = CopyOnWriteArrayList<LocalServerSocket>()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }
        startForeground(1, buildNotification())
        if (running) return START_NOT_STICKY
        running = true
        val v = i?.getStringExtra(EXTRA_VERSION)?.ifBlank { null } ?: "3.3.3"
        val asRoot = i?.getBooleanExtra(EXTRA_ASROOT, false) ?: false
        thread { record(v, asRoot) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rec", "Recording", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 0, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        return Notification.Builder(this, "rec")
            .setContentTitle("Recording call audio")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stopPi).build())
            .build()
    }

    private fun stopRecording() {
        servers.forEach { try { it.close() } catch (_: Exception) {} }
        procs.forEach { it.destroy() }
        thread {
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f com.genymobile.scrcpy.Server")).waitFor()
            } catch (_: Exception) {}
        }
    }

    private fun record(version: String, asRoot: Boolean) {
        try {
            val dir = getExternalFilesDir(null)!!
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            status = "Preparing server..."
            val prep = Runtime.getRuntime().exec(
                arrayOf(
                    "su", "-c",
                    "cp /sdcard/Download/scrcpy-server.jar /data/local/tmp/scrcpy-server.jar && " +
                        "chmod 644 /data/local/tmp/scrcpy-server.jar"
                )
            )
            if (prep.waitFor() != 0) {
                status = "Error: could not copy /sdcard/Download/scrcpy-server.jar (file missing, or root denied)"
                return
            }
            val peaks = mapOf("remote" to AtomicInteger(), "mic" to AtomicInteger())
            val remote = File(dir, "${stamp}_remote.wav")
            val mic = File(dir, "${stamp}_mic.wav")
            status = "Recording... (files go to ${dir.path})"
            val t1 = stream("remote", "playback", version, asRoot, remote, File(dir, "${stamp}_remote.log"), peaks["remote"]!!)
            val t2 = stream("mic", "mic", version, asRoot, mic, File(dir, "${stamp}_mic.log"), peaks["mic"]!!)
            t1.join(); t2.join()
            status = "Mixing..."
            val mixed = File(dir, "${stamp}_mixed.wav")
            mix(remote, mic, mixed)
            status = "Done. Folder: ${dir.path}\n" +
                "${mixed.name}\nremote peak=${peaks["remote"]!!.get()} (0 = silent), mic peak=${peaks["mic"]!!.get()}\n" +
                "If a peak is 0 or a file is tiny, open the matching .log file."
        } catch (e: Exception) {
            status = "Error: ${e.message}"
        } finally {
            running = false
            servers.clear(); procs.clear()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stream(
        label: String, src: String, version: String, asRoot: Boolean,
        wav: File, log: File, peak: AtomicInteger
    ): Thread {
        val scid = "%08x".format(Random().nextInt() and 0x7fffffff)
        val server = LocalServerSocket("scrcpy_$scid")
        servers.add(server)
        val connected = AtomicBoolean(false)
        val opts = "scid=$scid log_level=info video=false control=false audio=true " +
            "audio_source=$src audio_codec=raw" +
            (if (src == "playback") " audio_dup=true" else "") +
            " send_device_meta=false send_codec_meta=false send_frame_meta=false send_dummy_byte=false cleanup=false"
        val cmd = "CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server $version $opts"
        val args = if (asRoot) arrayOf("su", "-c", cmd) else arrayOf("su", "-c", cmd, "2000")
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        procs.add(p)
        thread {
            try { p.inputStream.bufferedReader().forEachLine { log.appendText(it + "\n") } } catch (_: Exception) {}
        }
        thread {
            Thread.sleep(15000)
            if (!connected.get()) {
                log.appendText("[app] server never connected within 15 s\n")
                try { server.close() } catch (_: Exception) {}
            }
        }
        return thread {
            val raf = RandomAccessFile(wav, "rw")
            var total = 0L
            try {
                val s = server.accept()
                connected.set(true)
                raf.write(ByteArray(44))
                val ins = s.inputStream
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    total += n
                    var i = 0
                    while (i + 1 < n) {
                        val v = Math.abs((buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8))
                        if (v > peak.get()) peak.set(v)
                        i += 2
                    }
                }
            } catch (e: Exception) {
                log.appendText("[app] $label stream ended: ${e.message}\n")
            } finally {
                try { writeWavHeader(raf, total); raf.close() } catch (_: Exception) {}
            }
        }
    }

    private fun writeWavHeader(f: RandomAccessFile, dataLen: Long) {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataLen).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(2)
        b.putInt(48000); b.putInt(48000 * 2 * 2); b.putShort(4); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataLen.toInt())
        f.seek(0); f.write(b.array())
    }

    private fun readFully(i: InputStream, b: ByteArray): Int {
        var off = 0
        while (off < b.size) {
            val n = i.read(b, off, b.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun mix(a: File, b: File, out: File) {
        if (!a.exists() || !b.exists()) return
        val ia = FileInputStream(a).also { it.skip(44) }
        val ib = FileInputStream(b).also { it.skip(44) }
        val o = RandomAccessFile(out, "rw")
        o.write(ByteArray(44))
        val ba = ByteArray(8192); val bb = ByteArray(8192)
        var total = 0L
        while (true) {
            val na = readFully(ia, ba); val nb = readFully(ib, bb)
            val n = maxOf(na, nb)
            if (n <= 0) break
            val ob = ByteArray(n)
            var i = 0
            while (i + 1 < n) {
                val x = (if (i + 1 < na) (ba[i].toInt() and 0xff) or (ba[i + 1].toInt() shl 8) else 0) +
                    (if (i + 1 < nb) (bb[i].toInt() and 0xff) or (bb[i + 1].toInt() shl 8) else 0)
                val c = x.coerceIn(-32768, 32767)
                ob[i] = (c and 0xff).toByte(); ob[i + 1] = ((c shr 8) and 0xff).toByte()
                i += 2
            }
            o.write(ob); total += n
        }
        writeWavHeader(o, total)
        o.close(); ia.close(); ib.close()
    }
}
