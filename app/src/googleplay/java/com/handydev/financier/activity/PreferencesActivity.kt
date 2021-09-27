package com.handydev.financier.activity

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.PreferenceManager
import android.preference.PreferenceScreen
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.handydev.financier.R
import com.handydev.financier.activity.RequestPermission.isRequestingPermission
import com.handydev.financier.app.FinancierApp
import com.handydev.financier.dialog.FolderBrowser
import com.handydev.financier.export.Export
import com.handydev.financier.export.drive.DriveBackupError
import com.handydev.financier.export.dropbox.Dropbox
import com.handydev.financier.rates.ExchangeRateProviderFactory
import com.handydev.financier.utils.FingerprintUtils.fingerprintUnavailable
import com.handydev.financier.utils.FingerprintUtils.reasonWhyFingerprintUnavailable
import com.handydev.financier.utils.MyPreferences
import com.handydev.financier.utils.PinProtection
import org.greenrobot.eventbus.EventBus

class PreferencesActivity : PreferenceActivity(),
    SharedPreferences.OnSharedPreferenceChangeListener {
    var pOpenExchangeRatesAppId: Preference? = null

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        setGDriveBackupFolder()
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(MyPreferences.switchLocale(base))
    }

    private fun isOpenExchangeRatesProvider(provider: String?): Boolean {
        return ExchangeRateProviderFactory.openexchangerates.name == provider
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences)
        val preferenceScreen: PreferenceScreen = getPreferenceScreen()
        setGDriveBackupFolder()
        val pLocale: Preference = preferenceScreen.findPreference("ui_language")
        pLocale.setOnPreferenceChangeListener { preference, newValue ->
            val locale = newValue as String
            MyPreferences.switchLocale(this@PreferencesActivity, locale)
            true
        }
        val pNewTransactionShortcut: Preference =
            preferenceScreen.findPreference("shortcut_new_transaction")
        pNewTransactionShortcut.setOnPreferenceClickListener { arg0 ->
            addShortcut(
                ".activity.TransactionActivity",
                R.string.transaction,
                R.drawable.icon_transaction
            )
            true
        }
        val pNewTransferShortcut: Preference =
            preferenceScreen.findPreference("shortcut_new_transfer")
        pNewTransferShortcut.setOnPreferenceClickListener {
            addShortcut(".activity.TransferActivity", R.string.transfer, R.drawable.icon_transfer)
            true
        }
        val pDatabaseBackupFolder: Preference =
            preferenceScreen.findPreference("database_backup_folder")
        pDatabaseBackupFolder.setOnPreferenceClickListener {
            if (isRequestingPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                return@setOnPreferenceClickListener false
            }
            selectDatabaseBackupFolder()
            true
        }
        val pGDriveBackupFolder: Preference = preferenceScreen.findPreference("backup_folder")
        pGDriveBackupFolder.setOnPreferenceChangeListener { _, _ ->
            setGDriveBackupFolder()
            true
        }
        val pAuthDropbox: Preference = preferenceScreen.findPreference("dropbox_authorize")
        pAuthDropbox.setOnPreferenceClickListener {
            authDropbox()
            true
        }
        val pDeauthDropbox: Preference = preferenceScreen.findPreference("dropbox_unlink")
        pDeauthDropbox.setOnPreferenceClickListener {
            deAuthDropbox()
            true
        }
        val pExchangeProvider: Preference =
            preferenceScreen.findPreference("exchange_rate_provider")
        pOpenExchangeRatesAppId = preferenceScreen.findPreference("openexchangerates_app_id")
        pExchangeProvider.onPreferenceChangeListener = Preference.OnPreferenceChangeListener() { preference, newValue ->
            pOpenExchangeRatesAppId?.isEnabled = isOpenExchangeRatesProvider(newValue as String?)
            true
        }
        val pDriveAccount: Preference =
            preferenceScreen.findPreference("google_drive_backup_account")
        pDriveAccount.setOnPreferenceClickListener { arg0 ->
            chooseAccount()
            true
        }
        val useFingerprint: Preference =
            preferenceScreen.findPreference("pin_protection_use_fingerprint")
        if (fingerprintUnavailable(this)) {
            useFingerprint.summary = getString(
                R.string.fingerprint_unavailable,
                reasonWhyFingerprintUnavailable(this)
            )
            useFingerprint.isEnabled = false
        }
        linkToDropbox()
        setCurrentDatabaseBackupFolder()
        enableOpenExchangeApp()
        selectAccount()
    }

    private fun chooseAccount() {
        val signInOptions: GoogleSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE), Scope(DriveScopes.DRIVE_APPDATA))
            .build()
        val client: GoogleSignInClient = GoogleSignIn.getClient(this, signInOptions)
        client.signOut().addOnSuccessListener { aVoid ->
            MyPreferences.setGoogleDriveAccount(this@PreferencesActivity, "")
            startActivityForResult(client.getSignInIntent(), CHOOSE_ACCOUNT)
        }
    }

    private val selectedAccount: Account?
        get() {
            val accountName = MyPreferences.getGoogleDriveAccount(this)
            if (accountName != null) {
                val accountManager: AccountManager = AccountManager.get(this)
                val accounts: Array<Account> = accountManager.getAccountsByType("com.google")
                for (account in accounts) {
                    if (accountName.equals(account.name)) {
                        return account
                    }
                }
            }
            return null
        }

    private fun linkToDropbox() {
        val dropboxAuthorized: Boolean = MyPreferences.isDropboxAuthorized(this)
        val preferenceScreen: PreferenceScreen = preferenceScreen
        preferenceScreen.findPreference("dropbox_unlink").isEnabled = dropboxAuthorized
        preferenceScreen.findPreference("dropbox_upload_backup").isEnabled = dropboxAuthorized
        preferenceScreen.findPreference("dropbox_upload_autobackup").isEnabled = dropboxAuthorized
    }

    private fun selectDatabaseBackupFolder() {
        val intent = Intent(this, FolderBrowser::class.java)
        intent.putExtra(FolderBrowser.PATH, databaseBackupFolder)
        startActivityForResult(intent, SELECT_DATABASE_FOLDER)
    }

    private fun enableOpenExchangeApp() {
        pOpenExchangeRatesAppId?.isEnabled = MyPreferences.isOpenExchangeRatesProviderSelected(this)
    }

    private val databaseBackupFolder: String
        get() = Export.getBackupFolder(this).absolutePath

    private fun setCurrentDatabaseBackupFolder() {
        val pDatabaseBackupFolder: Preference =
            getPreferenceScreen().findPreference("database_backup_folder")
        val summary: String =
            getString(R.string.database_backup_folder_summary, databaseBackupFolder)
        pDatabaseBackupFolder.setSummary(summary)
    }

    private fun setGDriveBackupFolder() {
        val pGDriveBackupFolder: Preference = preferenceScreen.findPreference("backup_folder")
        pGDriveBackupFolder.summary = MyPreferences.getBackupFolder(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                SELECT_DATABASE_FOLDER -> {
                    val databaseBackupFolder = data.getStringExtra(FolderBrowser.PATH)
                    MyPreferences.setDatabaseBackupFolder(this, databaseBackupFolder)
                    setCurrentDatabaseBackupFolder()
                }
                CHOOSE_ACCOUNT -> handleSignInResult(data)
            }
        }
    }

    private fun handleSignInResult(intent: Intent) {
        GoogleSignIn.getSignedInAccountFromIntent(intent)
            .addOnSuccessListener { googleSignInAccount ->
                MyPreferences.setGoogleDriveAccount(this, googleSignInAccount.email)
                FinancierApp.driveClient.account = googleSignInAccount.account
                selectAccount()
            }.addOnFailureListener { e ->
            EventBus.getDefault().post(DriveBackupError(e.message))
        }
    }

    private fun selectAccount() {
        val pDriveAccount: Preference =
            getPreferenceScreen().findPreference("google_drive_backup_account")
        val account: Account? = selectedAccount
        if (account != null) {
            pDriveAccount.setSummary(account.name)
        }
    }

    private fun addShortcut(activity: String, nameId: Int, iconId: Int) {
        val intent: Intent = createShortcutIntent(
            activity, getString(nameId), Intent.ShortcutIconResource.fromContext(this, iconId),
            "com.android.launcher.action.INSTALL_SHORTCUT"
        )
        sendBroadcast(intent)
    }

    private fun createShortcutIntent(
        activity: String,
        shortcutName: String,
        shortcutIcon: Intent.ShortcutIconResource,
        action: String
    ): Intent {
        val shortcutIntent = Intent()
        shortcutIntent.component = ComponentName(this.packageName, activity)
        shortcutIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        shortcutIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val intent = Intent()
        intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent)
        intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, shortcutName)
        intent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, shortcutIcon)
        intent.setAction(action)
        return intent
    }

    var dropbox: Dropbox = Dropbox(this)
    private fun authDropbox() {
        dropbox.startAuth()
    }

    private fun deAuthDropbox() {
        dropbox.deAuth()
        linkToDropbox()
    }

    override fun onPause() {
        super.onPause()
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(this)
        PinProtection.lock(this)
    }

    override fun onResume() {
        super.onResume()
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(this)
        PinProtection.unlock(this)
        dropbox.completeAuth()
        linkToDropbox()
    }

    companion object {
        private const val SELECT_DATABASE_FOLDER = 100
        const val CHOOSE_ACCOUNT = 101
    }
}