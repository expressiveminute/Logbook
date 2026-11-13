package com.highfly.logbook

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import com.google.zxing.BarcodeFormat
import com.highfly.logbook.databinding.FragmentBoardingPassScanBinding
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/**
 * Scannt den QR-Code auf einer Bordkarte (Long-Press auf dem Dashboard-Plus
 * → „Bordkarte scannen“). Der Kameraausschnitt ist eine eingebettete
 * ZXing-[com.journeyapps.barcodescanner.BarcodeView]; die CAMERA-Permission
 * wird zur Laufzeit erfragt. Erkannt wird nur das Logbook-Format
 * ([QrPayload.PREFIX]): der Rohtext geht als Argument an „Neuer Flug“, wo der
 * Nutzer den Flug wie gewohnt bearbeiten und speichern kann
 * ([AddEntryFragment]). Fremd-Codes lösen nur einen kurzen Hinweis aus.
 */
class BoardingPassScanFragment : LogbookFragment() {

    private var _binding: FragmentBoardingPassScanBinding? = null
    private val binding get() = _binding!!

    private var cameraStarted = false

    /** true, sobald ein gültiger Code erkannt wurde (keine Mehrfachnavigation). */
    private var resultHandled = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startScanning() else showNoCameraHint()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBoardingPassScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }

        // Nur QR-Codes dekodieren, das spart Rechenzeit.
        binding.barcodeView.setDecoderFactory(DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE)))
        binding.barcodeView.decodeContinuous(BarcodeCallback { result ->
            // Der Callback läuft nicht zwingend auf dem UI-Thread.
            activity?.runOnUiThread { onScanResult(result.text) }
        })

        if (hasCameraPermission()) {
            startScanning()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        if (cameraStarted && !resultHandled) binding.barcodeView.resume()
    }

    override fun onPause() {
        super.onPause()
        binding.barcodeView.pause()
    }

    override fun onDestroyView() {
        binding.barcodeView.stopDecoding()
        super.onDestroyView()
        _binding = null
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        requireContext(), Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun startScanning() {
        if (cameraStarted || resultHandled) return
        cameraStarted = true
        binding.barcodeView.resume()
    }

    private fun onScanResult(text: String?) {
        if (resultHandled) return
        val entry = QrPayload.decode(text)
        if (entry == null) {
            // Fremdformat oder kaputt: kurzer Hinweis, dann weiter scannen.
            Toast.makeText(
                requireContext(), R.string.boarding_pass_scan_invalid, Toast.LENGTH_SHORT
            ).show()
            return
        }
        resultHandled = true
        binding.barcodeView.stopDecoding()
        binding.barcodeView.pause()
        // Rohtext mitgeben; AddEntryFragment dekodiert ihn selbst zu einem
        // vorbefüllten Formular.
        findNavController().navigate(
            R.id.action_boarding_pass_scan_to_add_entry,
            bundleOf("prefillQr" to text)
        )
    }

    private fun showNoCameraHint() {
        binding.tvScanHint.text = getString(R.string.boarding_pass_scan_no_camera)
    }
}
