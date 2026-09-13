package com.highfly.logbook

import android.graphics.Color
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.highfly.logbook.databinding.FragmentProfileBinding

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null

    private val binding get() = _binding!!

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
        updateLanguageTiles()
        updateAccentTiles(Settings.getAccentColor(requireContext()))
        updateClassSchemeTiles(Settings.getClassScheme(requireContext()))

        binding.darkModeTile.setOnClickListener { applyMode(true) }
        binding.lightModeTile.setOnClickListener { applyMode(false) }

        binding.roleCrewTile.setOnClickListener { confirmRoleSwitch(Settings.ROLE_CREW, binding.roleCrewLabel) }
        binding.rolePassengerTile.setOnClickListener {
            confirmRoleSwitch(Settings.ROLE_PASSENGER, binding.rolePassengerLabel)
        }

        binding.accentPurpleTile.setOnClickListener { applyAccent(Settings.ACCENT_PURPLE) }
        binding.accentLightBlueTile.setOnClickListener { applyAccent(Settings.ACCENT_LIGHT_BLUE) }
        binding.accentLightGreenTile.setOnClickListener { applyAccent(Settings.ACCENT_LIGHT_GREEN) }
        binding.accentBrownTile.setOnClickListener { applyAccent(Settings.ACCENT_BROWN) }

        binding.tileClassSchemeLufthansa.setOnClickListener { applyClassScheme(Settings.CLASS_SCHEME_LUFTHANSA) }

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

    private fun highlight(card: MaterialCardView, label: TextView, active: Boolean) {
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}