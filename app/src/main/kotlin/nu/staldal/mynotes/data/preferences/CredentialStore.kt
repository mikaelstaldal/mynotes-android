package nu.staldal.mynotes.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException

class CredentialStore(context: Context) {
    private val prefs = openOrRecreate(context)

    val baseUrl: String?
        get() = prefs.getString(KEY_BASE_URL, null)

    val username: String?
        get() = prefs.getString(KEY_USERNAME, null)

    val password: String?
        get() = prefs.getString(KEY_PASSWORD, null)

    fun save(baseUrl: String, username: String, password: String) {
        prefs.edit()
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_BASE_URL)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .apply()
    }

    fun hasCredentials(): Boolean =
        baseUrl != null && username != null && password != null

    private companion object {
        const val TAG = "CredentialStore"
        const val PREFS_NAME = "secret_shared_prefs"
        const val KEY_BASE_URL = "base_url"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"

        fun openOrRecreate(context: Context): SharedPreferences =
            try {
                createEncryptedPrefs(context)
            } catch (e: GeneralSecurityException) {
                recreate(context, e)
            } catch (e: IOException) {
                recreate(context, e)
            }

        // The encrypted store can't be decrypted — typically after a
        // device-to-device migration copied secret_shared_prefs.xml without the
        // AndroidKeyStore master key (which never leaves the device). Discard the
        // corrupt file and start fresh so the user is prompted to log in again
        // instead of the app crashing on launch.
        private fun recreate(context: Context, cause: Exception): SharedPreferences {
            Log.w(TAG, "Could not decrypt credential store; recreating", cause)
            context.deleteSharedPreferences(PREFS_NAME)
            return createEncryptedPrefs(context)
        }

        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
