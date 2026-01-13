package com.termux.api.apis

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.text.TextUtils
import androidx.annotation.RequiresApi
import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE
import java.io.File
import java.io.PrintWriter
import java.util.Locale

object JobSchedulerAPI {

    private const val LOG_TAG = "JobSchedulerAPI"

    private fun formatJobInfo(jobInfo: JobInfo): String {
        val path = jobInfo.extras.getString(JobSchedulerService.SCRIPT_FILE_PATH)
        val description = mutableListOf<String>()
        
        if (jobInfo.isPeriodic) {
            description.add(String.format(Locale.ENGLISH, "(periodic: %dms)", jobInfo.intervalMillis))
        }
        if (jobInfo.isRequireCharging) {
            description.add("(while charging)")
        }
        if (jobInfo.isRequireDeviceIdle) {
            description.add("(while idle)")
        }
        if (jobInfo.isPersisted) {
            description.add("(persisted)")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (jobInfo.isRequireBatteryNotLow) {
                description.add("(battery not low)")
            }
            if (jobInfo.isRequireStorageNotLow) {
                description.add("(storage not low)")
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            description.add(String.format(Locale.ENGLISH, "(network: %s)", jobInfo.requiredNetwork.toString()))
        }

        return String.format(Locale.ENGLISH, "Job %d: %s    %s", jobInfo.id, path,
            TextUtils.join(" ", description))
    }

    @JvmStatic
    fun onReceive(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        val pending = intent.getBooleanExtra("pending", false)
        val cancel = intent.getBooleanExtra("cancel", false)
        val cancelAll = intent.getBooleanExtra("cancel_all", false)

        when {
            pending -> {
                ResultReturner.returnData(apiReceiver, intent) { out ->
                    runDisplayPendingJobsAction(context, out)
                }
            }
            cancelAll -> {
                ResultReturner.returnData(apiReceiver, intent) { out ->
                    runCancelAllJobsAction(context, out)
                }
            }
            cancel -> {
                ResultReturner.returnData(apiReceiver, intent) { out ->
                    runCancelJobAction(context, intent, out)
                }
            }
            else -> {
                ResultReturner.returnData(apiReceiver, intent) { out ->
                    runScheduleJobAction(context, intent, out)
                }
            }
        }
    }

    private fun runScheduleJobAction(context: Context, intent: Intent, out: PrintWriter) {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

        val jobId = intent.getIntExtra("job_id", 0)

        Logger.logVerbose(LOG_TAG, "schedule_job: Running action for job $jobId")

        val scriptPath = intent.getStringExtra("script")
        val networkType = intent.getStringExtra("network")
        val periodicMillis = intent.getIntExtra("period_ms", 0)
        val batteryNotLow = intent.getBooleanExtra("battery_not_low", true)
        val charging = intent.getBooleanExtra("charging", false)
        val persisted = intent.getBooleanExtra("persisted", false)
        val idle = intent.getBooleanExtra("idle", false)
        val storageNotLow = intent.getBooleanExtra("storage_not_low", false)

        val networkTypeCode = when (networkType) {
            "any" -> JobInfo.NETWORK_TYPE_ANY
            "unmetered" -> JobInfo.NETWORK_TYPE_UNMETERED
            "cellular" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                JobInfo.NETWORK_TYPE_CELLULAR
            else
                JobInfo.NETWORK_TYPE_UNMETERED
            "not_roaming" -> JobInfo.NETWORK_TYPE_NOT_ROAMING
            "none" -> JobInfo.NETWORK_TYPE_NONE
            null -> JobInfo.NETWORK_TYPE_ANY
            else -> JobInfo.NETWORK_TYPE_NONE
        }

        if (scriptPath == null) {
            Logger.logErrorPrivate(LOG_TAG, "schedule_job: Script path not passed")
            out.println("No script path given")
            return
        }

        val file = File(scriptPath)
        val fileCheckMsg = when {
            !file.isFile -> "No such file: %s"
            !file.canRead() -> "Cannot read file: %s"
            !file.canExecute() -> "Cannot execute file: %s"
            else -> ""
        }

        if (fileCheckMsg.isNotEmpty()) {
            Logger.logErrorPrivate(LOG_TAG, "schedule_job: ${String.format(fileCheckMsg, scriptPath)}")
            out.println(String.format(fileCheckMsg, scriptPath))
            return
        }

        val extras = PersistableBundle().apply {
            putString(JobSchedulerService.SCRIPT_FILE_PATH, file.absolutePath)
        }

        val serviceComponent = ComponentName(context, JobSchedulerService::class.java)
        var builder = JobInfo.Builder(jobId, serviceComponent)
            .setExtras(extras)
            .setRequiredNetworkType(networkTypeCode)
            .setRequiresCharging(charging)
            .setPersisted(persisted)
            .setRequiresDeviceIdle(idle)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = builder.setRequiresBatteryNotLow(batteryNotLow)
            builder = builder.setRequiresStorageNotLow(storageNotLow)
        }

        if (periodicMillis > 0) {
            // For Android `>= 7`, the minimum period is 900000ms (15 minutes).
            // - https://developer.android.com/reference/android/app/job/JobInfo#getMinPeriodMillis()
            // - https://cs.android.com/android/_/android/platform/frameworks/base/+/10be4e90
            builder = builder.setPeriodic(periodicMillis.toLong())
        }

        val jobInfo = builder.build()
        val scheduleResponse = jobScheduler.schedule(jobInfo)
        val message = String.format(Locale.ENGLISH, "Scheduling %s - response %d", formatJobInfo(jobInfo), scheduleResponse)
        printMessage(out, "schedule_job", message)

        displayPendingJob(out, jobScheduler, "schedule_job", "Pending", jobId)
    }

    private fun runDisplayPendingJobsAction(context: Context, out: PrintWriter) {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

        Logger.logVerbose(LOG_TAG, "display_pending_jobs: Running action")
        displayPendingJobs(out, jobScheduler, "display_pending_jobs", "Pending")
    }

    private fun runCancelAllJobsAction(context: Context, out: PrintWriter) {
        Logger.logVerbose(LOG_TAG, "cancel_all_jobs: Running action")
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val jobsCount = displayPendingJobs(out, jobScheduler, "cancel_all_jobs", "Cancelling")
        if (jobsCount >= 0) {
            Logger.logVerbose(LOG_TAG, "cancel_all_jobs: Cancelling $jobsCount jobs")
            jobScheduler.cancelAll()
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private fun runCancelJobAction(context: Context, intent: Intent, out: PrintWriter) {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

        if (!intent.hasExtra("job_id")) {
            Logger.logErrorPrivate(LOG_TAG, "cancel_job: Job id not passed")
            out.println("Job id not passed")
            return
        }

        val jobId = intent.getIntExtra("job_id", 0)
        Logger.logVerbose(LOG_TAG, "cancel_job: Running action for job $jobId")

        if (displayPendingJob(out, jobScheduler, "cancel_job", "Cancelling", jobId)) {
            Logger.logVerbose(LOG_TAG, "cancel_job: Cancelling job $jobId")
            jobScheduler.cancel(jobId)
        }
    }

    private fun displayPendingJob(
        out: PrintWriter,
        jobScheduler: JobScheduler,
        actionTag: String,
        actionLabel: String,
        jobId: Int
    ): Boolean {
        val jobInfo = jobScheduler.getPendingJob(jobId)
        if (jobInfo == null) {
            printMessage(out, actionTag, String.format(Locale.ENGLISH, "No job %d found", jobId))
            return false
        }

        printMessage(out, actionTag, String.format(Locale.ENGLISH, "$actionLabel %s", formatJobInfo(jobInfo)))
        return true
    }

    private fun displayPendingJobs(
        out: PrintWriter,
        jobScheduler: JobScheduler,
        actionTag: String,
        actionLabel: String
    ): Int {
        val jobs = jobScheduler.allPendingJobs
        if (jobs.isEmpty()) {
            printMessage(out, actionTag, "No jobs found")
            return 0
        }

        val stringBuilder = StringBuilder()
        var jobAdded = false
        for (job in jobs) {
            if (jobAdded) stringBuilder.append("\n")
            stringBuilder.append(String.format(Locale.ENGLISH, "$actionLabel %s", formatJobInfo(job)))
            jobAdded = true
        }
        printMessage(out, actionTag, stringBuilder.toString())

        return jobs.size
    }

    private fun printMessage(out: PrintWriter, actionTag: String, message: String) {
        Logger.logVerbose(LOG_TAG, "$actionTag: $message")
        out.println(message)
    }

    class JobSchedulerService : JobService() {

        override fun onStartJob(params: JobParameters): Boolean {
            Logger.logInfo(SERVICE_LOG_TAG, "onStartJob: $params")

            val extras = params.extras
            val filePath = extras.getString(SCRIPT_FILE_PATH)

            val executionCommand = ExecutionCommand().apply {
                executableUri = Uri.Builder()
                    .scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE)
                    .path(filePath)
                    .build()
                runner = ExecutionCommand.Runner.APP_SHELL.getName()
            }

            // Create execution intent with the action TERMUX_SERVICE#ACTION_SERVICE_EXECUTE to be sent to the TERMUX_SERVICE
            val executionIntent = Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, executionCommand.executableUri).apply {
                setClassName(TermuxConstants.TERMUX_PACKAGE_NAME, TermuxConstants.TERMUX_APP.TERMUX_SERVICE_NAME)
                putExtra(TERMUX_SERVICE.EXTRA_RUNNER, executionCommand.runner)
                putExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, true) // Also pass in case user using termux-app version < 0.119.0
            }

            val context = applicationContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // https://developer.android.com/about/versions/oreo/background.html
                context.startForegroundService(executionIntent)
            } else {
                context.startService(executionIntent)
            }

            Logger.logInfo(SERVICE_LOG_TAG, "Job started for \"$filePath\"")

            return false
        }

        override fun onStopJob(params: JobParameters): Boolean {
            Logger.logInfo(SERVICE_LOG_TAG, "onStopJob: $params")
            return false
        }

        companion object {
            const val SCRIPT_FILE_PATH = "${TermuxConstants.TERMUX_API_PACKAGE_NAME}.jobscheduler_script_path"
            private const val SERVICE_LOG_TAG = "JobSchedulerService"
        }
    }
}
