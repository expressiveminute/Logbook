package com.highfly.logbook

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentProfileBinding
import java.io.File

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null

    private val binding get() = _binding!!

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val csv = LogbookCsv.toCsv(LogbookRepository.getEntries())
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                out.write(csv.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(requireContext(), R.string.export_success, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.export_error, Toast.LENGTH_SHORT).show()
        }
    }

    private val avatarPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        saveAvatarFromUri(uri)
    }

    private val crashLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val content = CrashLogger.currentContent(requireContext())
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(requireContext(), R.string.crash_log_success, Toast.LENGTH_SHORT).show()
            showCrashLogContent(content)
        } catch (e: Exception) {
            CrashLogger.logError("CrashLogExport", "Export fehlgeschlagen", e)
            Toast.makeText(requireContext(), R.string.crash_log_error, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateModeTiles(Settings.isDarkMode(requireContext()))
        updateRoleTiles(Settings.getRole(requireContext()))
        updateCrewFunctionTiles(Settings.getCrewFunction(requireContext()))
        updateLanguageTiles()
        updateAccentTiles(Settings.getAccentColor(requireContext()))
        updateClassSchemeTiles(Settings.getClassScheme(requireContext()))
        updateMapOrientationTiles(Settings.getMapOrientation(requireContext()))

        binding.darkModeTile.setOnClickListener { applyMode(true) }
        binding.lightModeTile.setOnClickListener { applyMode(false) }

        binding.roleCrewTile.setOnClickListener { confirmRoleSwitch(Settings.ROLE_CREW, binding.roleCrewLabel) }
        binding.rolePassengerTile.setOnClickListener {
            confirmRoleSwitch(Settings.ROLE_PASSENGER, binding.rolePassengerLabel)
        }

        binding.crewFunctionPurserIiTile.setOnClickListener {
            applyCrewFunction(Settings.CREW_FUNCTION_PURSER_II)
        }
        binding.crewFunctionPurserITile.setOnClickListener {
            applyCrewFunction(Settings.CREW_FUNCTION_PURSER_I)
        }
        binding.crewFunctionFlightAttendantTile.setOnClickListener {
            applyCrewFunction(Settings.CREW_FUNCTION_FLIGHT_ATTENDANT)
        }

        binding.accentPurpleTile.setOnClickListener { applyAccent(Settings.ACCENT_PURPLE) }
        binding.accentLightBlueTile.setOnClickListener { applyAccent(Settings.ACCENT_LIGHT_BLUE) }
        binding.accentLightGreenTile.setOnClickListener { applyAccent(Settings.ACCENT_LIGHT_GREEN) }
        binding.accentBrownTile.setOnClickListener { applyAccent(Settings.ACCENT_BROWN) }

        binding.tileClassSchemeLufthansa.setOnClickListener { applyClassScheme(Settings.CLASS_SCHEME_LUFTHANSA) }

        binding.mapOrientationLandscapeTile.setOnClickListener {
            applyMapOrientation(Settings.MAP_ORIENTATION_LANDSCAPE)
        }
        binding.mapOrientationPortraitTile.setOnClickListener {
            applyMapOrientation(Settings.MAP_ORIENTATION_PORTRAIT)
        }

        binding.exportTile.setOnClickListener { confirmExport() }
        binding.importTile.setOnClickListener {
            findNavController().navigate(R.id.action_profile_to_import)
        }

        binding.crashLogTile.setOnClickListener { confirmCrashLog() }
        binding.demoDataSwitch.isChecked = Settings.isDemoDataEnabled(requireContext())
        binding.demoDataTile.setOnClickListener { binding.demoDataSwitch.toggle() }
        binding.demoDataSwitch.setOnCheckedChangeListener { _, checked ->
            Settings.setDemoDataEnabled(requireContext(), checked)
            LogbookRepository.invalidate()
            Toast.makeText(
                requireContext(),
                if (checked) R.string.demo_data_enabled else R.string.demo_data_disabled,
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.avatarTile.setOnClickListener { showAvatarDialog() }
        loadAvatarPreview()
        binding.profileNameInput.setText(Settings.getProfileName(requireContext()))
        binding.profileNameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                Settings.setProfileName(requireContext(), s?.toString().orEmpty())
            }
        })

        setupSections()
        setupDefaultPeriodDropdown()
    }

    private fun setupSections() {
        setupCollapsible(binding.headerSectionProfile, binding.contentProfile, binding.profileHeaderArrow)
        setupCollapsible(binding.headerSectionThemes, binding.contentThemes, binding.themesHeaderArrow)
        setupCollapsible(
            binding.headerSectionCustomization,
            binding.contentCustomization,
            binding.customizationHeaderArrow
        )
        setupCollapsible(binding.headerSectionData, binding.contentData, binding.dataHeaderArrow)
        setupCollapsible(
            binding.headerSectionDevelopment,
            binding.contentDevelopment,
            binding.developmentHeaderArrow
        )
    }

    private fun confirmCrashLog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.crash_log_title)
            .setMessage(R.string.crash_log_message)
            .setPositiveButton(R.string.crash_log_confirm) { _, _ ->
                crashLogLauncher.launch("crash_log.log")
            }
            .setNegativeButton(R.string.discard_cancel, null)
            .show()
    }

    private fun showCrashLogContent(content: String) {
        val textView = TextView(requireContext()).apply {
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(24), dp(8), dp(24), dp(8))
            text = content
        }
        val scrollView = android.widget.ScrollView(requireContext()).apply {
            addView(textView)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.development_crash_log)
            .setView(scrollView)
            .setPositiveButton(R.string.crash_log_copy) { _, _ ->
                val clip = android.content.ClipData.newPlainText(
                    "crash_log",
                    content
                )
                requireContext()
                    .getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(clip)
                Toast.makeText(
                    requireContext(),
                    R.string.crash_log_copied,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun confirmExport() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.export_title)
            .setMessage(R.string.export_message)
            .setPositiveButton(R.string.export_confirm) { _, _ ->
                exportLauncher.launch("logbook.csv")
            }
            .setNegativeButton(R.string.discard_cancel, null)
            .show()
    }

    private fun setupCollapsible(header: LinearLayout, content: View, arrow: ImageView) {
        header.setOnClickListener {
            val expanding = content.visibility != View.VISIBLE
            if (expanding) {
                content.visibility = View.VISIBLE
                content.alpha = 0f
                content.animate().alpha(1f).setDuration(200).start()
            } else {
                content.animate().alpha(0f).setDuration(150).withEndAction {
                    content.visibility = View.GONE
                }.start()
            }
            arrow.animate().rotation(if (expanding) 180f else 0f).setDuration(200).start()
        }
    }

    private fun applyAccent(accent: String) {
        if (Settings.getAccentColor(requireContext()) == accent) return
        Settings.setAccentColor(requireContext(), accent)
        requireActivity().recreate()
    }

    private fun applyMode(dark: Boolean) {
        Settings.setDarkMode(requireContext(), dark)
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun updateModeTiles(dark: Boolean) {
        highlight(binding.darkModeTile, binding.darkModeLabel, dark)
        highlight(binding.lightModeTile, binding.lightModeLabel, !dark)
    }

    private fun updateLanguageTiles() {
        highlight(binding.langDeTile, binding.langDeLabel, true)
    }

    private fun applyMapOrientation(orientation: String) {
        if (Settings.getMapOrientation(requireContext()) == orientation) return
        Settings.setMapOrientation(requireContext(), orientation)
        updateMapOrientationTiles(orientation)
    }

    private fun updateMapOrientationTiles(orientation: String) {
        highlight(
            binding.mapOrientationLandscapeTile,
            binding.mapOrientationLandscapeLabel,
            orientation == Settings.MAP_ORIENTATION_LANDSCAPE,
            binding.mapOrientationLandscapeIcon
        )
        highlight(
            binding.mapOrientationPortraitTile,
            binding.mapOrientationPortraitLabel,
            orientation == Settings.MAP_ORIENTATION_PORTRAIT,
            binding.mapOrientationPortraitIcon
        )
    }

    private fun confirmRoleSwitch(role: String, label: TextView) {
        if (Settings.getRole(requireContext()) == role) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.role_switch_title)
            .setMessage(getString(R.string.role_switch_message, label.text))
            .setPositiveButton(R.string.role_switch_confirm) { _, _ ->
                Settings.setRole(requireContext(), role)
                updateRoleTiles(role)
            }
            .setNegativeButton(R.string.role_switch_cancel, null)
            .show()
    }

    private fun updateRoleTiles(role: String) {
        highlight(binding.roleCrewTile, binding.roleCrewLabel, role == Settings.ROLE_CREW)
        highlight(binding.rolePassengerTile, binding.rolePassengerLabel, role == Settings.ROLE_PASSENGER)
        binding.crewFunctionGroup.visibility = if (role == Settings.ROLE_CREW) View.VISIBLE else View.GONE
    }

    private fun applyCrewFunction(key: String) {
        if (Settings.getCrewFunction(requireContext()) == key) return
        Settings.setCrewFunction(requireContext(), key)
        updateCrewFunctionTiles(key)
    }

    private fun updateCrewFunctionTiles(key: String) {
        highlight(
            binding.crewFunctionPurserIiTile,
            binding.crewFunctionPurserIiLabel,
            key == Settings.CREW_FUNCTION_PURSER_II
        )
        highlight(
            binding.crewFunctionPurserITile,
            binding.crewFunctionPurserILabel,
            key == Settings.CREW_FUNCTION_PURSER_I
        )
        highlight(
            binding.crewFunctionFlightAttendantTile,
            binding.crewFunctionFlightAttendantLabel,
            key == Settings.CREW_FUNCTION_FLIGHT_ATTENDANT
        )
    }

    private fun updateAccentTiles(accent: String) {
        highlight(binding.accentPurpleTile, binding.accentPurpleLabel, accent == Settings.ACCENT_PURPLE)
        highlight(binding.accentLightBlueTile, binding.accentLightBlueLabel, accent == Settings.ACCENT_LIGHT_BLUE)
        highlight(binding.accentLightGreenTile, binding.accentLightGreenLabel, accent == Settings.ACCENT_LIGHT_GREEN)
        highlight(binding.accentBrownTile, binding.accentBrownLabel, accent == Settings.ACCENT_BROWN)
    }

    private fun applyClassScheme(scheme: String) {
        if (Settings.getClassScheme(requireContext()) == scheme) return
        Settings.setClassScheme(requireContext(), scheme)
        updateClassSchemeTiles(scheme)
    }

    private fun updateClassSchemeTiles(scheme: String) {
        val active = scheme == Settings.CLASS_SCHEME_LUFTHANSA
        if (active) {
            val navy = ContextCompat.getColor(requireContext(), R.color.scheme_lufthansa_bg)
            binding.tileClassSchemeLufthansa.setCardBackgroundColor(navy)
            binding.tileClassSchemeLufthansa.strokeColor = navy
            binding.labelClassSchemeLufthansa.setTextColor(Color.WHITE)
        } else {
            highlight(binding.tileClassSchemeLufthansa, binding.labelClassSchemeLufthansa, false)
        }
    }

    private fun highlight(
        card: MaterialCardView,
        label: TextView,
        active: Boolean,
        icon: ImageView? = null
    ) {
        val primaryContainer = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorPrimaryContainer
        )
        val onPrimaryContainer = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnPrimaryContainer
        )
        val surfaceContainerLow = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorSurfaceContainerLow
        )
        val onSurface = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnSurface
        )
        val outlineVariant = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOutlineVariant
        )

        card.setCardBackgroundColor(
            if (active) primaryContainer else surfaceContainerLow
        )
        card.strokeColor =
            if (active) primaryContainer else outlineVariant
        label.setTextColor(
            if (active) onPrimaryContainer else onSurface
        )
        icon?.imageTintList = ColorStateList.valueOf(
            if (active) onPrimaryContainer else onSurface
        )
    }

    private fun setupDefaultPeriodDropdown() {
        val keys = PeriodOptions.keys(requireContext())
        val labels = keys.map { PeriodOptions.label(requireContext(), it) }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels)
        binding.defaultPeriodDropdown.setAdapter(adapter)
        binding.defaultPeriodDropdown.setText(
            PeriodOptions.label(requireContext(), Settings.getDefaultPeriodKey(requireContext())),
            false
        )
        binding.defaultPeriodDropdown.setOnItemClickListener { parent, _, position, _ ->
            val label = parent.getItemAtPosition(position) as String
            val key = PeriodOptions.keyForLabel(requireContext(), label)
            if (key != null) {
                Settings.setDefaultPeriodKey(requireContext(), key)
            }
        }
    }

    private fun loadAvatarPreview() {
        val value = Settings.getAvatar(requireContext())
        val card = binding.avatarTile
        val icon = binding.avatarPreview
        if (value == Settings.AVATAR_FILE) {
            val file = File(requireContext().filesDir, ProfileAvatar.FILE_NAME)
            val bmp = if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
            if (bmp != null) {
                card.setCardBackgroundColor(
                    MaterialColors.getColor(
                        binding.root,
                        com.google.android.material.R.attr.colorSurfaceContainerLow
                    )
                )
                card.strokeColor = MaterialColors.getColor(
                    binding.root,
                    com.google.android.material.R.attr.colorOutlineVariant
                )
                icon.setImageDrawable(
                    RoundedBitmapDrawableFactory.create(resources, bmp).apply {
                        isCircular = true
                    }
                )
                icon.imageTintList = null
                refreshNavAvatar()
                return
            }
        }
        card.setCardBackgroundColor(
            MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorPrimaryContainer
            )
        )
        card.strokeColor = MaterialColors.getColor(
            binding.root,
            com.google.android.material.R.attr.colorPrimaryContainer
        )
        icon.setImageResource(ProfileAvatar.resId(value))
        icon.imageTintList = ColorStateList.valueOf(
            MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorOnPrimaryContainer
            )
        )
        refreshNavAvatar()
    }

    private fun refreshNavAvatar() {
        (activity as? MainActivity)?.refreshProfileNavIcon()
    }

    private fun showAvatarDialog() {
        val options = arrayOf(
            getString(R.string.profile_avatar_person),
            getString(R.string.profile_avatar_flight),
            getString(R.string.profile_avatar_world),
            getString(R.string.profile_avatar_upload)
        )
        val keys = arrayOf(
            Settings.AVATAR_PERSON,
            Settings.AVATAR_FLIGHT,
            Settings.AVATAR_WORLD
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_avatar_title)
            .setItems(options) { _, which ->
                if (which < keys.size) {
                    Settings.setAvatar(requireContext(), keys[which])
                    loadAvatarPreview()
                } else {
                    avatarPickerLauncher.launch("image/*")
                }
            }
            .show()
    }

    private fun saveAvatarFromUri(uri: Uri) {
        try {
            val bytes = requireContext().contentResolver.openInputStream(uri)
                ?.use { it.readBytes() }
                ?: return
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            val scaled = Bitmap.createScaledBitmap(decoded, 512, 512, true)
            if (decoded != scaled) decoded.recycle()
            File(requireContext().filesDir, ProfileAvatar.FILE_NAME).outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            scaled.recycle()
            Settings.setAvatar(requireContext(), Settings.AVATAR_FILE)
            loadAvatarPreview()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.import_error, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}