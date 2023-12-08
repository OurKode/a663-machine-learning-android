package com.dicoding.mymediaplayer

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifierResult
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.components.containers.AudioData.AudioDataFormat
import com.google.mediapipe.tasks.core.BaseOptions
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class AudioClassifierHelper(
    val threshold: Float = 0.1f,
    val maxResults: Int = 3,
    val modelName: String = "yamnet.tflite",
    val runningMode: RunningMode = RunningMode.AUDIO_STREAM,
    val overlap: Int = 2,
    val context: Context,
    var classifierListener: ClassifierListener? = null,
) {

    private var recorder: AudioRecord? = null
    private var executor: ScheduledThreadPoolExecutor? = null
    private var audioClassifier: AudioClassifier? = null
    private val classifyRunnable = Runnable {
        recorder?.let { classifyAudioAsync(it) }
    }

    init {
        initClassifier()
    }

    fun initClassifier() {
        try {
            val optionsBuilder = AudioClassifier.AudioClassifierOptions.builder()
                .setScoreThreshold(threshold)
                .setMaxResults(maxResults)
                .setRunningMode(runningMode)

            if (runningMode == RunningMode.AUDIO_STREAM) {
                optionsBuilder
                    .setResultListener(this::streamAudioResultListener)
                    .setErrorListener(this::streamAudioErrorListener)
            }

            val baseOptionsBuilder = BaseOptions.builder()
                .setModelAssetPath(modelName)
            optionsBuilder.setBaseOptions(baseOptionsBuilder.build())

            audioClassifier = AudioClassifier.createFromOptions(context, optionsBuilder.build())

            if (runningMode == RunningMode.AUDIO_STREAM) {
                recorder = audioClassifier?.createAudioRecord(
                    AudioFormat.CHANNEL_IN_DEFAULT,
                    SAMPLING_RATE_IN_HZ,
                    BUFFER_SIZE_IN_BYTES.toInt()
                )
            }
        } catch (e: IllegalStateException) {
            classifierListener?.onError(context.getString(R.string.audio_classifier_failed))
            Log.e(TAG, "MP task failed to load with error: " + e.message)
        } catch (e: RuntimeException) {
            classifierListener?.onError(context.getString(R.string.audio_classifier_failed))
            Log.e(TAG, "MP task failed to load with error: " + e.message)
        }
    }

    fun startAudioClassification() {
        if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            return
        }

        recorder?.startRecording()
        executor = ScheduledThreadPoolExecutor(1)

        // Each model will expect a specific audio recording length. This formula calculates that
        // length using the input buffer size and tensor format sample rate.
        // For example, YAMNET expects 0.975 second length recordings.
        // This needs to be in milliseconds to avoid the required Long value dropping decimals.
        val lengthInMilliSeconds = ((REQUIRE_INPUT_BUFFER_SIZE * 1.0f) / SAMPLING_RATE_IN_HZ) * 1000
        val interval = (lengthInMilliSeconds * (1 - (overlap * 0.25))).toLong()

        executor?.scheduleAtFixedRate(
            classifyRunnable,
            0,
            interval,
            TimeUnit.MILLISECONDS
        )
    }

    private fun classifyAudioAsync(audioRecord: AudioRecord) {
        val audioData = AudioData.create(
            AudioDataFormat.create(recorder?.format), SAMPLING_RATE_IN_HZ
        )
        audioData.load(audioRecord)

        val inferenceTime = SystemClock.uptimeMillis()
        audioClassifier?.classifyAsync(audioData, inferenceTime)
    }

    fun classifyAudio(audioData: AudioData): ResultBundle? {
        val startTime = SystemClock.uptimeMillis()
        audioClassifier?.classify(audioData)
            ?.also { audioClassificationResult ->
                val inferenceTime = SystemClock.uptimeMillis() - startTime
                return ResultBundle(
                    listOf(audioClassificationResult),
                    inferenceTime
                )
            }

        // If audioClassifier?.classify() returns null, this is likely an error. Returning null
        // to indicate this.
        classifierListener?.onError("Audio classifier failed to classify.")
        return null
    }

    fun stopAudioClassification() {
        executor?.shutdownNow()
        audioClassifier?.close()
        audioClassifier = null
        recorder?.stop()
    }

    fun isClosed(): Boolean {
        return audioClassifier == null
    }

    private fun streamAudioResultListener(resultListener: AudioClassifierResult) {
        classifierListener?.onResult(
            ResultBundle(listOf(resultListener), 0)
        )
    }

    private fun streamAudioErrorListener(e: RuntimeException) {
        classifierListener?.onError(e.message.toString())
    }

    data class ResultBundle(
        val results: List<AudioClassifierResult>,
        val inferenceTime: Long,
    )

    companion object {
        private const val TAG = "AudioClassifierHelper"

        private const val SAMPLING_RATE_IN_HZ = 16000
        private const val BUFFER_SIZE_FACTOR: Int = 2
        private const val EXPECTED_INPUT_LENGTH = 0.975F
        private const val REQUIRE_INPUT_BUFFER_SIZE =
            SAMPLING_RATE_IN_HZ * EXPECTED_INPUT_LENGTH

        /**
         * Size of the buffer where the audio data is stored by Android
         */
        private const val BUFFER_SIZE_IN_BYTES =
            REQUIRE_INPUT_BUFFER_SIZE * Float.SIZE_BYTES * BUFFER_SIZE_FACTOR
    }

    interface ClassifierListener {
        fun onError(error: String)
        fun onResult(resultBundle: ResultBundle)
    }
}
