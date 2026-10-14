package com.example.goldingen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Ayar ekrani: sayisal degerler burada girilir, servis Baslat'a basilinca bunlari okur. */
class MainActivity : Activity() {

    private class Field(val key: String, val label: String, val def: String, val decimal: Boolean = false)

    private val fields = listOf(
        Field("radius", "Yarıçap (piksel) – Random sonrası bu çemberin içindeki boss'a gidilir", "150"),
        Field("arrive", "Varış mesafesi (piksel) – imleç kafatasına bu kadar yaklaşınca harita kapanır", "45"),
        Field("minAtk", "Minimum ATK (buff için)", "0"),
        Field("minDef", "Minimum DEF (buff için)", "0"),
        Field("buffTravel", "Buff noktasına yürüme/ışınlanma bekleme (sn)", "12"),
        Field("buffWait", "Buff alınmasını bekleme üst sınırı (sn)", "90"),
        Field("randomWait", "Random sonrası bekleme (sn)", "2.5", true),
        Field("walkTimeout", "Boss'a varış zaman aşımı (sn)", "60"),
        Field("fightTimeout", "Bir boss için savaş zaman aşımı (sn)", "300")
    )

    private val inputs = HashMap<String, EditText>()
    private lateinit var buffOn: CheckBox
    private lateinit var stopAuto: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(pad, pad, pad, pad)

        val info = TextView(this)
        info.textSize = 15f
        info.text = "Goldingen – MU Immortal sarı boss avı\n\n" +
            "KURULUM\n" +
            "1) 'Erişilebilirlik ayarlarını aç' → Goldingen servisini aç. Açılmıyorsa Ayarlar > Uygulamalar > " +
            "Goldingen > sağ üst üç nokta > 'Kısıtlı ayarlara izin ver'.\n" +
            "2) Shizuku çalışıyor olmalı (oyunda panelden 'Shizuku izni').\n" +
            "3) Aşağıdaki değerleri gir ve Kaydet'e bas.\n" +
            "4) Oyuna geç. Solda panel çıkar. 'Ayar' menüsünden noktaları işaretle, 'Test' ile kontrol et, " +
            "sonra '▶ Başlat'.\n"
        col.addView(info)

        for (f in fields) {
            val tv = TextView(this)
            tv.text = f.label
            tv.textSize = 13f
            tv.setPadding(0, pad / 2, 0, 0)
            col.addView(tv)
            val et = EditText(this)
            et.inputType = if (f.decimal) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else {
                InputType.TYPE_CLASS_NUMBER
            }
            et.setText(prefs().getString("cfg_" + f.key, f.def))
            inputs[f.key] = et
            col.addView(et)
        }

        buffOn = CheckBox(this)
        buffOn.text = "Buff alma açık (ATK ve DEF ikisi birden minimumun altındaysa buff noktasına git)"
        buffOn.isChecked = prefs().getBoolean("cfg_buffOn", true)
        col.addView(buffOn)

        stopAuto = CheckBox(this)
        stopAuto.text = "Boss ölünce oto saldırı düğmesine bir kez daha dokun (kapatmak için)"
        stopAuto.isChecked = prefs().getBoolean("cfg_stopAuto", false)
        col.addView(stopAuto)

        val save = Button(this)
        save.text = "Kaydet"
        save.setOnClickListener {
            val e = prefs().edit()
            for (f in fields) {
                val raw = inputs[f.key]?.text?.toString()?.trim().orEmpty()
                e.putString("cfg_" + f.key, if (raw.isEmpty()) f.def else raw)
            }
            e.putBoolean("cfg_buffOn", buffOn.isChecked)
            e.putBoolean("cfg_stopAuto", stopAuto.isChecked)
            e.apply()
            Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show()
        }
        col.addView(
            save,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val btn = Button(this)
        btn.text = "Erişilebilirlik ayarlarını aç"
        btn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        col.addView(
            btn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val sv = ScrollView(this)
        sv.addView(col)
        setContentView(sv)
    }

    private fun prefs() = getSharedPreferences("goldingen", Context.MODE_PRIVATE)
}
