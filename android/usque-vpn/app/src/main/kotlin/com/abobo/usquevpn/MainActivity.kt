package com.abobo.usquevpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import usqueandroid.Usqueandroid
import java.util.Locale

data class AppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable
)

class MainActivity : Activity() {

    companion object {
        private const val VPN_REQUEST_CODE = 1001
        private const val PREFS_NAME = "UsqueVpnPrefs"
        private const val KEY_SNI = "sni"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_PROXY_MODE = "proxy_mode"
        private const val KEY_ALLOWED_APPS = "allowed_apps"
        private const val KEY_LANGUAGE = "language"
        const val MODE_GLOBAL = "global"
        const val MODE_PER_APP = "per_app"
        const val LANG_ZH = "zh"
        const val LANG_EN = "en"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var connectButton: Button
    private lateinit var ipInfoText: TextView
    private lateinit var settingsButton: Button
    private lateinit var sniText: TextView
    private lateinit var endpointText: TextView
    private lateinit var modeText: TextView

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lang = prefs.getString(KEY_LANGUAGE, LANG_ZH) ?: LANG_ZH
        super.attachBaseContext(updateLocale(newBase, lang))
    }

    private fun updateLocale(context: Context, lang: String): Context {
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        statusText = findViewById(R.id.status_text)
        statusDot = findViewById(R.id.status_dot)
        connectButton = findViewById(R.id.connect_button)
        ipInfoText = findViewById(R.id.ip_info_text)
        settingsButton = findViewById(R.id.settings_button)
        sniText = findViewById(R.id.sni_text)
        endpointText = findViewById(R.id.endpoint_text)
        modeText = findViewById(R.id.mode_text)

        loadSavedSettings()

        connectButton.setOnClickListener {
            if (UsqueVpnService.isRunning) stopVpn() else startVpn()
        }

        settingsButton.setOnClickListener { showSettingsDialog() }
        findViewById<Button>(R.id.mode_button).setOnClickListener { showModeDialog() }
        findViewById<Button>(R.id.app_picker_button).setOnClickListener { showAppPickerDialog() }
        findViewById<Button>(R.id.language_button).setOnClickListener { showLanguageDialog() }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun loadSavedSettings() {
        val savedSni = prefs.getString(KEY_SNI, "www.visa.cn") ?: "www.visa.cn"
        Usqueandroid.setSNI(savedSni)

        // Migrate old default endpoint
        val savedEndpoint = prefs.getString(KEY_ENDPOINT, "") ?: ""
        if (savedEndpoint.contains("162.159.198.1") || savedEndpoint == "162.159.198.1:443") {
            prefs.edit().putString(KEY_ENDPOINT, "162.159.198.2:500").apply()
            Usqueandroid.setEndpoint("162.159.198.2:500")
        }

        val endpoint = prefs.getString(KEY_ENDPOINT, "") ?: ""
        if (endpoint.isNotEmpty()) {
            Usqueandroid.setEndpoint(endpoint)
        }
    }

    private fun showLanguageDialog() {
        val languages = arrayOf(
            getString(R.string.language_chinese),
            getString(R.string.language_english)
        )
        val langCodes = arrayOf(LANG_ZH, LANG_EN)
        val currentLang = prefs.getString(KEY_LANGUAGE, LANG_ZH) ?: LANG_ZH
        val checkedIndex = langCodes.indexOf(currentLang).coerceAtLeast(0)

        AlertDialog.Builder(this, R.style.Theme_UsqueVpn_Dialog)
            .setTitle(R.string.language_title)
            .setSingleChoiceItems(languages, checkedIndex) { dialog, which ->
                val newLang = langCodes[which]
                if (newLang != currentLang) {
                    prefs.edit().putString(KEY_LANGUAGE, newLang).apply()
                    val intent = intent
                    finish()
                    startActivity(intent)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
    }

    private fun showModeDialog() {
        val currentMode = prefs.getString(KEY_PROXY_MODE, MODE_GLOBAL) ?: MODE_GLOBAL
        val modes = arrayOf(
            getString(R.string.mode_global),
            getString(R.string.mode_per_app)
        )
        val checkedIndex = if (currentMode == MODE_GLOBAL) 0 else 1

        AlertDialog.Builder(this, R.style.Theme_UsqueVpn_Dialog)
            .setTitle(R.string.mode_title)
            .setSingleChoiceItems(modes, checkedIndex) { dialog, which ->
                val newMode = if (which == 0) MODE_GLOBAL else MODE_PER_APP
                prefs.edit().putString(KEY_PROXY_MODE, newMode).apply()
                if (newMode == MODE_PER_APP) {
                    val allowedApps = prefs.getStringSet(KEY_ALLOWED_APPS, emptySet()) ?: emptySet()
                    if (allowedApps.isEmpty()) {
                        dialog.dismiss()
                        showAppPickerDialog()
                        return@setSingleChoiceItems
                    }
                }
                Toast.makeText(this,
                    if (which == 0) getString(R.string.mode_global_set)
                    else getString(R.string.mode_per_app_set),
                    Toast.LENGTH_SHORT
                ).show()
                updateUI()
                dialog.dismiss()
                if (UsqueVpnService.isRunning) {
                    UsqueVpnService.restart(this)
                    connectButton.postDelayed({ updateUI() }, 2000)
                }
            }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
    }

    /**
     * Load all installed user apps with proper names.
     * Uses ApplicationInfo.loadLabel() for reliable name resolution.
     */
    private fun loadAllApps(): List<AppItem> {
        val pm = packageManager
        val apps = mutableListOf<AppItem>()
        val seen = mutableSetOf<String>()

        // Method 1: Get all installed packages (most reliable)
        val installedPackages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
        for (pkg in installedPackages) {
            val appInfo = pkg.applicationInfo ?: continue
            val pkgName = appInfo.packageName

            // Skip self
            if (pkgName == packageName) continue
            // Skip already seen
            if (pkgName in seen) continue
            // Skip system apps (but keep updated system apps)
            val isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val isUpdated = appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            if (isSystem && !isUpdated) continue

            seen.add(pkgName)

            // Get app name - try multiple methods
            val appName = getAppName(pm, appInfo, pkgName)

            try {
                apps.add(AppItem(
                    name = appName,
                    packageName = pkgName,
                    icon = appInfo.loadIcon(pm)
                ))
            } catch (e: Exception) {
                // Skip apps that fail to load icon
            }
        }

        return apps.sortedBy { it.name.lowercase() }
    }

    /**
     * Get human-readable app name using multiple fallback methods.
     */
    private fun getAppName(pm: PackageManager, appInfo: ApplicationInfo, pkgName: String): String {
        // Method 1: ApplicationInfo.loadLabel()
        try {
            val label = appInfo.loadLabel(pm).toString().trim()
            if (label.isNotEmpty() && label != pkgName && !label.contains(".")) {
                return label
            }
        } catch (_: Exception) {}

        // Method 2: PackageInfo.applicationInfo.loadLabel()
        try {
            val pkgInfo = pm.getPackageInfo(pkgName, 0)
            val label = pkgInfo.applicationInfo.loadLabel(pm).toString().trim()
            if (label.isNotEmpty() && label != pkgName) {
                return label
            }
        } catch (_: Exception) {}

        // Method 3: Launch intent resolver
        try {
            val launchIntent = pm.getLaunchIntentForPackage(pkgName)
            if (launchIntent != null) {
                val resolveInfo = pm.resolveActivity(launchIntent, 0)
                if (resolveInfo != null) {
                    val label = resolveInfo.loadLabel(pm).toString().trim()
                    if (label.isNotEmpty() && label != pkgName) {
                        return label
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback: Clean up package name
        return pkgName.substringAfterLast(".").replaceFirstChar { it.uppercase() }
    }

    private fun showAppPickerDialog() {
        val allApps = loadAllApps()
        val allowedApps = prefs.getStringSet(KEY_ALLOWED_APPS, emptySet())?.toMutableSet() ?: mutableSetOf()

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_app_picker, null)
        val searchInput = dialogView.findViewById<EditText>(R.id.search_input)
        val listView = dialogView.findViewById<ListView>(R.id.app_list)
        val selectAllBtn = dialogView.findViewById<Button>(R.id.select_all_btn)
        val deselectAllBtn = dialogView.findViewById<Button>(R.id.deselect_all_btn)
        val selectedCountText = dialogView.findViewById<TextView>(R.id.selected_count)

        val checkedMap = mutableMapOf<String, Boolean>()
        for (app in allApps) {
            checkedMap[app.packageName] = allowedApps.contains(app.packageName)
        }

        fun updateSelectedCount() {
            val count = checkedMap.values.count { it }
            selectedCountText.text = getString(R.string.app_picker_selected, count)
        }

        fun buildFilteredList(query: String): List<AppItem> {
            if (query.isEmpty()) return allApps
            val q = query.lowercase()
            return allApps.filter {
                it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }

        var currentList = allApps

        fun createAdapter(list: List<AppItem>): BaseAdapter {
            return object : BaseAdapter() {
                override fun getCount() = list.size
                override fun getItem(pos: Int) = list[pos]
                override fun getItemId(pos: Int) = pos.toLong()
                override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                    val view = convertView ?: LayoutInflater.from(this@MainActivity)
                        .inflate(R.layout.item_app, parent, false)
                    val app = list[pos]
                    view.findViewById<ImageView>(R.id.app_icon).setImageDrawable(app.icon)
                    view.findViewById<TextView>(R.id.app_name).text = app.name
                    view.findViewById<TextView>(R.id.app_package).text = app.packageName
                    val cb = view.findViewById<CheckBox>(R.id.app_checkbox)
                    cb.setOnCheckedChangeListener(null)
                    cb.isChecked = checkedMap[app.packageName] == true
                    cb.setOnCheckedChangeListener { _, isChecked ->
                        checkedMap[app.packageName] = isChecked
                        updateSelectedCount()
                    }
                    view.setOnClickListener { cb.toggle() }
                    return view
                }
            }
        }

        listView.adapter = createAdapter(currentList)
        updateSelectedCount()

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentList = buildFilteredList(s?.toString() ?: "")
                listView.adapter = createAdapter(currentList)
            }
        })

        selectAllBtn.setOnClickListener {
            for (app in currentList) checkedMap[app.packageName] = true
            listView.adapter = createAdapter(currentList)
            updateSelectedCount()
        }
        deselectAllBtn.setOnClickListener {
            for (app in currentList) checkedMap[app.packageName] = false
            listView.adapter = createAdapter(currentList)
            updateSelectedCount()
        }

        val dialog = AlertDialog.Builder(this, R.style.Theme_UsqueVpn_Dialog)
            .setTitle(R.string.app_picker_title)
            .setView(dialogView)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                val selected = checkedMap.filter { it.value }.keys.toSet()
                prefs.edit().putStringSet(KEY_ALLOWED_APPS, selected).apply()
                Toast.makeText(this, getString(R.string.app_picker_saved, selected.size), Toast.LENGTH_SHORT).show()
                updateUI()
                if (UsqueVpnService.isRunning) {
                    UsqueVpnService.restart(this)
                    connectButton.postDelayed({ updateUI() }, 2000)
                }
            }
            .setNegativeButton(R.string.settings_cancel, null)
            .create()
        dialog.show()
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.85).toInt()
        )
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val sniInput = dialogView.findViewById<EditText>(R.id.sni_input)
        val endpointInput = dialogView.findViewById<EditText>(R.id.endpoint_input)
        val configPath = "${filesDir.absolutePath}/config.json"

        sniInput.setText(prefs.getString(KEY_SNI, Usqueandroid.getSNI()))
        val currentEndpoint = prefs.getString(KEY_ENDPOINT, "") ?: ""
        if (currentEndpoint.isNotEmpty()) {
            endpointInput.setText(currentEndpoint)
        } else {
            endpointInput.setText(Usqueandroid.getDefaultEndpoint(configPath))
        }

        AlertDialog.Builder(this, R.style.Theme_UsqueVpn_Dialog)
            .setTitle(R.string.settings_title)
            .setView(dialogView)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                val sni = sniInput.text.toString()
                val endpoint = endpointInput.text.toString()
                prefs.edit()
                    .putString(KEY_SNI, sni)
                    .putString(KEY_ENDPOINT, endpoint)
                    .apply()
                Usqueandroid.setSNI(sni)
                Usqueandroid.setEndpoint(endpoint)
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                updateUI()
            }
            .setNegativeButton(R.string.settings_cancel, null)
            .setNeutralButton(R.string.settings_reset) { _, _ ->
                prefs.edit()
                    .putString(KEY_SNI, "www.visa.cn")
                    .putString(KEY_ENDPOINT, "162.159.198.2:500")
                    .apply()
                Usqueandroid.setSNI("www.visa.cn")
                Usqueandroid.setEndpoint("162.159.198.2:500")
                Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show()
                updateUI()
            }
            .show()
    }

    private fun startVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            onVpnPermissionGranted()
        }
    }

    private fun stopVpn() {
        UsqueVpnService.stop()
        val intent = Intent(this, UsqueVpnService::class.java)
        intent.action = UsqueVpnService.ACTION_DISCONNECT
        startService(intent)
        connectButton.postDelayed({ updateUI() }, 1000)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                onVpnPermissionGranted()
            } else {
                Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onVpnPermissionGranted() {
        val intent = Intent(this, UsqueVpnService::class.java)
        startService(intent)
        connectButton.postDelayed({ updateUI() }, 1500)
    }

    private fun updateUI() {
        val configPath = "${filesDir.absolutePath}/config.json"

        if (UsqueVpnService.isRunning) {
            statusText.text = getString(R.string.status_connected)
            statusText.setTextColor(getColor(R.color.success))
            statusDot.setBackgroundResource(R.color.success)
            connectButton.text = getString(R.string.btn_disconnect)
            settingsButton.isEnabled = false
        } else {
            statusText.text = getString(R.string.status_disconnected)
            statusText.setTextColor(getColor(R.color.error))
            statusDot.setBackgroundResource(R.color.error)
            connectButton.text = getString(R.string.btn_connect)
            settingsButton.isEnabled = true
        }

        if (Usqueandroid.isRegistered(configPath)) {
            val ipv4 = Usqueandroid.getAssignedIPv4(configPath)
            val ipv6 = Usqueandroid.getAssignedIPv6(configPath)
            ipInfoText.text = "IPv4: $ipv4\nIPv6: $ipv6"
        } else {
            ipInfoText.text = getString(R.string.label_not_registered)
        }

        val currentSni = prefs.getString(KEY_SNI, Usqueandroid.getSNI()) ?: "www.visa.cn"
        sniText.text = "SNI: $currentSni"

        val currentEndpoint = prefs.getString(KEY_ENDPOINT, "") ?: ""
        val displayEndpoint = if (currentEndpoint.isNotEmpty()) {
            currentEndpoint
        } else {
            Usqueandroid.getDefaultEndpoint(configPath)
        }
        endpointText.text = "Endpoint: $displayEndpoint"

        val mode = prefs.getString(KEY_PROXY_MODE, MODE_GLOBAL) ?: MODE_GLOBAL
        if (mode == MODE_GLOBAL) {
            modeText.text = getString(R.string.mode_global_display)
            modeText.setTextColor(getColor(R.color.accent_secondary))
        } else {
            val count = (prefs.getStringSet(KEY_ALLOWED_APPS, emptySet()) ?: emptySet()).size
            modeText.text = getString(R.string.mode_per_app_display, count)
            modeText.setTextColor(getColor(R.color.accent_primary))
        }
    }
}
