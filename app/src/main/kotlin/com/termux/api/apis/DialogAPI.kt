package com.termux.api.apis

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.util.JsonWriter
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.DatePicker
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.termux.api.R
import com.termux.api.activities.TermuxApiPermissionActivity
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.theme.TermuxThemeUtils
import com.termux.shared.theme.NightMode
import com.termux.shared.theme.ThemeUtils
import com.termux.shared.view.KeyboardUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * API that allows receiving user input interactively in a variety of different ways
 */
object DialogAPI {

    private const val LOG_TAG = "DialogAPI"

    @JvmStatic
    fun onReceive(context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        context.startActivity(
            Intent(context, DialogActivity::class.java)
                .putExtras(intent.extras!!)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    class DialogActivity : AppCompatActivity() {

        @Volatile
        private var resultReturned = false
        private var mInputMethod: InputMethod? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            Logger.logDebug(ACTIVITY_LOG_TAG, "onCreate")

            super.onCreate(savedInstanceState)
            val intent = intent
            val context: Context = this

            val methodType = if (intent.hasExtra("input_method")) intent.getStringExtra("input_method") else ""

            // Set NightMode.APP_NIGHT_MODE
            TermuxThemeUtils.setAppNightMode(context)
            val shouldEnableDarkTheme = ThemeUtils.shouldEnableDarkTheme(this, NightMode.getAppNightMode().name)
            if (shouldEnableDarkTheme) {
                this.setTheme(R.style.DialogTheme_Dark)
            }

            mInputMethod = InputMethodFactory.get(methodType, this)
            if (mInputMethod != null) {
                mInputMethod!!.create(this) { result ->
                    postResult(context, result)
                    finish()
                }
            } else {
                val result = InputResult().apply {
                    error = "Unknown Input Method: $methodType"
                }
                postResult(context, result)
            }
        }

        override fun onNewIntent(intent: Intent) {
            Logger.logDebug(ACTIVITY_LOG_TAG, "onNewIntent")
            super.onNewIntent(intent)
            setIntent(intent)
        }

        override fun onDestroy() {
            Logger.logDebug(ACTIVITY_LOG_TAG, "onDestroy")
            super.onDestroy()

            postResult(this, null)

            mInputMethod?.inputMethodDialog?.let { dismissDialog(it) }
        }

        /**
         * Writes the InputResult to the console
         */
        @Synchronized
        fun postResult(context: Context, resultParam: InputResult?) {
            if (resultReturned) {
                Logger.logDebug(ACTIVITY_LOG_TAG, "Ignoring call to postResult")
                return
            } else {
                Logger.logDebug(ACTIVITY_LOG_TAG, "postResult")
            }

            ResultReturner.returnData(context, intent, object : ResultReturner.ResultJsonWriter() {
                override fun writeJson(out: JsonWriter) {
                    out.beginObject()

                    val result = resultParam ?: InputResult().apply {
                        code = Dialog.BUTTON_NEGATIVE
                    }

                    out.name("code").value(result.code.toLong())
                    out.name("text").value(result.text)
                    if (result.index > -1) {
                        out.name("index").value(result.index.toLong())
                    }
                    if (result.values.isNotEmpty()) {
                        out.name("values")
                        out.beginArray()
                        for (value in result.values) {
                            out.beginObject()
                            out.name("index").value(value.index.toLong())
                            out.name("text").value(value.text)
                            out.endObject()
                        }
                        out.endArray()
                    }
                    if (result.error.isNotEmpty()) {
                        out.name("error").value(result.error)
                    }

                    out.endObject()
                    out.flush()
                    resultReturned = true
                }
            })
        }

        companion object {
            private const val ACTIVITY_LOG_TAG = "DialogActivity"

            internal fun dismissDialog(dialog: Dialog) {
                try {
                    dialog.dismiss()
                } catch (e: Exception) {
                    Logger.logStackTraceWithMessage(ACTIVITY_LOG_TAG, "Failed to dismiss dialog", e)
                }
            }

            /**
             * Extract value extras from intent into String array
             */
            fun getInputValues(intent: Intent?): Array<String> {
                if (intent == null || !intent.hasExtra("input_values")) {
                    return arrayOf()
                }

                val temp = intent.getStringExtra("input_values")!!.split("(?<!\\\\),".toRegex())
                return temp.map { s -> s.trim().replace("\\,", ",") }.toTypedArray()
            }
        }
    }

    /**
     * Factory for returning proper input method type that we received in our incoming intent
     */
    object InputMethodFactory {
        fun get(type: String?, activity: AppCompatActivity): InputMethod? {
            return when (type ?: "") {
                "confirm" -> ConfirmInputMethod(activity)
                "checkbox" -> CheckBoxInputMethod(activity)
                "counter" -> CounterInputMethod(activity)
                "date" -> DateInputMethod(activity)
                "radio" -> RadioInputMethod(activity)
                "sheet" -> BottomSheetInputMethod()
                "speech" -> SpeechInputMethod(activity)
                "spinner" -> SpinnerInputMethod(activity)
                "text" -> TextInputMethod(activity)
                "time" -> TimeInputMethod(activity)
                else -> null
            }
        }
    }

    /**
     * Interface for creating an input method type
     */
    interface InputMethod {
        val inputMethodDialog: Dialog?
        fun create(activity: AppCompatActivity, resultListener: InputResultListener)
    }

    /**
     * Callback interface for receiving an InputResult
     */
    fun interface InputResultListener {
        fun onResult(result: InputResult)
    }

    /**
     * Simple POJO to store the result of input methods
     */
    class InputResult {
        var text: String = ""
        var error: String = ""
        var code: Int = 0
        var index: Int = -1
        var values: MutableList<Value> = mutableListOf()
    }

    class Value {
        var index: Int = -1
        var text: String = ""
    }

    /*
     * --------------------------------------
     * InputMethod Implementations
     * --------------------------------------
     */

    /**
     * CheckBox InputMethod
     * Allow users to select multiple options from a range of values
     */
    class CheckBoxInputMethod(activity: AppCompatActivity) : InputDialog<LinearLayout>(activity) {

        override fun createWidgetView(activity: AppCompatActivity): LinearLayout {
            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }

            val layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 32
                bottomMargin = 32
            }

            val values = DialogActivity.getInputValues(activity.intent)

            for (j in values.indices) {
                val value = values[j]

                val checkBox = CheckBox(activity).apply {
                    text = value
                    id = j
                    textSize = 18f
                    setPadding(16, 16, 16, 16)
                    setLayoutParams(layoutParams)
                }

                layout.addView(checkBox)
            }
            return layout
        }

        override fun getResult(): String {
            val checkBoxCount = widgetView.childCount

            val values = mutableListOf<Value>()
            val sb = StringBuilder("[")

            for (j in 0 until checkBoxCount) {
                val box = widgetView.findViewById<CheckBox>(j)
                if (box.isChecked) {
                    val value = Value().apply {
                        index = j
                        text = box.text.toString()
                    }
                    values.add(value)
                    sb.append(box.text.toString()).append(", ")
                }
            }
            inputResult.values = values
            // remove trailing comma and add closing bracket
            return sb.toString().replace(", $".toRegex(), "") + "]"
        }
    }

    /**
     * Confirm InputMethod
     * Allow users to confirm YES or NO.
     */
    class ConfirmInputMethod(activity: AppCompatActivity) : InputDialog<TextView>(activity) {

        override fun onDialogClick(button: Int): InputResult {
            inputResult.text = if (button == Dialog.BUTTON_POSITIVE) "yes" else "no"
            return inputResult
        }

        override fun createWidgetView(activity: AppCompatActivity): TextView {
            val textView = TextView(activity)
            val intent = activity.intent

            val text = if (intent.hasExtra("input_hint")) intent.getStringExtra("input_hint") else "Confirm"
            textView.text = text
            return textView
        }

        override fun getNegativeButtonText(): String = "No"

        override fun getPositiveButtonText(): String = "Yes"
    }

    /**
     * Counter InputMethod
     * Allow users to increment or decrement a number in a given range
     */
    class CounterInputMethod(activity: AppCompatActivity) : InputDialog<View>(activity) {

        private var min: Int = 0
        private var max: Int = 0
        private var counter: Int = 0
        private lateinit var counterLabel: TextView

        override fun createWidgetView(activity: AppCompatActivity): View {
            val layout = View.inflate(activity, R.layout.dialog_counter, null)
            counterLabel = layout.findViewById(R.id.counterTextView)

            val incrementButton = layout.findViewById<Button>(R.id.incrementButton)
            incrementButton.setOnClickListener { increment() }

            val decrementButton = layout.findViewById<Button>(R.id.decrementButton)
            decrementButton.setOnClickListener { decrement() }
            updateCounterRange()

            return layout
        }

        private fun updateCounterRange() {
            val intent = activity.intent

            if (intent.hasExtra("input_range")) {
                val values = intent.getIntArrayExtra("input_range")!!
                if (values.size != RANGE_LENGTH) {
                    inputResult.error = "Invalid range! Must be 3 int values!"
                    postCanceledResult()
                    dialog?.let { DialogActivity.dismissDialog(it) }
                } else {
                    min = minOf(values[0], values[1])
                    max = maxOf(values[0], values[1])
                    counter = values[2]
                }
            } else {
                min = DEFAULT_MIN
                max = DEFAULT_MAX
                // halfway
                counter = (DEFAULT_MAX - DEFAULT_MIN) / 2
            }
            updateLabel()
        }

        override fun getResult(): String = counterLabel.text.toString()

        private fun updateLabel() {
            counterLabel.text = counter.toString()
        }

        private fun increment() {
            if ((counter + 1) <= max) {
                ++counter
                updateLabel()
            }
        }

        private fun decrement() {
            if ((counter - 1) >= min) {
                --counter
                updateLabel()
            }
        }

        companion object {
            private const val DEFAULT_MIN = 0
            private const val DEFAULT_MAX = 100
            private const val RANGE_LENGTH = 3
        }
    }

    /**
     * Date InputMethod
     * Allow users to pick a specific date
     */
    class DateInputMethod(activity: AppCompatActivity) : InputDialog<DatePicker>(activity) {

        override fun getResult(): String {
            val month = widgetView.month
            val day = widgetView.dayOfMonth
            val year = widgetView.year

            val calendar = Calendar.getInstance().apply {
                set(year, month, day, 0, 0, 0)
            }

            val intent = activity.intent
            if (intent.hasExtra("date_format")) {
                val dateFormat = intent.getStringExtra("date_format")
                try {
                    val simpleDateFormat = SimpleDateFormat(dateFormat, Locale.getDefault())
                    simpleDateFormat.timeZone = calendar.timeZone
                    return simpleDateFormat.format(calendar.time)
                } catch (e: Exception) {
                    inputResult.error = e.toString()
                    postCanceledResult()
                }
            }
            return calendar.time.toString()
        }

        override fun createWidgetView(activity: AppCompatActivity): DatePicker = DatePicker(activity)
    }

    /**
     * Text InputMethod
     * Allow users to enter plaintext or a password
     */
    class TextInputMethod(activity: AppCompatActivity) : InputDialog<EditText>(activity) {

        override fun getResult(): String = widgetView.text.toString()

        override fun createWidgetView(activity: AppCompatActivity): EditText {
            val intent = activity.intent
            val editText = EditText(activity)

            if (intent.hasExtra("input_hint")) {
                editText.hint = intent.getStringExtra("input_hint")
            }

            val multiLine = intent.getBooleanExtra("multiple_lines", false)
            val numeric = intent.getBooleanExtra("numeric", false)
            val password = intent.getBooleanExtra("password", false)

            var flags = InputType.TYPE_CLASS_TEXT

            if (password) {
                flags = if (numeric) {
                    flags or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                } else {
                    flags or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }

            if (multiLine) {
                flags = flags or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                editText.setLines(4)
            }

            if (numeric) {
                flags = flags and InputType.TYPE_CLASS_TEXT.inv() // clear to allow only numbers
                flags = flags or InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }

            editText.inputType = flags

            return editText
        }
    }

    /**
     * Time InputMethod
     * Allow users to pick a specific time
     */
    class TimeInputMethod(activity: AppCompatActivity) : InputDialog<TimePicker>(activity) {

        override fun getResult(): String =
            String.format(Locale.getDefault(), "%02d:%02d", widgetView.hour, widgetView.minute)

        override fun createWidgetView(activity: AppCompatActivity): TimePicker = TimePicker(activity)
    }

    /**
     * Radio InputMethod
     * Allow users to confirm from radio button options
     */
    class RadioInputMethod(activity: AppCompatActivity) : InputDialog<RadioGroup>(activity) {

        private lateinit var radioGroup: RadioGroup

        override fun createWidgetView(activity: AppCompatActivity): RadioGroup {
            radioGroup = RadioGroup(activity).apply {
                setPadding(16, 16, 16, 16)
            }

            val layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 32
                bottomMargin = 32
            }

            val values = DialogActivity.getInputValues(activity.intent)

            for (j in values.indices) {
                val value = values[j]

                val button = RadioButton(activity).apply {
                    text = value
                    id = j
                    textSize = 18f
                    setPadding(16, 16, 16, 16)
                    setLayoutParams(layoutParams)
                }

                radioGroup.addView(button)
            }
            return radioGroup
        }

        override fun getResult(): String {
            val radioIndex = radioGroup.indexOfChild(widgetView.findViewById(radioGroup.checkedRadioButtonId))
            val radioButton = radioGroup.getChildAt(radioIndex) as? RadioButton
            inputResult.index = radioIndex
            return radioButton?.text?.toString() ?: ""
        }
    }

    /**
     * BottomSheet InputMethod
     * Allow users to select from a variety of options in a bottom sheet dialog
     */
    class BottomSheetInputMethod : BottomSheetDialogFragment(), InputMethod {
        private lateinit var resultListener: InputResultListener

        override val inputMethodDialog: Dialog?
            get() = getDialog()

        override fun create(activity: AppCompatActivity, resultListener: InputResultListener) {
            this.resultListener = resultListener
            show(activity.supportFragmentManager, "BOTTOM_SHEET")
        }

        @NonNull
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            // create custom BottomSheetDialog that has friendlier dismissal behavior
            return object : BottomSheetDialog(requireActivity(), theme) {
                @Deprecated("Deprecated in Java")
                override fun onBackPressed() {
                    super.onBackPressed()
                    // make it so that user only has to hit back key one time to get rid of bottom sheet
                    requireActivity().onBackPressed()
                    postCanceledResult()
                }

                override fun cancel() {
                    super.cancel()

                    if (isCurrentAppTermux()) {
                        showKeyboard()
                    }
                    // dismiss on single touch outside of dialog
                    requireActivity().onBackPressed()
                    postCanceledResult()
                }
            }
        }

        @SuppressLint("RestrictedApi")
        override fun setupDialog(dialog: Dialog, style: Int) {
            val layout = LinearLayout(context).apply {
                minimumHeight = 100
                setPadding(16, 16, 16, 16)
                orientation = LinearLayout.VERTICAL
            }

            val scrollView = NestedScrollView(requireContext())
            val values = DialogActivity.getInputValues(requireActivity().intent)

            for (i in values.indices) {
                val j = i
                val textView = TextView(context).apply {
                    text = values[j]
                    textSize = 20f
                    setPadding(56, 56, 56, 56)
                    setOnClickListener {
                        val result = InputResult().apply {
                            text = values[j]
                            index = j
                        }
                        dismissDialog(dialog)
                        resultListener.onResult(result)
                    }
                }

                layout.addView(textView)
            }
            scrollView.addView(layout)
            dialog.setContentView(scrollView)
            hideKeyboard()
        }

        private fun hideKeyboard() {
            KeyboardUtils.setSoftKeyboardAlwaysHiddenFlags(activity)
        }

        private fun showKeyboard() {
            KeyboardUtils.showSoftKeyboard(activity, view)
        }

        private fun isCurrentAppTermux(): Boolean {
            val activityManager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningProcesses = activityManager.runningAppProcesses ?: return false
            for (processInfo in runningProcesses) {
                if (processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    for (activeProcess in processInfo.pkgList) {
                        if (activeProcess == TermuxConstants.TERMUX_PACKAGE_NAME) {
                            return true
                        }
                    }
                }
            }
            return false
        }

        private fun postCanceledResult() {
            val result = InputResult().apply {
                code = Dialog.BUTTON_NEGATIVE
            }
            resultListener.onResult(result)
        }

        companion object {
            private fun dismissDialog(dialog: Dialog) {
                try {
                    dialog.dismiss()
                } catch (e: Exception) {
                    Logger.logStackTraceWithMessage("BottomSheetInputMethod", "Failed to dismiss dialog", e)
                }
            }
        }
    }

    /**
     * Spinner InputMethod
     * Allow users to make a selection based on a list of specified values
     */
    class SpinnerInputMethod(activity: AppCompatActivity) : InputDialog<Spinner>(activity) {

        override fun getResult(): String {
            inputResult.index = widgetView.selectedItemPosition
            return widgetView.selectedItem.toString()
        }

        override fun createWidgetView(activity: AppCompatActivity): Spinner {
            val spinner = Spinner(activity)

            val intent = activity.intent
            val items = DialogActivity.getInputValues(intent)
            val adapter = ArrayAdapter(activity, R.layout.spinner_item, items)

            spinner.adapter = adapter
            return spinner
        }
    }

    /**
     * Speech InputMethod
     * Allow users to use the built in microphone to get text from speech
     */
    class SpeechInputMethod(activity: AppCompatActivity) : InputDialog<TextView>(activity) {

        override fun createWidgetView(activity: AppCompatActivity): TextView {
            val textView = TextView(activity)
            val intent = activity.intent

            val text = if (intent.hasExtra("input_hint")) intent.getStringExtra("input_hint") else "Listening for speech..."

            textView.text = text
            textView.textSize = 20f
            return textView
        }

        override fun create(activity: AppCompatActivity, resultListener: InputResultListener) {
            // Since we're using the microphone, we need to make sure we have proper permission
            if (!TermuxApiPermissionActivity.checkAndRequestPermissions(activity, activity.intent, Manifest.permission.RECORD_AUDIO)) {
                activity.finish()
            }

            if (!hasSpeechRecognizer(activity)) {
                Toast.makeText(activity, "No voice recognition found!", Toast.LENGTH_SHORT).show()
                activity.finish()
            }

            val speechIntent = createSpeechIntent()
            val recognizer = createSpeechRecognizer(activity, resultListener)

            // create intermediate InputResultListener so that we can stop our speech listening
            // if user hits the cancel button
            val clickListener = getClickListener { result ->
                recognizer.stopListening()
                resultListener.onResult(result)
            }

            val dialog = getDialogBuilder(activity, clickListener)
                .setPositiveButton(null, null)
                .setOnDismissListener(null)
                .create()

            dialog.setCanceledOnTouchOutside(false)
            dialog.show()

            recognizer.startListening(speechIntent)
        }

        private fun hasSpeechRecognizer(context: Context): Boolean {
            val installList = context.packageManager.queryIntentActivities(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0
            )
            return installList.isNotEmpty()
        }

        private fun createSpeechIntent(): Intent {
            return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            }
        }

        private fun createSpeechRecognizer(activity: AppCompatActivity, listener: InputResultListener): SpeechRecognizer {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(activity)
            recognizer.setRecognitionListener(object : RecognitionListener {

                override fun onResults(results: Bundle) {
                    val voiceResults = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)

                    if (!voiceResults.isNullOrEmpty()) {
                        inputResult.text = voiceResults[0]
                    }
                    listener.onResult(inputResult)
                }

                override fun onError(error: Int) {
                    val errorDescription = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
                        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
                        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
                        else -> "ERROR_UNKNOWN"
                    }
                    inputResult.error = errorDescription
                    listener.onResult(inputResult)
                }

                // unused
                override fun onEndOfSpeech() {}
                override fun onReadyForSpeech(bundle: Bundle) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(bytes: ByteArray) {}
                override fun onPartialResults(bundle: Bundle) {}
                override fun onEvent(i: Int, bundle: Bundle) {}
            })
            return recognizer
        }
    }

    /**
     * Base Dialog class to extend from for adding specific views / widgets to a Dialog interface
     * @param T Main view type that will be displayed within dialog
     */
    abstract class InputDialog<T : View>(protected val activity: AppCompatActivity) : InputMethod {

        // result that belongs to us
        protected val inputResult = InputResult()

        // listener for our input result
        protected lateinit var resultListener: InputResultListener

        // view that will be placed in our dialog
        protected lateinit var widgetView: T

        // dialog that holds everything
        override var inputMethodDialog: Dialog? = null
            protected set

        init {
            @Suppress("LeakingThis")
            widgetView = createWidgetView(activity)
            initActivityDisplay(activity)
        }

        // method to be implemented that handles creating view that is placed in our dialog
        protected abstract fun createWidgetView(activity: AppCompatActivity): T

        // method that should be implemented that handles returning a result obtained through user input
        protected open fun getResult(): String? = null

        override fun create(activity: AppCompatActivity, resultListener: InputResultListener) {
            this.resultListener = resultListener

            // Handle OK and Cancel button clicks
            val clickListener = getClickListener(resultListener)

            // Dialog interface that will display to user
            inputMethodDialog = getDialogBuilder(activity, clickListener).create()
            inputMethodDialog?.show()
        }

        protected fun postCanceledResult() {
            inputResult.code = Dialog.BUTTON_NEGATIVE
            resultListener.onResult(inputResult)
        }

        private fun initActivityDisplay(activity: Activity) {
            activity.setFinishOnTouchOutside(false)
            activity.requestWindowFeature(Window.FEATURE_NO_TITLE)
        }

        /**
         * Places our generic widget view type inside a FrameLayout
         */
        private fun getLayoutView(activity: AppCompatActivity, view: T): View {
            val layout = getFrameLayout(activity)
            val params = layout.layoutParams

            view.layoutParams = params
            layout.addView(view)
            layout.isScrollbarFadingEnabled = false

            // wrap everything in scrollview
            val scrollView = ScrollView(activity)
            scrollView.addView(layout)

            return scrollView
        }

        protected fun getClickListener(listener: InputResultListener): DialogInterface.OnClickListener {
            return DialogInterface.OnClickListener { _, button ->
                val result = onDialogClick(button)
                listener.onResult(result)
            }
        }

        private fun getDismissListener(): DialogInterface.OnDismissListener {
            return DialogInterface.OnDismissListener {
                // force dismiss behavior on single tap outside of dialog
                activity.onBackPressed()
                onDismissed()
            }
        }

        /**
         * Creates a dialog builder to initialize a dialog w/ a view and button click listeners
         */
        protected fun getDialogBuilder(activity: AppCompatActivity, clickListener: DialogInterface.OnClickListener): AlertDialog.Builder {
            val intent = activity.intent
            val layoutView = getLayoutView(activity, widgetView)

            return AlertDialog.Builder(activity)
                .setTitle(if (intent.hasExtra("input_title")) intent.getStringExtra("input_title") else "")
                .setNegativeButton(getNegativeButtonText(), clickListener)
                .setPositiveButton(getPositiveButtonText(), clickListener)
                .setOnDismissListener(getDismissListener())
                .setView(layoutView)
        }

        protected open fun getNegativeButtonText(): String = "Cancel"

        protected open fun getPositiveButtonText(): String = "OK"

        private fun onDismissed() {
            postCanceledResult()
        }

        /**
         * Create a basic frame layout that will add a margin around our main widget view
         */
        private fun getFrameLayout(activity: AppCompatActivity): FrameLayout {
            val layout = FrameLayout(activity)
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            val margin = 56
            params.setMargins(margin, margin, margin, margin)

            layout.layoutParams = params
            return layout
        }

        /**
         * Returns an InputResult containing code of our button and the text if we hit OK
         */
        protected open fun onDialogClick(button: Int): InputResult {
            // receive indication of whether the OK or CANCEL button is clicked
            inputResult.code = button

            // OK clicked
            if (button == Dialog.BUTTON_POSITIVE) {
                inputResult.text = getResult() ?: ""
            }
            return inputResult
        }

        companion object {
            fun dismissDialog(dialog: Dialog?) {
                try {
                    dialog?.dismiss()
                } catch (e: Exception) {
                    Logger.logStackTraceWithMessage("InputDialog", "Failed to dismiss dialog", e)
                }
            }
        }
    }
}
