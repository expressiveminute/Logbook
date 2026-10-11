package com.highfly.logbook

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Bestätigungsdialog für zerstörerische Aktionen: Der Nutzer muss eine
 * Passphrase eintippen, bevor der positive Knopf frei wird. Bis dahin ist
 * er ausgegraut, damit nichts versehentlich bestätigt werden kann.
 *
 * Die Passphrasen stehen hier zusammen: [PHRASE_FLIGHTS] für das Löschen
 * eines Nutzers und den Werksreset, [PHRASE_DANGER_ZONE] für das
 * Überschreiben beim Import. Wörter, kein Freitext: Die Prüfung ignoriert
 * Groß-/Kleinschreibung, führende und folgende Leerzeichen.
 */
object PassphraseConfirm {

    const val PHRASE_FLIGHTS = "FLIGHTS"
    const val PHRASE_DANGER_ZONE = "DANGER ZONE"

    /**
     * Zeigt den Dialog. [titleRes] beschreibt die Aktion, [message] erklärt
     * sie und nennt die Passphrase; [confirmRes] beschriftet den positiven
     * Knopf. [onConfirmed] läuft erst, wenn der Nutzer die Passphrase
     * eingetippt und bestätigt hat.
     */
    fun show(
        context: Context,
        titleRes: Int,
        message: CharSequence,
        phrase: String,
        confirmRes: Int,
        onConfirmed: () -> Unit
    ) {
        val input = EditText(context).apply {
            hint = context.getString(R.string.passphrase_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSelectAllOnFocus(true)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setMessage(message)
            .setView(input)
            .setPositiveButton(confirmRes, null)
            .setNegativeButton(R.string.discard_cancel, null)
            .create()

        fun matches(): Boolean =
            input.text?.toString()?.trim()?.equals(phrase, ignoreCase = true) == true

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.isEnabled = matches()
            input.doAfterTextChanged { positive.isEnabled = matches() }
        }

        // Die Passphrase mit "Fertig" auf der Tastatur bestätigen, ohne den
        // Knopf anzutippen.
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            if (!matches()) return@setOnEditorActionListener true
            dialog.dismiss()
            onConfirmed()
            true
        }

        dialog.show()
        // Der Listener des positiven Knopfs wird erst nach dem Anzeigen
        // gesetzt: [getButton] liefert vorher null.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            dialog.dismiss()
            onConfirmed()
        }
    }
}