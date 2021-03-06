package eu.kanade.tachiyomi.ui.setting

import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.app.Activity
import android.app.Dialog
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.preference.PreferenceScreen
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItemsMultiChoice
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.BackupConst
import eu.kanade.tachiyomi.data.backup.BackupCreateService
import eu.kanade.tachiyomi.data.backup.BackupCreatorJob
import eu.kanade.tachiyomi.data.backup.BackupRestoreService
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.base.controller.popControllerWithTag
import eu.kanade.tachiyomi.ui.base.controller.requestPermissionsSafe
import eu.kanade.tachiyomi.util.*
import java.io.File
import java.util.concurrent.TimeUnit
import eu.kanade.tachiyomi.data.preference.PreferenceKeys as Keys

class SettingsBackupController : SettingsController() {

    /**
     * Flags containing information of what to backup.
     */
    private var backupFlags = 0

    private val receiver = BackupBroadcastReceiver()

    init {
        preferences.context.registerLocalReceiver(receiver, IntentFilter(BackupConst.INTENT_FILTER))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requestPermissionsSafe(arrayOf(WRITE_EXTERNAL_STORAGE), 500)
    }

    override fun onDestroy() {
        super.onDestroy()
        preferences.context.unregisterLocalReceiver(receiver)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) = with(screen) {
        titleRes = R.string.backup

        preference {
            titleRes = R.string.pref_create_backup
            summaryRes = R.string.pref_create_backup_summ

            onClick {
                val ctrl = CreateBackupDialog()
                ctrl.targetController = this@SettingsBackupController
                ctrl.showDialog(router)
            }
        }
        preference {
            titleRes = R.string.pref_restore_backup
            summaryRes = R.string.pref_restore_backup_summ

            onClick {
                val intent = Intent(Intent.ACTION_GET_CONTENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "application/*"
                val title = resources?.getString(R.string.file_select_backup)
                val chooser = Intent.createChooser(intent, title)
                startActivityForResult(chooser, CODE_BACKUP_RESTORE)
            }
        }
        preferenceCategory {
            titleRes = R.string.pref_backup_service_category

            intListPreference {
                key = Keys.backupInterval
                titleRes = R.string.pref_backup_interval
                entriesRes = arrayOf(R.string.update_never, R.string.update_6hour,
                        R.string.update_12hour, R.string.update_24hour,
                        R.string.update_48hour, R.string.update_weekly)
                entryValues = arrayOf("0", "6", "12", "24", "48", "168")
                defaultValue = "0"
                summary = "%s"

                onChange { newValue ->
                    // Always cancel the previous task, it seems that sometimes they are not updated
                    BackupCreatorJob.cancelTask()

                    val interval = (newValue as String).toInt()
                    if (interval > 0) {
                        BackupCreatorJob.setupTask(interval)
                    }
                    true
                }
            }
            val backupDir = preference {
                key = Keys.backupDirectory
                titleRes = R.string.pref_backup_directory

                onClick {
                    val currentDir = preferences.backupsDirectory().getOrDefault()
                    try{
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        startActivityForResult(intent, CODE_BACKUP_DIR)
                    } catch (e: ActivityNotFoundException){
                        // Fall back to custom picker on error
                        startActivityForResult(preferences.context.getFilePicker(currentDir), CODE_BACKUP_DIR)
                    }
                }

                preferences.backupsDirectory().asObservable()
                        .subscribeUntilDestroy { path ->
                            val dir = UniFile.fromUri(context, Uri.parse(path))
                            summary = dir.filePath + "/automatic"
                        }
            }
            val backupNumber = intListPreference {
                key = Keys.numberOfBackups
                titleRes = R.string.pref_backup_slots
                entries = arrayOf("1", "2", "3", "4", "5")
                entryValues = entries
                defaultValue = "1"
                summary = "%s"
            }

            preferences.backupInterval().asObservable()
                    .subscribeUntilDestroy {
                        backupDir.isVisible = it > 0
                        backupNumber.isVisible = it > 0
                    }
        }

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            CODE_BACKUP_DIR -> if (data != null && resultCode == Activity.RESULT_OK) {
                val activity = activity ?: return
                // Get uri of backup folder.
                val uri = data.data

                // Get UriPermission so it's possible to write files
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION

                if (uri != null) {
                    activity.contentResolver.takePersistableUriPermission(uri, flags)
                }

                // Set backup Uri
                preferences.backupsDirectory().set(uri.toString())
            }
            CODE_BACKUP_CREATE -> if (data != null && resultCode == Activity.RESULT_OK) {
                val activity = activity ?: return

                val uri = data.data
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION

                if (uri != null) {
                    activity.contentResolver.takePersistableUriPermission(uri, flags)
                }

                val file = UniFile.fromUri(activity, uri)

                BackupCreateService.makeBackup(activity, file.uri, backupFlags)
            }
            CODE_BACKUP_RESTORE -> if (data != null && resultCode == Activity.RESULT_OK) {
                val uri = data.data
                if (uri != null)
                    RestoreBackupDialog(uri).showDialog(router)
            }
        }
    }

    fun createBackup(flags: Int) {
        backupFlags = flags

        // Setup custom file picker intent
        // Get dirs
        val currentDir = preferences.backupsDirectory().getOrDefault()

        try {
            // Use Android's built-in file creator
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/*")
                    .putExtra(Intent.EXTRA_TITLE, Backup.getDefaultFilename())

            startActivityForResult(intent, CODE_BACKUP_CREATE)
        } catch (e: ActivityNotFoundException) {
            // Handle errors where the android ROM doesn't support the built in picker
            startActivityForResult(preferences.context.getFilePicker(currentDir), CODE_BACKUP_CREATE)
        }
    }

    class CreateBackupDialog : DialogController() {
        override fun onCreateDialog(savedViewState: Bundle?): Dialog {
            val activity = activity!!
            val options = arrayOf(R.string.manga, R.string.categories, R.string.chapters,
                    R.string.track, R.string.history)
                    .map { activity.getString(it) }

            return MaterialDialog(activity)
                    .title(R.string.pref_create_backup)
                    .message(R.string.backup_choice)
                .listItemsMultiChoice(items = options, disabledIndices = intArrayOf(0),
                    initialSelection = intArrayOf(0))
                    { _, positions, _ ->
                        var flags = 0
                        for (i in 1 until positions.size) {
                            when (positions[i]) {
                                1 -> flags = flags or BackupCreateService.BACKUP_CATEGORY
                                2 -> flags = flags or BackupCreateService.BACKUP_CHAPTER
                                3 -> flags = flags or BackupCreateService.BACKUP_TRACK
                                4 -> flags = flags or BackupCreateService.BACKUP_HISTORY
                            }
                        }

                        (targetController as? SettingsBackupController)?.createBackup(flags)
                    }
                    .positiveButton(R.string.action_create)
                    .negativeButton(android.R.string.cancel)
        }
    }

    class CreatedBackupDialog(bundle: Bundle? = null) : DialogController(bundle) {
        constructor(uri: Uri) : this(Bundle().apply {
            putParcelable(KEY_URI, uri)
        })

        override fun onCreateDialog(savedViewState: Bundle?): Dialog {
            val activity = activity!!
            val unifile = UniFile.fromUri(activity, args.getParcelable(KEY_URI))
            return MaterialDialog(activity)
                    .title(R.string.backup_created)
                    .message(R.string.file_saved, unifile.filePath)
                    .positiveButton(R.string.action_close)
                    .negativeButton(R.string.action_export) {
                        val sendIntent = Intent(Intent.ACTION_SEND)
                        sendIntent.type = "application/json"
                        sendIntent.putExtra(Intent.EXTRA_STREAM, unifile.uri)
                        startActivity(Intent.createChooser(sendIntent, ""))
                    }
        }

        private companion object {
            const val KEY_URI = "BackupCreatedDialog.uri"
        }
    }

    class RestoreBackupDialog(bundle: Bundle? = null) : DialogController(bundle) {
        constructor(uri: Uri) : this(Bundle().apply {
            putParcelable(KEY_URI, uri)
        })

        override fun onCreateDialog(savedViewState: Bundle?): Dialog {
            return MaterialDialog(activity!!)
                    .title(R.string.pref_restore_backup)
                    .message(R.string.backup_restore_content)
                    .positiveButton(R.string.action_restore) {
                        val context = applicationContext
                        if (context != null) {
                            activity?.toast(R.string.restoring_backup)
                            BackupRestoreService.start(context, args.getParcelable(KEY_URI)!!)
                        }
                    }
        }

        private companion object {
            const val KEY_URI = "RestoreBackupDialog.uri"
        }
    }

    inner class BackupBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(BackupConst.ACTION)) {
                BackupConst.ACTION_BACKUP_COMPLETED_DIALOG -> {
                    router.popControllerWithTag(TAG_CREATING_BACKUP_DIALOG)
                    val uri = Uri.parse(intent.getStringExtra(BackupConst.EXTRA_URI))
                    CreatedBackupDialog(uri).showDialog(router)
                }
                BackupConst.ACTION_ERROR_BACKUP_DIALOG -> {
                    router.popControllerWithTag(TAG_CREATING_BACKUP_DIALOG)
                    context.toast(intent.getStringExtra(BackupConst.EXTRA_ERROR_MESSAGE))
                }
                BackupConst.ACTION_ERROR_RESTORE_DIALOG -> {
                    router.popControllerWithTag(TAG_RESTORING_BACKUP_DIALOG)
                    context.toast(intent.getStringExtra(BackupConst.EXTRA_ERROR_MESSAGE))
                }
            }
        }
    }

    private companion object {
        const val CODE_BACKUP_CREATE = 501
        const val CODE_BACKUP_RESTORE = 502
        const val CODE_BACKUP_DIR = 503

        const val TAG_CREATING_BACKUP_DIALOG = "CreatingBackupDialog"
        const val TAG_RESTORING_BACKUP_DIALOG = "RestoringBackupDialog"
    }

}
