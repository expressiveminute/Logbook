package com.highfly.logbook

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.highfly.logbook.databinding.DialogAvatarPickerBinding
import com.highfly.logbook.databinding.FragmentProfileBinding
import com.highfly.logbook.databinding.ItemAvatarChoiceBinding
import java.io.File
import kotlin.math.roundToInt

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null

    private val binding get() = _binding!!

    private val avatarPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        saveAvatarFromUri(uri)
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

        binding.avatarTile.setOnClickListener { showAvatarDialog() }
        loadAvatarPreview()
        binding.tvSettingsVersion.text =
            getString(R.string.settings_version_label, BuildConfig.VERSION_NAME)
        binding.profileNameInput.setText(Settings.getProfileName(requireContext()))
        binding.profileNameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                Settings.setProfileName(requireContext(), s?.toString().orEmpty())
            }
        })

        binding.headerSectionProfile.setOnClickListener { openSection("profile") }
        binding.headerSectionCustomization.setOnClickListener { openSection("customization") }
        binding.headerSectionData.setOnClickListener { openSection("data") }
        binding.headerSectionDevelopment.setOnClickListener { openSection("development") }
    }

    private fun openSection(sectionKey: String) {
        val bundle = Bundle().apply { putString("sectionKey", sectionKey) }
        findNavController().navigate(R.id.action_profile_to_settings_section, bundle)
    }

    private fun loadAvatarPreview() {
        val value = Settings.getAvatar(requireContext())
        val frame = binding.avatarTile
        val icon = binding.avatarPreview
        if (value == Settings.AVATAR_FILE) {
            val file = File(requireContext().filesDir, ProfileAvatar.FILE_NAME)
            val bmp = if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
            if (bmp != null) {
                frame.backgroundTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(
                        binding.root,
                        com.google.android.material.R.attr.colorSurfaceContainerLow
                    )
                )
                setIconInset(icon, 0)
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
        frame.backgroundTintList = ColorStateList.valueOf(
            MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorPrimaryContainer
            )
        )
        setIconInset(icon, AVATAR_ICON_INSET_DP)
        icon.setImageResource(ProfileAvatar.resId(value))
        icon.imageTintList = ColorStateList.valueOf(
            MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorOnPrimaryContainer
            )
        )
        refreshNavAvatar()
    }

    /** Verkleinert ein Preset-Icon auf [insetDp] innerhalb des runden Rahmens;
     * ein hochgeladenes Foto ([insetDp] = 0) fuellt ihn komplett. */
    private fun setIconInset(icon: ImageView, insetDp: Int) {
        val inset = (insetDp * resources.displayMetrics.density).roundToInt()
        icon.setPadding(inset, inset, inset, inset)
    }

    private fun refreshNavAvatar() {
        (activity as? MainActivity)?.refreshProfileNavIcon()
    }

    private fun showAvatarDialog() {
        val dialogBinding = DialogAvatarPickerBinding.inflate(layoutInflater)
        val current = ProfileAvatar.normalize(Settings.getAvatar(requireContext()))
        lateinit var dialog: AlertDialog

        ProfileAvatar.presets.chunked(AVATAR_GRID_COLUMNS).forEach { rowPresets ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            rowPresets.forEach { preset ->
                val cell = ItemAvatarChoiceBinding.inflate(layoutInflater, row, false)
                val selected = preset.value == current
                cell.cardAvatarChoice.contentDescription = getString(preset.nameRes)
                cell.cardAvatarChoice.isSelected = selected
                cell.cardAvatarChoice.strokeWidth = dp(if (selected) 2 else 1)
                cell.ivAvatarChoiceCheck.isVisible = selected
                cell.ivAvatarChoice.setImageResource(preset.iconRes)
                cell.cardAvatarChoice.setOnClickListener {
                    Settings.setAvatar(requireContext(), preset.value)
                    loadAvatarPreview()
                    dialog.dismiss()
                }
                row.addView(cell.root)
            }
            dialogBinding.llAvatarGrid.addView(row)
        }

        dialogBinding.tvAvatarCurrent.text = getString(
            R.string.profile_avatar_current,
            getString(
                if (current == Settings.AVATAR_FILE) R.string.profile_avatar_own_photo
                else ProfileAvatar.presetNameRes(current)
            )
        )

        dialogBinding.btnAvatarUpload.setOnClickListener {
            dialog.dismiss()
            avatarPickerLauncher.launch("image/*")
        }
        if (current == Settings.AVATAR_FILE) {
            // Hochgeladenes Foto ist der aktive Avatar: Upload-Kante hervorheben.
            dialogBinding.btnAvatarUpload.strokeWidth = dp(2)
            dialogBinding.btnAvatarUpload.strokeColor = ColorStateList.valueOf(
                MaterialColors.getColor(
                    binding.root,
                    com.google.android.material.R.attr.colorPrimary
                )
            )
        }

        dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_avatar_title)
            .setView(dialogBinding.root)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

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

    private companion object {
        const val AVATAR_ICON_INSET_DP = 24
        const val AVATAR_GRID_COLUMNS = 4
    }
}