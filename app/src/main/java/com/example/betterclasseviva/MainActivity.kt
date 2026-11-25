package com.example.betterclasseviva

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.github.mikephil.charting.animation.Easing
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.components.LimitLine
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var scraper: ClasseVivaScraper
    private var reportCardSalvata: ReportCard? = null
    private var rawReportCardSalvata: ReportCard? = null
    private var showAllMaterie = true
    private var ultimoIndiceGrafico = -1
    private var ordineDecrescente = true // true = dalla media più alta alla più bassa

    private fun isReligione(materia: String): Boolean {
        val m = materia.lowercase()
        return m.contains("religione") || m.contains("alternativa") || m.contains("catholic")
    }

    private fun filtraReligione(reportCard: ReportCard): ReportCard {
        fun filtraPeriodo(periodo: PeriodoScuola): PeriodoScuola {
            val valutazioniFiltrate = periodo.valutazioni.filterKeys { !isReligione(it) }.toMutableMap()
            val medieFiltrate = periodo.medie.filterKeys { !isReligione(it) }.toMutableMap()
            return PeriodoScuola(valutazioniFiltrate, medieFiltrate)
        }

        val trimestreFiltrato = filtraPeriodo(reportCard.trimestre)
        val pentamestreFiltrato = filtraPeriodo(reportCard.pentamestre)

        val tutteLeMedie = (trimestreFiltrato.medie.values + pentamestreFiltrato.medie.values).filter { it.ponderata > 0.0 }
        val nuovaMediaGen = if (tutteLeMedie.isNotEmpty()) tutteLeMedie.map { it.ponderata }.average() else 0.0

        return ReportCard(trimestreFiltrato, pentamestreFiltrato, nuovaMediaGen, reportCard.nomeStudente)
    }

    fun calcolaMediaPonderata(voti: List<Voto>): Double {
        if (voti.isEmpty()) return 0.0
        val sommaSemplice = voti.sumOf { it.numeric }
        val mediaSemplice = sommaSemplice / voti.size
        var sommaProdotti = 0.0
        var sommaPesi = 0.0
        for (v in voti) {
            sommaProdotti += (v.numeric * v.weight)
            sommaPesi += v.weight
        }
        return if (sommaPesi > 0) sommaProdotti / sommaPesi else mediaSemplice
    }

    private fun ricaricaLista() {
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        reportCardSalvata?.let { reportCard ->
            viewPager.animate().cancel()
            viewPager.animate()
                .alpha(0f)
                .setStartDelay(0)
                .setDuration(130)
                .withEndAction {
                    val paginaCorrente = viewPager.currentItem
                    viewPager.adapter = ScreenSlidePagerAdapter(reportCard, showAllMaterie)
                    viewPager.setCurrentItem(paginaCorrente, false)
                    viewPager.animate()
                        .alpha(1f)
                        .setStartDelay(0)
                        .setDuration(200)
                        .start()
                }
                .start()
        }
    }

    /** Cambia solo l'ordine delle card già presenti: ognuna scivola nella nuova posizione. */
    private fun riordinaLista() {
        supportFragmentManager.fragments
            .filterIsInstance<TabPeriodoFragment>()
            .forEach { it.riordina(ordineDecrescente) }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scraper = ClasseVivaScraper()

        val sharedPrefs = getSharedPreferences("BetterClassevivaPrefs", MODE_PRIVATE)
        val savedUser = sharedPrefs.getString("username", null)
        val savedPass = sharedPrefs.getString("password", null)

        val btnLogout = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnLogout)
        val segIndicator = findViewById<View>(R.id.segIndicator)
        val segTutte = findViewById<TextView>(R.id.segTutte)
        val segInsuff = findViewById<TextView>(R.id.segInsuff)
        val chipOrdine = findViewById<View>(R.id.chipOrdine)
        val imgOrdine = findViewById<ImageView>(R.id.imgOrdine)
        imgOrdine.rotation = if (ordineDecrescente) 180f else 0f
        val inputUser = findViewById<EditText>(R.id.inputUsername)
        val inputPass = findViewById<EditText>(R.id.inputPassword)
        val btnTogglePassword = findViewById<ImageButton>(R.id.btnTogglePasswordVisibility)
        val btnAccedi = findViewById<Button>(R.id.btnAccedi)
        val layoutLogin = findViewById<LinearLayout>(R.id.layoutLogin)
        val layoutDashboard = findViewById<View>(R.id.layoutDashboard)
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)

        // Feedback al tocco su tutti gli elementi interattivi principali
        btnAccedi.addPressScale(0.96f)
        findViewById<View>(R.id.segFiltro).addPressScale(0.97f)
        findViewById<View>(R.id.layoutMediaGeneraleContainer).addPressScale(0.985f)

        val btnSettings = findViewById<View>(R.id.btnSettings)
        btnSettings.addPressScale(0.92f)
        btnSettings.setOnClickListener {
            btnSettings.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
            val switchReligione = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchMostraReligione)
            val btnSalva = dialogView.findViewById<Button>(R.id.btnSalvaImpostazioni)

            val sharedPrefs = getSharedPreferences("BetterClassevivaPrefs", MODE_PRIVATE)
            switchReligione.isChecked = sharedPrefs.getBoolean("mostra_religione", true)

            val dialog = android.app.Dialog(this)
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            dialog.setContentView(dialogView)
            dialog.setCancelable(false)
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setGravity(Gravity.CENTER)
                addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.5f)
            }

            val card = dialogView.findViewById<View>(R.id.dialogCard)
            val dimMax = 0.65f
            var chiuso = false

            fun chiudi() {
                if (chiuso) return
                chiuso = true
                card.animate().cancel()
                card.animate()
                    .alpha(0f).scaleX(0.92f).scaleY(0.92f).translationY(16f * resources.displayMetrics.density)
                    .setStartDelay(0)
                    .setDuration(170)
                    .setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { dialog.dismiss() }
                    .start()
                ValueAnimator.ofFloat(dimMax, 0f).apply {
                    duration = 170
                    addUpdateListener { dialog.window?.setDimAmount(it.animatedValue as Float) }
                    start()
                }
            }

            btnSalva.setOnClickListener {
                val mostraReligioneNuova = switchReligione.isChecked
                sharedPrefs.edit().putBoolean("mostra_religione", mostraReligioneNuova).apply()
                chiudi()
                rawReportCardSalvata?.let { raw ->
                    reportCardSalvata = if (mostraReligioneNuova) raw else filtraReligione(raw)
                    reportCardSalvata?.let { rc ->
                        val txtMediaGenerale = findViewById<TextView>(R.id.txtMediaGenerale)
                        val chartMediaGeneraleFull = findViewById<LineChart>(R.id.chartMediaGeneraleFull)
                        val txtConteggioInsufficienze = findViewById<TextView>(R.id.txtConteggioInsufficienze)
                        val containerListaInsufficienze = findViewById<LinearLayout>(R.id.containerListaInsufficienze)
                        val txtMediaTrimestre = findViewById<TextView>(R.id.txtMediaTrimestre)
                        val txtMediaPentamestre = findViewById<TextView>(R.id.txtMediaPentamestre)
                        val chipMaterie = findViewById<TextView>(R.id.chipTotaleMaterie)
                        val chipVoti = findViewById<TextView>(R.id.chipTotaleVoti)
                        val chipMigliore = findViewById<TextView>(R.id.chipMateriaMigliore)
                        val chipMigliorLabel = findViewById<TextView>(R.id.chipMigliorLabel)

                        val tutteMaterieSet = (rc.trimestre.valutazioni.keys + rc.pentamestre.valutazioni.keys).toSet()
                        val tuttiVoti = rc.trimestre.valutazioni.values.flatten() + rc.pentamestre.valutazioni.values.flatten()
                        chipMaterie.text = "${tutteMaterieSet.size}"
                        chipVoti.text = "${tuttiVoti.size}"

                        val tutteLeMedie = (rc.trimestre.medie.toList() + rc.pentamestre.medie.toList())
                            .filter { it.second.ponderata > 0.0 }
                        val migliore = tutteLeMedie.maxByOrNull { it.second.ponderata }
                        if (migliore != null) {
                            chipMigliore.text = String.format("%.1f", migliore.second.ponderata)
                            chipMigliorLabel.text = migliore.first.replaceFirstChar { it.uppercase() }
                        } else {
                            chipMigliore.text = "--"
                            chipMigliorLabel.text = "Migliore"
                        }

                        val coloreMedia = Color.parseColor(if (rc.mediaGeneraleTotale >= 6.0) "#30D158" else "#FF453A")
                        txtMediaGenerale.text = String.format("%.2f", rc.mediaGeneraleTotale)
                        txtMediaGenerale.setTextColor(coloreMedia)

                        creaGraficoMediaGenerale(chartMediaGeneraleFull, rc)
                        aggiornaSezioneInsufficienze(txtConteggioInsufficienze, containerListaInsufficienze, rc)

                        val mediaT = if (rc.trimestre.valutazioni.values.flatten().isNotEmpty())
                            rc.trimestre.valutazioni.values.flatten().map { it.numeric }.average() else 0.0
                        val mediaP = if (rc.pentamestre.valutazioni.values.flatten().isNotEmpty())
                            rc.pentamestre.valutazioni.values.flatten().map { it.numeric }.average() else 0.0

                        txtMediaTrimestre.text = if (mediaT > 0) String.format("%.2f", mediaT) else "--"
                        txtMediaTrimestre.setTextColor(Color.parseColor(if (mediaT >= 6.0) "#30D158" else "#FF453A"))
                        txtMediaPentamestre.text = if (mediaP > 0) String.format("%.2f", mediaP) else "--"
                        txtMediaPentamestre.setTextColor(Color.parseColor(if (mediaP >= 6.0) "#30D158" else "#FF453A"))

                        ricaricaLista()
                    }
                }
            }

            dialogView.setOnClickListener { chiudi() }
            dialog.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                    chiudi(); true
                } else false
            }

            dialog.show()

            val d = resources.displayMetrics.density
            card.alpha = 0f
            card.scaleX = 0.88f
            card.scaleY = 0.88f
            card.translationY = 28f * d
            card.animate()
                .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setStartDelay(0)
                .setDuration(380)
                .setInterpolator(Anim.emphasized)
                .start()
            ValueAnimator.ofFloat(0f, dimMax).apply {
                duration = 300
                addUpdateListener { dialog.window?.setDimAmount(it.animatedValue as Float) }
                start()
            }
        }

        // Gestione insets per evitare che "Ciao" tocchi la barra delle notifiche
        ViewCompat.setOnApplyWindowInsetsListener(layoutDashboard) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            findViewById<View>(R.id.rowHeaderCiao).setPadding(
                20.dpToPx(),
                statusBarHeight + 16.dpToPx(),
                20.dpToPx(),
                4.dpToPx()
            )
            insets
        }

        // Mostra/nascondi password: l'icona fa un "pop" e il colore sfuma
        var isPasswordVisible = false
        var tintCorrente = Color.parseColor("#8E8E93")
        btnTogglePassword.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            val typeface = inputPass.typeface
            val tintNuovo: Int
            if (isPasswordVisible) {
                inputPass.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                tintNuovo = Color.parseColor("#007AFF")
            } else {
                inputPass.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                tintNuovo = Color.parseColor("#8E8E93")
            }
            inputPass.typeface = typeface
            inputPass.setSelection(inputPass.text.length)

            ValueAnimator.ofObject(ArgbEvaluator(), tintCorrente, tintNuovo).apply {
                duration = 260
                addUpdateListener { btnTogglePassword.setColorFilter(it.animatedValue as Int) }
                start()
            }
            tintCorrente = tintNuovo
            btnTogglePassword.setImageResource(if (isPasswordVisible) R.drawable.ic_eye_off else R.drawable.ic_eye)
            btnTogglePassword.bump(0.6f)
        }

        // Ricostruisce le pagine (filtro / ordinamento) con dissolvenza; le card rientrano in cascata
        fun ricaricaLista() {
            reportCardSalvata?.let { reportCard ->
                viewPager.animate().cancel()
                viewPager.animate()
                    .alpha(0f)
                    .setStartDelay(0)
                    .setDuration(130)
                    .withEndAction {
                        val paginaCorrente = viewPager.currentItem
                        viewPager.adapter = ScreenSlidePagerAdapter(reportCard, showAllMaterie)
                        viewPager.setCurrentItem(paginaCorrente, false)
                        viewPager.animate()
                            .alpha(1f)
                            .setStartDelay(0)
                            .setDuration(200)
                            .start()
                    }
                    .start()
            }
        }

        // Ordinamento per media: la freccia ruota
        chipOrdine.addPressScale(0.95f)
        chipOrdine.setOnClickListener {
            ordineDecrescente = !ordineDecrescente
            chipOrdine.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            imgOrdine.animate()
                .rotation(if (ordineDecrescente) 180f else 0f)
                .setStartDelay(0)
                .setDuration(340)
                .setInterpolator(Anim.emphasized)
                .start()
            riordinaLista()
        }

        // Filtro materie: controllo segmentato con indicatore che scorre
        fun applicaFiltro(tutte: Boolean) {
            if (showAllMaterie == tutte) return
            showAllMaterie = tutte

            val blu = Color.parseColor("#0A84FF")
            val rosso = Color.parseColor("#FF453A")
            val grigio = Color.parseColor("#8E8E93")
            val targetX = if (tutte) 0f else (segInsuff.left - segTutte.left).toFloat()

            segIndicator.animate().cancel()
            segIndicator.animate()
                .translationX(targetX)
                .setStartDelay(0)
                .setDuration(340)
                .setInterpolator(Anim.emphasized)
                .start()
            val coloreFondo = Color.parseColor(if (tutte) "#1C2738" else "#321C1C")
            val coloreFondoPrima = segIndicator.backgroundTintList?.defaultColor ?: coloreFondo
            ValueAnimator.ofObject(ArgbEvaluator(), coloreFondoPrima, coloreFondo).apply {
                duration = 340
                addUpdateListener { segIndicator.backgroundTintList = ColorStateList.valueOf(it.animatedValue as Int) }
                start()
            }
            segTutte.animateColorTo(if (tutte) blu else grigio, 300)
            segInsuff.animateColorTo(if (tutte) grigio else rosso, 300)
            segIndicator.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)

            ricaricaLista()
        }
        segTutte.setOnClickListener { applicaFiltro(true) }
        segInsuff.setOnClickListener { applicaFiltro(false) }

        // Instagram badges click listener
        val openInsta = {
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://instagram.com/fabio.odasso"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Impossibile aprire Instagram", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<View>(R.id.badgeInstaLogin).apply {
            addPressScale(0.96f)
            setOnClickListener { openInsta() }
        }
        findViewById<View>(R.id.badgeInstaDashboard).apply {
            addPressScale(0.96f)
            setOnClickListener { openInsta() }
        }

        // Logout: conferma con dialog personalizzato, poi la dashboard sfuma verso il basso
        btnLogout.setOnClickListener {
            btnLogout.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            val dialogView = layoutInflater.inflate(R.layout.dialog_logout_confirm, null)
            val btnAnnulla = dialogView.findViewById<Button>(R.id.btnAnnullaLogout)
            val btnConferma = dialogView.findViewById<Button>(R.id.btnConfermaLogout)

            val dialog = android.app.Dialog(this)
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            dialog.setContentView(dialogView)
            dialog.setCancelable(false)
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setGravity(Gravity.CENTER)
                addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.5f)
            }

            val card = dialogView.findViewById<View>(R.id.dialogCard)
            val dimMax = 0.65f
            var chiuso = false

            fun chiudi() {
                if (chiuso) return
                chiuso = true
                card.animate().cancel()
                card.animate()
                    .alpha(0f).scaleX(0.92f).scaleY(0.92f).translationY(16f * resources.displayMetrics.density)
                    .setStartDelay(0)
                    .setDuration(170)
                    .setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { dialog.dismiss() }
                    .start()
                ValueAnimator.ofFloat(dimMax, 0f).apply {
                    duration = 170
                    addUpdateListener { dialog.window?.setDimAmount(it.animatedValue as Float) }
                    start()
                }
            }

            btnAnnulla.setOnClickListener { chiudi() }
            btnConferma.setOnClickListener {
                chiudi()
                sharedPrefs.edit().clear().apply()
                inputPass.text.clear()
                btnLogout.hide()
                layoutDashboard.animate().cancel()
                layoutDashboard.animate()
                    .alpha(0f)
                    .translationY(30.dpToPx().toFloat())
                    .setStartDelay(0)
                    .setDuration(260)
                    .withEndAction {
                        layoutDashboard.visibility = View.GONE
                        layoutDashboard.alpha = 1f
                        layoutDashboard.translationY = 0f
                        layoutLogin.visibility = View.VISIBLE
                        animaEntrataLogin()
                    }
                    .start()
            }

            dialogView.setOnClickListener { chiudi() }
            dialog.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                    chiudi(); true
                } else false
            }

            dialog.show()

            val d = resources.displayMetrics.density
            card.alpha = 0f
            card.scaleX = 0.88f
            card.scaleY = 0.88f
            card.translationY = 28f * d
            card.animate()
                .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setStartDelay(0)
                .setDuration(380)
                .setInterpolator(Anim.emphasized)
                .start()
            ValueAnimator.ofFloat(0f, dimMax).apply {
                duration = 300
                addUpdateListener { dialog.window?.setDimAmount(it.animatedValue as Float) }
                start()
            }
        }

        btnAccedi.setOnClickListener {
            val user = inputUser.text.toString().trim()
            val pass = inputPass.text.toString().trim()

            if (user.isEmpty() || pass.isEmpty()) {
                if (user.isEmpty()) inputUser.shake()
                if (pass.isEmpty()) (inputPass.parent as View).shake()
                Toast.makeText(this, "Inserisci credenziali", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            effettuaLogin(user, pass)
        }

        if (savedUser != null && savedPass != null) {
            layoutLogin.visibility = View.GONE
            effettuaLogin(savedUser, savedPass)
        } else {
            animaEntrataLogin()
        }
    }

    // --- ANIMAZIONI DI INGRESSO ---

    private fun animaEntrataLogin() {
        val layoutLogin = findViewById<LinearLayout>(R.id.layoutLogin)
        for (i in 0 until layoutLogin.childCount) {
            layoutLogin.getChildAt(i).revealUp(delay = 80L + i * 80L)
        }
    }

    private fun animaEntrataDashboard() {
        val header = findViewById<ViewGroup>(R.id.layoutHeaderScorrevole)
        var step = 0
        for (i in 0 until header.childCount) {
            val child = header.getChildAt(i)
            if (child.id == R.id.layoutStatsQuickBar) {
                val bar = child as ViewGroup
                for (j in 0 until bar.childCount) {
                    bar.getChildAt(j).popIn(delay = 140L + step * 70L + j * 80L)
                }
            } else {
                child.revealUp(delay = 60L + step * 70L)
            }
            step++
        }
        // righe fisse sotto la parte scorrevole
        findViewById<View>(R.id.rowFiltri).revealUp(delay = 60L + step * 70L)
        findViewById<View>(R.id.tabLayout).revealUp(delay = 60L + (step + 1) * 70L)
    }

    private fun effettuaLogin(user: String, pass: String) {
        val layoutLogin = findViewById<LinearLayout>(R.id.layoutLogin)
        val layoutLoading = findViewById<LinearLayout>(R.id.layoutLoading)
        val layoutDashboard = findViewById<View>(R.id.layoutDashboard)
        val btnLogout = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnLogout)

        val txtCiaoStudente = findViewById<TextView>(R.id.txtCiaoStudente)
        val txtMediaGenerale = findViewById<TextView>(R.id.txtMediaGenerale)
        val layoutMediaGeneraleContainer = findViewById<LinearLayout>(R.id.layoutMediaGeneraleContainer)
        val imgInfoMedia = findViewById<ImageView>(R.id.imgInfoMedia)
        val layoutDettaglioMedie = findViewById<LinearLayout>(R.id.layoutDettaglioMedie)
        val txtMediaTrimestre = findViewById<TextView>(R.id.txtMediaTrimestre)
        val txtMediaPentamestre = findViewById<TextView>(R.id.txtMediaPentamestre)
        val layoutGraficoGeneraleDettagliato = findViewById<LinearLayout>(R.id.layoutGraficoGeneraleDettagliato)
        val chartMediaGeneraleFull = findViewById<LineChart>(R.id.chartMediaGeneraleFull)
        val txtConteggioInsufficienze = findViewById<TextView>(R.id.txtConteggioInsufficienze)
        val containerListaInsufficienze = findViewById<LinearLayout>(R.id.containerListaInsufficienze)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)

        val chipMaterie = findViewById<TextView>(R.id.chipTotaleMaterie)
        val chipVoti = findViewById<TextView>(R.id.chipTotaleVoti)
        val chipMigliore = findViewById<TextView>(R.id.chipMateriaMigliore)
        val chipMigliorLabel = findViewById<TextView>(R.id.chipMigliorLabel)

        // Login -> caricamento con dissolvenza incrociata
        if (layoutLogin.visibility == View.VISIBLE) {
            crossfade(layoutLogin, layoutLoading)
        } else {
            layoutLoading.alpha = 0f
            layoutLoading.visibility = View.VISIBLE
            layoutLoading.animate().alpha(1f).setStartDelay(0).setDuration(240).start()
        }

        lifecycleScope.launch {
            try {
                val rawReportCard = scraper.recuperaValutazioniEMedie(user, pass)
                rawReportCardSalvata = rawReportCard

                // Salvataggio credenziali per il prossimo avvio
                val sharedPrefs = getSharedPreferences("BetterClassevivaPrefs", MODE_PRIVATE)
                sharedPrefs.edit().putString("username", user).putString("password", pass).apply()

                val mostraReligione = sharedPrefs.getBoolean("mostra_religione", true)
                val reportCard = if (mostraReligione) rawReportCard else filtraReligione(rawReportCard)
                reportCardSalvata = reportCard

                // 0. Setup Ciao {nome}
                txtCiaoStudente.text = if (reportCard.nomeStudente.isNotEmpty()) "Ciao ${reportCard.nomeStudente}!" else "Ciao!"

                // Statistiche rapide (con conteggio animato)
                val tutteMaterieSet = (reportCard.trimestre.valutazioni.keys + reportCard.pentamestre.valutazioni.keys).toSet()
                val tuttiVoti = reportCard.trimestre.valutazioni.values.flatten() + reportCard.pentamestre.valutazioni.values.flatten()

                chipMaterie.animateNumber(0f, tutteMaterieSet.size.toFloat(), 700, 450) { "${it.toInt()}" }
                chipVoti.animateNumber(0f, tuttiVoti.size.toFloat(), 900, 500) { "${it.toInt()}" }

                val tutteLeMedie = (reportCard.trimestre.medie.toList() + reportCard.pentamestre.medie.toList())
                    .filter { it.second.ponderata > 0.0 }
                val migliore = tutteLeMedie.maxByOrNull { it.second.ponderata }
                if (migliore != null) {
                    chipMigliore.animateNumber(0f, migliore.second.ponderata.toFloat(), 900, 550) { String.format("%.1f", it) }
                    chipMigliorLabel.text = migliore.first.replaceFirstChar { it.uppercase() }
                } else {
                    chipMigliore.text = "--"
                    chipMigliorLabel.text = "Migliore"
                }

                // 1. Media Generale: conteggio da 0 + colore che sfuma + "battito" finale
                val coloreMedia = Color.parseColor(if (reportCard.mediaGeneraleTotale >= 6.0) "#30D158" else "#FF453A")
                txtMediaGenerale.setTextColor(Color.WHITE)
                txtMediaGenerale.pivotX = 0f
                txtMediaGenerale.post { txtMediaGenerale.pivotY = txtMediaGenerale.height / 2f }
                txtMediaGenerale.animateNumber(
                    0f, reportCard.mediaGeneraleTotale.toFloat(), 1200, 250,
                    onEnd = { txtMediaGenerale.bump(1.1f) }
                ) { String.format("%.2f", it) }
                txtMediaGenerale.animateColorTo(coloreMedia, 1200, 250)

                // 2. Grafico andamento media generale
                creaGraficoMediaGenerale(chartMediaGeneraleFull, reportCard)

                // 3. Insufficienze
                aggiornaSezioneInsufficienze(txtConteggioInsufficienze, containerListaInsufficienze, reportCard)

                // 4. Medie per periodo (il conteggio parte quando il pannello si apre)
                val mediaT = if (reportCard.trimestre.valutazioni.values.flatten().isNotEmpty())
                    reportCard.trimestre.valutazioni.values.flatten().map { it.numeric }.average() else 0.0
                val mediaP = if (reportCard.pentamestre.valutazioni.values.flatten().isNotEmpty())
                    reportCard.pentamestre.valutazioni.values.flatten().map { it.numeric }.average() else 0.0

                txtMediaTrimestre.text = if (mediaT > 0) String.format("%.2f", mediaT) else "--"
                txtMediaTrimestre.setTextColor(Color.parseColor(if (mediaT >= 6.0) "#30D158" else "#FF453A"))
                txtMediaPentamestre.text = if (mediaP > 0) String.format("%.2f", mediaP) else "--"
                txtMediaPentamestre.setTextColor(Color.parseColor(if (mediaP >= 6.0) "#30D158" else "#FF453A"))

                // 5. Espansione media + grafico: altezza animata, freccia che ruota, grafico che si "disegna"
                layoutMediaGeneraleContainer.setOnClickListener {
                    val apri = !layoutDettaglioMedie.isOpenTarget()
                    layoutMediaGeneraleContainer.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    imgInfoMedia.animate()
                        .rotation(if (apri) 180f else 0f)
                        .setStartDelay(0)
                        .setDuration(350)
                        .setInterpolator(Anim.emphasized)
                        .start()

                    if (apri) {
                        layoutDettaglioMedie.expandSmooth()
                        layoutGraficoGeneraleDettagliato.expandSmooth(delay = 80L)
                        if (mediaT > 0) txtMediaTrimestre.animateNumber(0f, mediaT.toFloat(), 700, 150) { String.format("%.2f", it) }
                        if (mediaP > 0) txtMediaPentamestre.animateNumber(0f, mediaP.toFloat(), 700, 250) { String.format("%.2f", it) }
                        chartMediaGeneraleFull.postDelayed({
                            chartMediaGeneraleFull.animateXY(1000, 800, Easing.EaseInOutCubic, Easing.EaseOutCubic)
                        }, 200)
                        chartMediaGeneraleFull.postDelayed({
                            if (ultimoIndiceGrafico >= 0) chartMediaGeneraleFull.highlightValue(ultimoIndiceGrafico.toFloat(), 0, false)
                        }, 1250)
                    } else {
                        layoutDettaglioMedie.collapseSmooth()
                        layoutGraficoGeneraleDettagliato.collapseSmooth()
                    }
                }

                // 6. Tab (Trimestre e Pentamestre)
                viewPager.adapter = ScreenSlidePagerAdapter(reportCard, showAllMaterie)

                // Transizione pagine: dissolvenza netta, così al bordo non si vede l'altro periodo
                viewPager.setPageTransformer { page, position ->
                    page.alpha = (1f - abs(position) * 1.8f).coerceIn(0f, 1f)
                }

                TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                    tab.text = if (position == 0) "Trimestre" else "Pentamestre"
                }.attach()

                // Selezione automatica del periodo in base alla data
                val currentMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
                viewPager.setCurrentItem(if (currentMonth in 1..8) 1 else 0, false)

                // Ingresso: il caricamento sfuma, le sezioni salgono in cascata
                layoutLoading.animate().cancel()
                layoutLoading.animate()
                    .alpha(0f)
                    .setStartDelay(0)
                    .setDuration(200)
                    .withEndAction {
                        layoutLoading.visibility = View.GONE
                        layoutLoading.alpha = 1f
                    }
                    .start()

                layoutDashboard.animate().cancel()
                layoutDashboard.alpha = 1f
                layoutDashboard.translationY = 0f
                findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.appBar).setExpanded(true, false)
                animaEntrataDashboard()
                layoutDashboard.visibility = View.VISIBLE
                btnLogout.show() // animazione di scala nativa del FAB

            } catch (e: Exception) {
                crossfade(layoutLoading, layoutLogin)
                // scuote i campi del form dopo la dissolvenza
                layoutLogin.postDelayed({
                    for (i in 2 until layoutLogin.childCount) layoutLogin.getChildAt(i).shake()
                }, 220)
                val errorMsg = e.localizedMessage ?: "Errore di connessione o autenticazione"
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Errore di login")
                    .setMessage(errorMsg)
                    .setPositiveButton("OK", null)
                    .show()
                android.util.Log.e("MAIN_ACTIVITY", "Login error", e)
            }
        }
    }

    // --- GRAFICO ANDAMENTO MEDIA ---
    private fun creaGraficoMediaGenerale(chart: LineChart, reportCard: ReportCard) {
        val tuttiConMateria = (reportCard.trimestre.valutazioni.entries + reportCard.pentamestre.valutazioni.entries)
            .flatMap { (materia, lista) -> lista.map { materia to it } }
            .sortedBy { chiaveData(it.second.date) } // ordine cronologico dell'anno scolastico

        if (tuttiConMateria.isEmpty()) {
            chart.clear()
            return
        }

        val entries = ArrayList<Entry>()
        var somma = 0.0
        for ((index, coppia) in tuttiConMateria.withIndex()) {
            somma += coppia.second.numeric
            entries.add(Entry(index.toFloat(), (somma / (index + 1)).toFloat()))
        }
        ultimoIndiceGrafico = entries.size - 1

        val blu = Color.parseColor("#0A84FF")
        val dataSet = LineDataSet(entries, "Media").apply {
            color = blu
            lineWidth = 3f
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            cubicIntensity = 0.15f
            setDrawFilled(true)
            fillDrawable = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#660A84FF"), Color.parseColor("#000A84FF"))
            )
            // indicatore al tocco: linea verticale + punto
            setDrawHighlightIndicators(true)
            setDrawHorizontalHighlightIndicator(false)
            highLightColor = Color.parseColor("#66FFFFFF")
            highlightLineWidth = 1.2f
            setDrawCircleHole(true)
            circleRadius = 5f
            circleHoleRadius = 2.5f
            setCircleColor(blu)
            circleHoleColor = Color.parseColor("#16161A")
        }

        val minV = entries.minOf { it.y }
        val maxV = entries.maxOf { it.y }
        // Intervallo sempre comprensivo della sufficienza (6) così la linea è visibile
        val assMin = minOf(Math.floor(minV - 0.5).toFloat(), 5f).coerceAtLeast(0f)
        val assMax = maxOf(Math.ceil(maxV + 0.5).toFloat(), 7f).coerceAtMost(10f)

        chart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            setNoDataText("Nessun voto")
            setScaleEnabled(false)
            setPinchZoom(false)
            isDoubleTapToZoomEnabled = false
            setDrawGridBackground(false)
            setExtraOffsets(0f, 12f, 8f, 0f)
            setHighlightPerDragEnabled(true)

            xAxis.isEnabled = false

            axisRight.isEnabled = false
            axisLeft.apply {
                textColor = Color.parseColor("#8E8E93")
                textSize = 10f
                axisMinimum = assMin
                axisMaximum = assMax
                granularity = 1f
                setDrawAxisLine(false)
                setDrawGridLines(true)
                gridColor = Color.parseColor("#232328")
                gridLineWidth = 1f
                setDrawLimitLinesBehindData(true)
                removeAllLimitLines()
                addLimitLine(LimitLine(6f, "Sufficienza").apply {
                    lineColor = Color.parseColor("#99FF453A")
                    lineWidth = 1.2f
                    enableDashedLine(14f, 9f, 0f)
                    labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
                    textColor = Color.parseColor("#FF453A")
                    textSize = 9f
                })
            }

            val marker = VotoMarker(this@MainActivity, tuttiConMateria)
            marker.chartView = this
            this.marker = marker
            invalidate()
        }
    }

    private fun aggiornaSezioneInsufficienze(txtConteggio: TextView, container: LinearLayout, reportCard: ReportCard) {
        val currentMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
        val periodoCorrente = if (currentMonth in 1..8) reportCard.pentamestre else reportCard.trimestre

        val materieInsufficienti = periodoCorrente.medie.filter { it.value.ponderata < 6.0 && it.value.ponderata > 0.0 }
            .toList()
            .sortedBy { it.second.ponderata }

        val colore = Color.parseColor(if (materieInsufficienti.isEmpty()) "#30D158" else "#FF453A")
        txtConteggio.setTextColor(Color.parseColor("#6E6E73"))
        txtConteggio.animateNumber(0f, materieInsufficienti.size.toFloat(), 600, 450) {
            "MATERIE INSUFFICIENTI: ${it.toInt()}"
        }
        txtConteggio.animateColorTo(colore, 600, 450)

        container.removeAllViews()
        if (materieInsufficienti.isNotEmpty()) {
            for ((i, entry) in materieInsufficienti.withIndex()) {
                val (materia, medie) = entry
                val txtBolla = TextView(this).apply {
                    text = "${materia.take(8).uppercase()} (${String.format("%.2f", medie.ponderata)})"
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(20, 10, 20, 10)

                    val backgroundBolla = resources.getDrawable(R.drawable.bg_voto_bolla, null).mutate() as GradientDrawable
                    backgroundBolla.setColor(Color.parseColor("#321C1C"))
                    setTextColor(Color.parseColor("#FF453A"))
                    background = backgroundBolla

                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 12, 0)
                    }
                }
                container.addView(txtBolla)
                txtBolla.popIn(delay = 550L + i * 80L)
            }
        }
    }

    // --- ADAPTER INTERNO PER GESTIRE I DUE FOGLI DELLE TAB ---
    private inner class ScreenSlidePagerAdapter(val reportCard: ReportCard, val showAll: Boolean) : androidx.viewpager2.adapter.FragmentStateAdapter(this) {
        override fun getItemCount(): Int = 2
        override fun getItemId(position: Int): Long {
            val filterId = if (showAll) 100L else 200L
            return position.toLong() + filterId
        }
        override fun containsItem(itemId: Long): Boolean {
            val filterId = if (showAll) 100L else 200L
            return itemId == 0L + filterId || itemId == 1L + filterId
        }
        override fun createFragment(position: Int): androidx.fragment.app.Fragment {
            val periodoData = if (position == 0) reportCard.trimestre else reportCard.pentamestre
            return TabPeriodoFragment(periodoData, showAll, ordineDecrescente, position == 1)
        }
    }

    // --- COSTRUTTORE GRAFICO PER LA SINGOLA MATERIA ---
    class TabPeriodoFragment(
        private val periodo: PeriodoScuola,
        private val showAll: Boolean,
        decrescente: Boolean = true,
        private val isPentamestre: Boolean = false
    ) : androidx.fragment.app.Fragment() {
        private var decrescenteCorrente = decrescente
        private var listaCard: LinearLayout? = null

        override fun onDestroyView() {
            listaCard = null
            super.onDestroyView()
        }

        // Prima le materie con almeno un voto, ordinate per media (alta o bassa);
        // in fondo quelle senza voti, in ordine alfabetico.
        private fun ordinaMaterie(decr: Boolean): List<String> {
            fun mediaDi(m: String) = periodo.medie[m]?.ponderata ?: 0.0
            val conVoti = periodo.valutazioni.keys.filter { periodo.valutazioni[it]?.isNotEmpty() == true }
            val senzaVoti = periodo.valutazioni.keys.filter { periodo.valutazioni[it].isNullOrEmpty() }.sorted()
            val conVotiOrdinate = if (decr)
                conVoti.sortedWith(compareByDescending<String> { mediaDi(it) }.thenBy { it })
            else
                conVoti.sortedWith(compareBy<String> { mediaDi(it) }.thenBy { it })
            return conVotiOrdinate + senzaVoti
        }

        /**
         * Riordina le card esistenti. Se la pagina è visibile le card scivolano dalla vecchia
         * alla nuova posizione (FLIP); se è fuori schermo vengono solo riordinate.
         */
        fun riordina(nuovoDecrescente: Boolean) {
            if (nuovoDecrescente == decrescenteCorrente) return
            decrescenteCorrente = nuovoDecrescente
            val lista = listaCard ?: return
            val ordine = ordinaMaterie(nuovoDecrescente)

            val card = (0 until lista.childCount).map { lista.getChildAt(it) }.filter { it.tag is String }
            if (card.size < 2) return

            val visibile = lista.getGlobalVisibleRect(android.graphics.Rect())
            card.forEach { it.animate().cancel(); it.alpha = 1f } // alpha: se la cascata d'ingresso era ancora in corso
            val prima = card.associateWith { it.top + it.translationY }

            val nuovoOrdine = card.sortedBy { ordine.indexOf(it.tag as String) }
            lista.removeAllViews()
            nuovoOrdine.forEach { lista.addView(it) }
            if (!visibile) {
                card.forEach { it.translationY = 0f }
                return
            }

            lista.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    lista.viewTreeObserver.removeOnPreDrawListener(this)
                    nuovoOrdine.forEach { c ->
                        val delta = (prima[c] ?: c.top.toFloat()) - c.top
                        c.translationY = delta
                        if (delta != 0f) {
                            c.animate()
                                .translationY(0f)
                                .setStartDelay(0)
                                .setDuration(480)
                                .setInterpolator(Anim.emphasized)
                                .start()
                        }
                    }
                    return true
                }
            })
        }
        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View? {
            val v = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setPadding(32, 16, 32, 16)
            }

            val cal = java.util.Calendar.getInstance()
            val month = cal.get(java.util.Calendar.MONTH) + 1
            val day = cal.get(java.util.Calendar.DAY_OF_MONTH)

            val nonAncoraIniziato = if (!isPentamestre) {
                month < 9 || (month == 9 && day < 10)
            } else {
                month >= 9 || (month == 1 && day < 2)
            }

            if (nonAncoraIniziato) {
                val txtNonIniziato = TextView(context).apply {
                    text = "Il periodo non è ancora iniziato"
                    textSize = 15f
                    setTextColor(Color.parseColor("#0A84FF"))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(32, 120, 32, 120)
                }
                v.addView(txtNonIniziato)
                return v
            }

            // NestedScrollView: fa scorrere via anche la parte alta (AppBarLayout) insieme alla lista
            val scroll = androidx.core.widget.NestedScrollView(requireContext()).apply {
                isFillViewport = true
                clipToPadding = false
            }
            val listContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // spazio in fondo per non coprire l'ultima card con il pulsante logout
                setPadding(0, 0, 0, (96 * resources.displayMetrics.density).toInt())
            }

            val materieOrdinate = ordinaMaterie(decrescenteCorrente)
            listaCard = listContainer
            var indiceAnimazione = 0

            for (materia in materieOrdinate) {
                val voti = periodo.valutazioni[materia] ?: mutableListOf()
                val mediaInfo = periodo.medie[materia] ?: MedieMateria(0.0, 0.0, 0)

                // Quando showAll è false (modalità "Solo Insufficienti"), nascondi le materie sufficienti
                if (!showAll && (voti.isEmpty() || mediaInfo.ponderata >= 6.0)) continue

                val cardMateria = layoutInflater.inflate(R.layout.item_materia, listContainer, false)
                val lblNome = cardMateria.findViewById<TextView>(R.id.lblNomeMateria)
                val lblMedia = cardMateria.findViewById<TextView>(R.id.lblMediaMateriaPeriodo)
                val imgSimula = cardMateria.findViewById<ImageView>(R.id.imgSimula)
                val votiContainer = cardMateria.findViewById<LinearLayout>(R.id.votiContainer)
                val layoutEspandibile = cardMateria.findViewById<LinearLayout>(R.id.layoutGraficoEspandibile)
                val txtPrevisione = cardMateria.findViewById<TextView>(R.id.txtPrevisioneVoto)
                val inputSimulazione = cardMateria.findViewById<EditText>(R.id.inputSimulazioneVoto)
                val txtRisultatoSimulazione = cardMateria.findViewById<TextView>(R.id.txtRisultatoSimulazione)

                // Ritardo a cascata per questa card (limitato per non far aspettare troppo)
                val ritardoCard = 60L + minOf(indiceAnimazione, 7) * 55L
                indiceAnimazione++

                lblNome.text = materia.uppercase()

                val lblTrend = cardMateria.findViewById<TextView>(R.id.lblTrendMedia)
                if (voti.isNotEmpty() && mediaInfo.ponderata > 0.0) {
                    val coloreMedia = Color.parseColor(if (mediaInfo.ponderata >= 6.0) "#30D158" else "#FF453A")
                    lblMedia.setTextColor(Color.WHITE)
                    lblMedia.animateNumber(0f, mediaInfo.ponderata.toFloat(), 700, ritardoCard + 150) { String.format("%.2f", it) }
                    lblMedia.animateColorTo(coloreMedia, 600, ritardoCard + 150)
                    cardMateria.findViewById<ProgressBar>(R.id.progressMedia).apply {
                        progressTintList = ColorStateList.valueOf(coloreMedia)
                        ObjectAnimator.ofInt(this, "progress", 0, (mediaInfo.ponderata * 100).toInt()).apply {
                            duration = 800
                            startDelay = ritardoCard + 150
                            interpolator = Anim.emphasized
                            start()
                        }
                    }

                    val votiOrdinati = voti.sortedBy { chiaveData(it.date) }
                    if (votiOrdinati.isNotEmpty()) {
                        val mediaTotale = mediaInfo.ponderata
                        val trend: Int
                        if (votiOrdinati.size >= 2) {
                            val mediaSenzaUltimo = (activity as? MainActivity)?.calcolaMediaPonderata(votiOrdinati.dropLast(1)) ?: 0.0
                            trend = when {
                                mediaTotale > mediaSenzaUltimo -> 1
                                mediaTotale < mediaSenzaUltimo -> -1
                                else -> 0
                            }
                        } else {
                            trend = if (votiOrdinati.first().numeric >= 6.0) 1 else -1
                        }

                        if (trend != 0) {
                            lblTrend.text = if (trend > 0) "▲" else "▼"
                            lblTrend.setTextColor(Color.parseColor(if (trend > 0) "#30D158" else "#FF453A"))
                            lblTrend.visibility = View.VISIBLE
                        } else {
                            lblTrend.visibility = View.GONE
                        }
                    } else {
                        lblTrend.visibility = View.GONE
                    }
                } else {
                    lblMedia.text = "--"
                    lblMedia.setTextColor(Color.parseColor("#8E8E93"))
                    lblTrend.visibility = View.GONE
                }

                // Calcolo previsione voto per la sufficienza
                val numeroVoti = voti.size
                val sommaVotiAttuale = voti.sumOf { it.numeric }

                if (voti.isNotEmpty() && mediaInfo.ponderata < 6.0 && mediaInfo.ponderata > 0.0) {
                    val votoNecessario = (6.0 * (numeroVoti + 1)) - sommaVotiAttuale

                    if (votoNecessario > 10.0) {
                        val votoNecessarioDue = (6.0 * (numeroVoti + 2) - sommaVotiAttuale) / 2.0
                        txtPrevisione.text = String.format("Ti servono due voti da %.1f per arrivare alla sufficienza.", votoNecessarioDue)
                    } else {
                        txtPrevisione.text = String.format("Ti serve un %.1f per arrivare alla sufficienza.", if (votoNecessario < 1) 1.0 else votoNecessario)
                    }
                } else if (voti.isNotEmpty() && mediaInfo.ponderata >= 6.0) {
                    txtPrevisione.text = "Sei già sopra la sufficienza! Continua così."
                } else {
                    txtPrevisione.text = "Nessun voto presente per questa materia in questo periodo."
                }

                // Simulatore: il risultato "scorre" dal valore precedente al nuovo
                var simAnim: ValueAnimator? = null
                var simUltimo = if (voti.isNotEmpty()) (sommaVotiAttuale / numeroVoti).toFloat() else 0f

                fun updateSimulazione() {
                    val votoInputString = inputSimulazione.text.toString().replace(",", ".")
                    val votoInput = votoInputString.toDoubleOrNull()

                    simAnim?.cancel()
                    if (votoInput != null && votoInput in 1.0..10.0) {
                        val nuovaMedia = if (voti.isNotEmpty()) (sommaVotiAttuale + votoInput) / (numeroVoti + 1) else votoInput

                        simAnim = txtRisultatoSimulazione.animateNumber(simUltimo, nuovaMedia.toFloat(), 380) {
                            String.format("NUOVA MEDIA: %.2f", it)
                        }
                        txtRisultatoSimulazione.animateColorTo(
                            Color.parseColor(if (nuovaMedia >= 6.0) "#30D158" else "#FF453A"), 300
                        )
                        simUltimo = nuovaMedia.toFloat()
                    } else {
                        txtRisultatoSimulazione.text = "NUOVA MEDIA: --"
                        txtRisultatoSimulazione.animateColorTo(Color.parseColor("#007AFF"), 300)
                        simUltimo = if (voti.isNotEmpty()) (sommaVotiAttuale / numeroVoti).toFloat() else 0f
                    }
                }

                inputSimulazione.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateSimulazione() }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })

                // CREAZIONE BOLLE COLORATE E CLICCABILI
                if (voti.isEmpty()) {
                    val txtNessunVoto = TextView(context).apply {
                        text = "Nessun voto presente"
                        textSize = 13f
                        setTextColor(Color.parseColor("#8E8E93"))
                        setPadding(10, 8, 10, 8)
                    }
                    votiContainer.addView(txtNessunVoto)
                } else {
                    for ((j, voto) in voti.withIndex()) {
                        val txtBolla = TextView(context).apply {
                            text = voto.grade
                            textSize = 14f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            gravity = Gravity.CENTER
                            setPadding(22, 12, 22, 12)
                            isClickable = true
                            isFocusable = true

                            val backgroundBolla = resources.getDrawable(R.drawable.bg_voto_bolla, null).mutate() as GradientDrawable

                            when {
                                voto.numeric >= 6.0 -> {
                                    backgroundBolla.setColor(Color.parseColor("#1C3224"))
                                    setTextColor(Color.parseColor("#30D158"))
                                }
                                voto.numeric >= 5.0 -> {
                                    backgroundBolla.setColor(Color.parseColor("#32281C"))
                                    setTextColor(Color.parseColor("#FF9F0A"))
                                }
                                else -> {
                                    backgroundBolla.setColor(Color.parseColor("#321C1C"))
                                    setTextColor(Color.parseColor("#FF453A"))
                                }
                            }
                            background = backgroundBolla

                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                setMargins(0, 0, 14, 0)
                            }

                            addPressScale(0.86f)

                            // CLICK EVENT SUL VOTO PER MOSTRARE IL DIALOG PERSONALIZZATO
                            setOnClickListener {
                                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                val dialogView = layoutInflater.inflate(R.layout.dialog_voto_dettaglio, null)

                                val txtBollaDialog = dialogView.findViewById<TextView>(R.id.dialogTxtVotoBolla)
                                val txtMateriaDialog = dialogView.findViewById<TextView>(R.id.dialogTxtMateria)
                                val txtTipoDialog = dialogView.findViewById<TextView>(R.id.dialogTxtTipoValutazione)
                                val txtDataDialog = dialogView.findViewById<TextView>(R.id.dialogTxtData)
                                val txtPesoDialog = dialogView.findViewById<TextView>(R.id.dialogTxtPeso)
                                val layoutNoteDialog = dialogView.findViewById<LinearLayout>(R.id.dialogLayoutNote)
                                val txtNoteDialog = dialogView.findViewById<TextView>(R.id.dialogTxtNote)
                                val btnChiudiDialog = dialogView.findViewById<Button>(R.id.dialogBtnChiudi)

                                txtBollaDialog.text = voto.grade
                                txtMateriaDialog.text = materia.uppercase()
                                txtTipoDialog.text = if (voto.type.isNotEmpty()) voto.type else "Valutazione"
                                txtDataDialog.text = formatData(voto.date)

                                // Calcolo corretto del peso percentuale (es. 1.0 -> 100%, 100.0 -> 100%, 0.5 -> 50%)
                                val pesoPercentuale = if (voto.weight > 1.5) Math.round(voto.weight) else Math.round(voto.weight * 100)
                                txtPesoDialog.text = "$pesoPercentuale%"

                                val bgBollaDialog = resources.getDrawable(R.drawable.bg_voto_bolla, null).mutate() as GradientDrawable
                                when {
                                    voto.numeric >= 6.0 -> {
                                        bgBollaDialog.setColor(Color.parseColor("#1C3224"))
                                        txtBollaDialog.setTextColor(Color.parseColor("#30D158"))
                                    }
                                    voto.numeric >= 5.0 -> {
                                        bgBollaDialog.setColor(Color.parseColor("#32281C"))
                                        txtBollaDialog.setTextColor(Color.parseColor("#FF9F0A"))
                                    }
                                    else -> {
                                        bgBollaDialog.setColor(Color.parseColor("#321C1C"))
                                        txtBollaDialog.setTextColor(Color.parseColor("#FF453A"))
                                    }
                                }
                                txtBollaDialog.background = bgBollaDialog

                                if (voto.description.isNotEmpty()) {
                                    layoutNoteDialog.visibility = View.VISIBLE
                                    txtNoteDialog.text = voto.description
                                } else {
                                    layoutNoteDialog.visibility = View.GONE
                                }

                                val dialog = android.app.Dialog(requireContext())
                                dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                                dialog.setContentView(dialogView)
                                dialog.setCancelable(false) // chiusura gestita da noi, sempre animata
                                dialog.window?.apply {
                                    // Finestra trasparente a tutto schermo, contenuto centrato: nessuno sfondo residuo
                                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                                    setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                    setGravity(Gravity.CENTER)
                                    addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                                    setDimAmount(0f)
                                }

                                val card = dialogView.findViewById<View>(R.id.dialogCard)
                                val dimMax = 0.65f
                                var chiuso = false

                                fun chiudi() {
                                    if (chiuso) return
                                    chiuso = true
                                    card.animate().cancel()
                                    card.animate()
                                        .alpha(0f).scaleX(0.92f).scaleY(0.92f).translationY(16f * resources.displayMetrics.density)
                                        .setStartDelay(0)
                                        .setDuration(170)
                                        .setInterpolator(android.view.animation.AccelerateInterpolator())
                                        .withEndAction { dialog.dismiss() }
                                        .start()
                                    ValueAnimator.ofFloat(dimMax, 0f).apply {
                                        duration = 170
                                        addUpdateListener { dialog.window?.setDimAmount(it.animatedValue as Float) }
                                        start()
                                    }
                                }

                                btnChiudiDialog.setOnClickListener { chiudi() }
                                dialogView.setOnClickListener { chiudi() } // tap fuori dalla card
                                dialog.setOnKeyListener { _, keyCode, event ->
                                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                                        chiudi(); true
                                    } else false
                                }

                                dialog.show()

                                // Entrata animata
                                val d = resources.displayMetrics.density
                                card.alpha = 0f
                                card.scaleX = 0.88f
                                card.scaleY = 0.88f
                                card.translationY = 28f * d
                                card.animate()
                                    .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                                    .setStartDelay(0)
                                    .setDuration(380)
                                    .setInterpolator(Anim.emphasized)
                                    .start()
                                ValueAnimator.ofFloat(0f, dimMax).apply {
                                    duration = 300
                                    addUpdateListener { dialog.window?.setDimAmount(it.animatedValue as Float) }
                                    start()
                                }
                                txtBollaDialog.popIn(delay = 140L, durationMs = 520L, overshoot = 3f)
                                txtMateriaDialog.revealUp(delay = 180L, distanceDp = 10f, durationMs = 380L)
                                txtTipoDialog.revealUp(delay = 230L, distanceDp = 10f, durationMs = 380L)
                            }
                        }
                        votiContainer.addView(txtBolla)
                        txtBolla.popIn(delay = ritardoCard + 200L + minOf(j, 10) * 40L)
                    }
                }

                // CLICK SULLA CARD: espansione fluida del simulatore, il bottone si illumina
                cardMateria.isClickable = true
                cardMateria.addPressScale(0.975f)
                cardMateria.setOnClickListener {
                    val apri = !layoutEspandibile.isOpenTarget()
                    cardMateria.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)

                    val iconaDa = if (apri) Color.parseColor("#8E8E93") else Color.parseColor("#0A84FF")
                    val iconaA = if (apri) Color.parseColor("#0A84FF") else Color.parseColor("#8E8E93")
                    val fondoDa = if (apri) Color.parseColor("#232328") else Color.parseColor("#1C2738")
                    val fondoA = if (apri) Color.parseColor("#1C2738") else Color.parseColor("#232328")
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 300
                        addUpdateListener {
                            val f = it.animatedFraction
                            imgSimula.setColorFilter(ArgbEvaluator().evaluate(f, iconaDa, iconaA) as Int)
                            imgSimula.backgroundTintList = ColorStateList.valueOf(ArgbEvaluator().evaluate(f, fondoDa, fondoA) as Int)
                        }
                        start()
                    }
                    imgSimula.bump(0.8f)

                    if (apri) layoutEspandibile.expandSmooth() else layoutEspandibile.collapseSmooth()
                }

                cardMateria.tag = materia
                listContainer.addView(cardMateria)
                cardMateria.revealUp(delay = ritardoCard)
            }

            if (listContainer.childCount == 0 && !showAll) {
                val txtVuoto = TextView(context).apply {
                    text = "Nessuna insufficienza in questo periodo"
                    textSize = 15f
                    setTextColor(Color.parseColor("#30D158"))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(32, 120, 32, 120)
                }
                listContainer.addView(txtVuoto)
            }

            scroll.addView(listContainer)
            v.addView(scroll)
            return v
        }
    }
}
