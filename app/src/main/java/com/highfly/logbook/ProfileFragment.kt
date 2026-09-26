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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.highfly.logbook.databinding.FragmentProfileBinding
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

    private companion object {
        const val AVATAR_ICON_INSET_DP = 24
    }
}