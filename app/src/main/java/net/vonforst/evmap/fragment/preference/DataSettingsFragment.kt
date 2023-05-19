package net.vonforst.evmap.fragment.preference

import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import net.vonforst.evmap.R
import net.vonforst.evmap.addDebugInterceptors
import net.vonforst.evmap.api.availability.TeslaAuthenticationApi
import net.vonforst.evmap.api.availability.TeslaOwnerApi
import net.vonforst.evmap.fragment.oauth.OAuthLoginFragmentArgs
import net.vonforst.evmap.viewmodel.SettingsViewModel
import net.vonforst.evmap.viewmodel.viewModelFactory
import okhttp3.OkHttpClient
import okio.IOException
import java.time.Instant

class DataSettingsFragment : BaseSettingsFragment() {
    override val isTopLevel = false

    private val vm: SettingsViewModel by viewModels(factoryProducer = {
        viewModelFactory {
            SettingsViewModel(
                requireActivity().application,
                getString(R.string.chargeprice_key),
                getString(R.string.chargeprice_api_url)
            )
        }
    })

    private lateinit var teslaAccountPreference: Preference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_data, rootKey)
        teslaAccountPreference = findPreference<Preference>("tesla_account")!!
        refreshTeslaAccountStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshTeslaAccountStatus()
    }

    private fun refreshTeslaAccountStatus() {
        teslaAccountPreference.summary =
            if (encryptedPrefs.teslaRefreshToken != null) {
                getString(R.string.pref_tesla_account_enabled, encryptedPrefs.teslaEmail)
            } else {
                getString(R.string.pref_tesla_account_disabled)
            }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        when (key) {
            "search_provider" -> {
                if (prefs.searchProvider == "google") {
                    Snackbar.make(
                        requireView(),
                        R.string.pref_search_provider_info,
                        Snackbar.LENGTH_INDEFINITE
                    ).apply {
                        setAction(R.string.ok) {}
                        this.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
                            ?.apply {
                                maxLines = 6
                            }
                    }
                        .show()
                }
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        return when (preference.key) {
            "search_delete_recent" -> {
                Snackbar.make(
                    requireView(),
                    R.string.deleted_recent_search_results,
                    Snackbar.LENGTH_LONG
                )
                    .show()
                vm.deleteRecentSearchResults()
                true
            }

            "tesla_account" -> {
                if (encryptedPrefs.teslaRefreshToken != null) {
                    teslaLogout()
                } else {
                    teslaLogin()
                }
                true
            }

            else -> super.onPreferenceTreeClick(preference)
        }
    }

    private fun teslaLogin() {
        val codeVerifier = TeslaAuthenticationApi.generateCodeVerifier()
        val codeChallenge = TeslaAuthenticationApi.generateCodeChallenge(codeVerifier)
        val uri = Uri.parse("https://auth.tesla.com/oauth2/v3/authorize").buildUpon()
            .appendQueryParameter("client_id", "ownerapi")
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("redirect_uri", "https://auth.tesla.com/void/callback")
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "openid email offline_access")
            .appendQueryParameter("state", "123").build()

        val args = OAuthLoginFragmentArgs(
            uri.toString(),
            "https://auth.tesla.com/void/callback"
        ).toBundle()

        setFragmentResultListener(uri.toString()) { _, result ->
            teslaGetAccessToken(result, codeVerifier)
        }

        findNavController().navigate(R.id.oauth_login, args)
    }

    private fun teslaGetAccessToken(result: Bundle, codeVerifier: String) {
        teslaAccountPreference.summary = getString(R.string.logging_in)

        val url = Uri.parse(result.getString("url"))
        val code = url.getQueryParameter("code")!!
        val okhttp = OkHttpClient.Builder().addDebugInterceptors().build()
        val request = TeslaAuthenticationApi.AuthCodeRequest(code, codeVerifier)
        lifecycleScope.launch {
            try {
                val time = Instant.now().epochSecond
                val response =
                    TeslaAuthenticationApi.create(okhttp).getToken(request)
                val userResponse =
                    TeslaOwnerApi.create(okhttp, response.accessToken).getUserInfo()

                encryptedPrefs.teslaEmail = userResponse.response.email
                encryptedPrefs.teslaAccessToken = response.accessToken
                encryptedPrefs.teslaAccessTokenExpiry = time + response.expiresIn
                encryptedPrefs.teslaRefreshToken = response.refreshToken
            } catch (e: IOException) {
                view?.let {
                    Snackbar.make(it, R.string.connection_error, Snackbar.LENGTH_SHORT).show()
                }
            }
            refreshTeslaAccountStatus()
        }
    }

    private fun teslaLogout() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.pref_tesla_account_enabled, encryptedPrefs.teslaEmail))
            .setPositiveButton(R.string.ok) { _, _ -> }
            .setNegativeButton(R.string.log_out) { _, _ ->
                // sign out
                encryptedPrefs.teslaRefreshToken = null
                encryptedPrefs.teslaAccessToken = null
                encryptedPrefs.teslaAccessTokenExpiry = -1
                encryptedPrefs.teslaEmail = null
                view?.let { Snackbar.make(it, R.string.logged_out, Snackbar.LENGTH_SHORT).show() }
                refreshTeslaAccountStatus()
            }
            .show()
    }
}