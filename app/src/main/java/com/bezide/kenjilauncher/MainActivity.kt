package com.bezide.kenjilauncher

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast

class MainActivity : Activity() {

    private val REQ_PICK_GAME = 1
    private val REQ_PICK_ICON = 2

    private var pendingUriForShortcut: Uri? = null
    private var pendingLabelForShortcut: String? = null

    // gemerkte URI, falls über Shortcut gestartet
    private var initialUriFromIntent: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prüfen, ob wir über Shortcut (mit URI) gestartet wurden
        val bootPath = intent.getStringExtra("bootPath")
        initialUriFromIntent = when {
            bootPath != null -> Uri.parse(bootPath)
            intent.data != null -> intent.data
            else -> null
        }

        if (initialUriFromIntent != null) {
            // → Shortcut-Start: KEIN How-To anzeigen
            proceedEntryFlow()
        } else {
            // → App-Start: How-To anzeigen, dann weiter
            showHowToDialog { proceedEntryFlow() }
        }
    }

    /** Anleitung (nur beim App-Start) anzeigen und danach onContinue() ausführen */
    private fun showHowToDialog(onContinue: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Welcome to Kenji Launcher")
            .setMessage(
                "1. Pick a Nintendo Switch game file (.nsp, .xci).\n\n" +
                        "2. Choose: Start Now or Create Shortcut.\n\n" +
                        "3. When creating a shortcut, you can enter a name and choose an icon (or use the default app icon).\n\n" +
                        "4. Shortcuts appear on your home screen and launch directly into Kenji-NX."
            )
            .setPositiveButton("Got it") { d, _ ->
                d.dismiss()
                onContinue()
            }
            .setCancelable(false)
            .show()
    }

    /** Startlogik nach How-To (oder direkt bei Shortcut-Start) */
    private fun proceedEntryFlow() {
        val uri = initialUriFromIntent
        if (uri != null) {
            // Shortcut-Start: prüfen, ob persistente Rechte da sind
            if (!hasPersistedRead(uri)) {
                Toast.makeText(this, "Please grant file access again…", Toast.LENGTH_SHORT).show()
                requestGameFile()
                return
            }
            grantAndStart(uri)
            return
        }
        // Normaler App-Start → Datei wählen
        requestGameFile()
    }

    private fun requestGameFile() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        startActivityForResult(pick, REQ_PICK_GAME)
    }

    @Deprecated("OK für diesen einfachen Flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, result: Intent?) {
        super.onActivityResult(requestCode, resultCode, result)

        if (requestCode == REQ_PICK_GAME && resultCode == RESULT_OK) {
            val gameUri = result?.data
            if (gameUri == null) { finish(); return }
            offerStartOrPin(gameUri)
            return
        }

        if (requestCode == REQ_PICK_ICON && resultCode == RESULT_OK) {
            val imageUri = result?.data
            val gameUri = pendingUriForShortcut
            val label = pendingLabelForShortcut
            pendingUriForShortcut = null
            pendingLabelForShortcut = null

            if (gameUri == null || label.isNullOrBlank()) {
                Toast.makeText(this, "Shortcut cancelled.", Toast.LENGTH_SHORT).show()
                finish(); return
            }

            // Optionales Icon laden; wenn null → Fallback App-Icon
            val bmp = imageUri?.let {
                try {
                    contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                loadBitmapFromUri(it)
            }

            val ok = pinShortcutWithBitmap(gameUri, label, bmp)
            Toast.makeText(this, if (ok) "Shortcut “$label” created." else "Shortcut failed.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        finish()
    }

    private fun offerStartOrPin(gameUri: Uri) {
        val suggested = suggestLabelFromUri(gameUri)
        val nameInput = EditText(this).apply { hint = suggested }

        AlertDialog.Builder(this)
            .setTitle("Choose action")
            .setMessage("Do you want to start the game now or create a home screen shortcut?")
            .setPositiveButton("Start Now") { _, _ ->
                grantAndStart(gameUri)
            }
            .setNeutralButton("Create Shortcut") { _, _ ->
                val label = nameInput.text?.toString()?.takeIf { it.isNotBlank() } ?: suggested
                persistReadWrite(gameUri)
                pendingUriForShortcut = gameUri
                pendingLabelForShortcut = label
                requestIconImage()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setView(nameInput)
            .setCancelable(false)
            .show()
    }

    private fun requestIconImage() {
        val pickIcon = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        // Wenn der Nutzer abbricht → Fallback ist App-Icon
        startActivityForResult(Intent.createChooser(pickIcon, "Select icon (or cancel to use app icon)"), REQ_PICK_ICON)
    }

    private fun suggestLabelFromUri(uri: Uri): String {
        val last = uri.lastPathSegment ?: return "Start Game"
        val raw = last.substringAfterLast("%2F").substringAfterLast("/")
        return Uri.decode(raw).ifBlank { "Start Game" }
    }

    private fun hasPersistedRead(uri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private fun persistReadWrite(uri: Uri) {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try { contentResolver.takePersistableUriPermission(uri, rw) } catch (_: Exception) {}
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? =
        try { contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) } }
        catch (_: Exception) { null }

    /** Shortcut mit benutzerdefiniertem Bitmap oder Fallback-App-Icon */
    private fun pinShortcutWithBitmap(gameUri: Uri, label: String, bmp: Bitmap?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val sm = getSystemService(ShortcutManager::class.java) ?: return false

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setData(gameUri)
            putExtra("bootPath", gameUri.toString())
        }

        val icon = if (bmp != null) {
            Icon.createWithBitmap(bmp)
        } else {
            // Fallback: App-Icon aus Ressourcen verwenden
            Icon.createWithResource(this, R.mipmap.ic_launcher)
        }

        val shortcut = ShortcutInfo.Builder(this, "kenji_game_${gameUri.hashCode()}")
            .setShortLabel(label.take(24))
            .setLongLabel(label)
            .setIcon(icon)
            .setIntent(launchIntent)
            .build()

        // Portrait während des Pin-Dialogs (manche Launcher wollen das)
        val prev = requestedOrientation
        return try {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            if (sm.isRequestPinShortcutSupported) {
                val cb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val successIntent = sm.createShortcutResultIntent(shortcut)
                    PendingIntent.getBroadcast(this, 0, successIntent, PendingIntent.FLAG_IMMUTABLE).intentSender
                } else null
                sm.requestPinShortcut(shortcut, cb)
                true
            } else {
                sm.addDynamicShortcuts(listOf(shortcut))
                true
            }
        } finally {
            requestedOrientation = prev
        }
    }

    /** Startet Kenji-NX mit READ+WRITE-Grant */
    private fun grantAndStart(uri: Uri) {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val mime = contentResolver.getType(uri) ?: "*/*"

        persistReadWrite(uri)
        val clip = ClipData.newUri(contentResolver, "GameUri", uri)
        try { grantUriPermission("org.kenjinx.android", uri, rw) } catch (_: Exception) {}

        val primary = Intent().apply {
            action = "org.kenjinx.android.LAUNCH_GAME"
            setClassName("org.kenjinx.android", "org.kenjinx.android.MainActivity")
            setDataAndType(uri, mime)
            clipData = clip
            putExtra("bootPath", uri.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or rw)
        }
        val fallback = Intent().apply {
            action = Intent.ACTION_VIEW
            setClassName("org.kenjinx.android", "org.kenjinx.android.MainActivity")
            setDataAndType(uri, mime)
            clipData = clip
            putExtra("bootPath", uri.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or rw)
        }

        try { startActivity(primary) } catch (_: Exception) {
            try { startActivity(fallback) } catch (e2: Exception) {
                Toast.makeText(this, "Start failed: ${e2.message}", Toast.LENGTH_LONG).show()
            }
        } finally { finish() }
    }
}
