package com.novra.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlinx.coroutines.delay
import javax.crypto.spec.SecretKeySpec

/**
 * Stores only a random salt and a Keystore-keyed verifier.
 * The PIN itself is never persisted.
 */
internal object AppPinVault {
    private const val PREFS = "novra_security"
    private const val SALT = "pin_salt"
    private const val VERIFIER = "pin_verifier"
    private const val KEY_ALIAS = "novra_app_pin_hmac_v1"
    private const val HMAC = "HmacSHA256"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(VERIFIER)

    @Synchronized
    fun setPin(context: Context, pin: String) {
        require(pin.length in 4..8 && pin.all(Char::isDigit)) { "PIN must contain 4 to 8 digits" }
        val salt = ByteArray(32).also(SecureRandom()::nextBytes)
        val verifier = calculate(salt, pin)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(VERIFIER, Base64.encodeToString(verifier, Base64.NO_WRAP))
            .commit()
    }

    @Synchronized
    fun verify(context: Context, pin: String): Boolean {
        if (pin.length !in 4..8 || !pin.all(Char::isDigit)) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lockUntil = prefs.getLong("pin_lock_until", 0L)
        if (now < lockUntil) return false
        if (lockUntil != 0L) {
            prefs.edit().remove("pin_lock_until").putInt("pin_failed_attempts", 0).apply()
        }
        val saltText = prefs.getString(SALT, null) ?: return false
        val expectedText = prefs.getString(VERIFIER, null) ?: return false
        val valid = runCatching {
            val salt = Base64.decode(saltText, Base64.NO_WRAP)
            val expected = Base64.decode(expectedText, Base64.NO_WRAP)
            val actual = calculate(salt, pin)
            java.security.MessageDigest.isEqual(expected, actual)
        }.getOrDefault(false)
        if (valid) {
            prefs.edit().remove("pin_lock_until").putInt("pin_failed_attempts", 0).apply()
        } else {
            val attempts = prefs.getInt("pin_failed_attempts", 0) + 1
            if (attempts >= 5) {
                prefs.edit().putInt("pin_failed_attempts", 0)
                    .putLong("pin_lock_until", now + 30_000L).apply()
            } else {
                prefs.edit().putInt("pin_failed_attempts", attempts).apply()
            }
        }
        return valid
    }

    fun lockoutRemainingMillis(context: Context): Long {
        val until = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("pin_lock_until", 0L)
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    @Synchronized
    fun disable(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(SALT).remove(VERIFIER)
            .remove("pin_failed_attempts").remove("pin_lock_until").commit()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(KEY_ALIAS)
        }
    }

    private fun calculate(salt: ByteArray, pin: String): ByteArray {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: createKey()
        return Mac.getInstance(HMAC).run {
            init(key)
            update(salt)
            doFinal(pin.toByteArray(Charsets.UTF_8))
        }
    }

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).setDigests(KeyProperties.DIGEST_SHA256).build()
        )
        return generator.generateKey()
    }
}

@Composable
internal fun AppPinGate(
    title: String,
    subtitle: String,
    confirmLabel: String,
    errorLabel: String,
    onSubmit: (String) -> Boolean,
    onCancel: (() -> Unit)? = null
) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var cooldownSeconds by remember { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val setup = confirmLabel.isNotBlank()

    LaunchedEffect(error) {
        if (error) {
            while (true) {
                val remaining = AppPinVault.lockoutRemainingMillis(context)
                cooldownSeconds = ((remaining + 999L) / 1000L).toInt()
                if (remaining <= 0L) break
                delay(250L)
            }
        } else {
            cooldownSeconds = 0
        }
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF101310)).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Rounded.Lock, null, tint = Color(0xFFC7E8D1), modifier = Modifier.size(46.dp))
        Spacer(Modifier.height(18.dp))
        Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = Color(0xFFA7B0A8), fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { value -> if (value.length <= 8 && value.all(Char::isDigit)) { pin = value; error = false } },
            label = { Text(if (setup) "New PIN" else "PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFC7E8D1), unfocusedBorderColor = Color(0xFF526057),
                focusedLabelColor = Color(0xFFC7E8D1), unfocusedLabelColor = Color(0xFFA7B0A8)
            )
        )
        if (setup) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = confirmation,
                onValueChange = { value -> if (value.length <= 8 && value.all(Char::isDigit)) { confirmation = value; error = false } },
                label = { Text("Confirm PIN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFC7E8D1), unfocusedBorderColor = Color(0xFF526057),
                    focusedLabelColor = Color(0xFFC7E8D1), unfocusedLabelColor = Color(0xFFA7B0A8)
                )
            )
        }
        if (error) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (cooldownSeconds > 0) "Too many attempts. Try again in ${cooldownSeconds}s."
                else errorLabel,
                color = Color(0xFFFF8A80), fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                error = if (setup && pin != confirmation) true else !onSubmit(pin)
                if (!error) { pin = ""; confirmation = "" }
            },
            enabled = pin.length in 4..8 && (!setup || confirmation.length in 4..8) && cooldownSeconds == 0,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC7E8D1), contentColor = Color(0xFF101310))
        ) { Text(if (setup) "Save PIN" else "Unlock") }
        if (onCancel != null) {
            TextButton(onClick = onCancel) { Text("Cancel", color = Color(0xFFA7B0A8)) }
        }
    }
}
