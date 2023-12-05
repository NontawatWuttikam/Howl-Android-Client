package com.example.howltestapp

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.util.Range
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import com.example.howltestapp.ui.theme.HowlTestAppTheme
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream


class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


//        val wakewordIntent2 = Intent(this, WakeWordService::class.java)
//        wakewordIntent2.putExtra("MODEL_FILE", "hey_ff_traced_full.ptl")
//        wakewordIntent2.putExtra("ALERT_AUDIO_FILE","huh.wav")
//        startService(wakewordIntent2)

        val wakewordIntent1 = Intent(this, WakeWordService::class.java)
        wakewordIntent1.putExtra("MODEL_FILE", "hello_cape_bee_5k49ep_traced_full.ptl")
        wakewordIntent1.putExtra("ALERT_AUDIO_FILE","huh.wav")
        startService(wakewordIntent1)

        setContent {
            HowlTestAppTheme {
                // A surface container using the 'background' color from the theme
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Greeting("Hello Boat")
                }
            }
        }
    }
}

class StateMachine {

    public class Label {
        var label:Int = -1
        var timeStamp:Long = -1
        var confidence:Float = -1.0f
        constructor (label:Int, timeStamp:Long, confidence:Float) {
            this.label = label
            this.timeStamp = timeStamp
            this.confidence = confidence
        }
    }
    private var goal = -1
    private var currentState = 0
    private var lastTimeStamp = -0L
    private var intervalThreshold = 0L
    private var delayInterval = 0L
    private var lastGoalTimeStamp = 0L
    private var confidenceLookup:ArrayList<Float> = arrayListOf()
    private var confidenceScore:ArrayList<Float> = arrayListOf()

    constructor (length:Int, intervalThreshold:Long, delayInterval:Long, confidenceLookup:ArrayList<Float>) {
        this.goal = length
        this.currentState = 0
        this.lastTimeStamp - 1
        this.intervalThreshold = intervalThreshold
        this.delayInterval = delayInterval
        this.lastGoalTimeStamp = -1
        this.confidenceLookup = confidenceLookup
    }

    public fun getState():Int {
        return this.currentState
    }

    fun transition(label:Label):Boolean {
        var isGoal = false
        val word = label.label
        val confidence = label.confidence
        val t = label.timeStamp

        if (t - this.lastTimeStamp > this.intervalThreshold) {
            this.currentState = 0
            confidenceScore.clear()
        }

        if (word == this.currentState && confidence >= confidenceLookup[word]) {
            if (this.currentState == 0) confidenceScore.clear()
            this.currentState += 1
            this.lastTimeStamp = t
            confidenceScore.add(confidence)
        }
        else if (word < this.currentState) {
//            this.currentState = 0
//            confidenceScore.clear()
            return false
        }
        else {
            this.currentState = 0
            confidenceScore.clear()
            return false
        }

        if (this.currentState == this.goal) {
            this.currentState = 0
            isGoal = true
            if (isGoal && (t - this.lastGoalTimeStamp < this.delayInterval)) {
                Log.d("ww", "DELAY INTERVAL SUPPRESS")
                return false
            }
            var conf = "confidences : "
            confidenceScore.forEach {
                conf += it.toString() + " "
            }
            Log.d("confidences", conf)
            this.lastGoalTimeStamp = t
            this.lastTimeStamp = t
        }

        return isGoal
    }

    public fun update(labels: List<Label>):Boolean {
        for (label in labels) {
            val isGoal = this.transition(label)
//            Log.d("update",this.currentState.toString())
            if (isGoal) {
                return true
            }
        }
        return false
    }
}

class DummyService : Service() {
    override fun onCreate() {
        super.onCreate()
        println("Start Dummy Service")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
    }

}

class WakeWordService : Service() {
    private var wwLength = 3
    private var stateMachine:StateMachine = StateMachine(wwLength,100,300, arrayListOf(0.0f, 0.0f, 0.0f)) //in millisec
    private var audioChunk:ShortArray = ShortArray(0);
    private lateinit var model:Module;
    private var isRun = true;
    private val wwHandlerThread = HandlerThread("RecognizerThread")
    private lateinit var handler:Handler;
    private val sampleRate = 16000
    private val strideSize = (this.sampleRate * 0.068).toInt()
    private val windowSize = (this.sampleRate * 0.4).toInt()
    private val wordOccurrenceThreshold = 1 // กี่คำถึงจะนับว่าเจอ เช่น 3 คือต้องเจอ window ติดกัน 3 ครั้ง
    private lateinit var mediaPlayer:MediaPlayer
    private lateinit var modelFile:String
    private lateinit var audioFile:String

    fun assetFilePath(context: Context, asset: String): String {
        val file = File(context.filesDir, asset)

        try {
            val inpStream: InputStream = context.assets.open(asset)
            try {
                val outStream = FileOutputStream(file, false)
                val buffer = ByteArray(4 * 1024)
                var read: Int

                while (true) {
                    read = inpStream.read(buffer)
                    if (read == -1) {
                        break
                    }
                    outStream.write(buffer, 0, read)
                }
                outStream.flush()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
            return file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }

    interface ReceiverListener {
        fun onChunkRecv(context:Context, audioChunk:ShortArray);
    }

    private class AudioChunkReceiver(val listener: ReceiverListener) : BroadcastReceiver() {
        private var audioChunk:ShortArray = ShortArray(0)
        private var isReady:Boolean = false
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "AUDIO_CHUNK_STREAM") {
                val audioChunk: ShortArray = intent.getSerializableExtra("AUDIO_CHUNK") as ShortArray
//                println(audioChunk.size)
                this.audioChunk = audioChunk
                this.isReady = true
                listener.onChunkRecv(context,audioChunk)
            }
        }

        fun isReady():Boolean {
            return this.isReady
        }

        fun getAudioChunk():ShortArray {
            return this.audioChunk
        }
    }

    override fun onCreate() {
        super.onCreate()
        // start audiocapture Service
        val audioCaptureIntent = Intent(this, AudioCaptureService::class.java)
        startService(audioCaptureIntent)

        // start audiocapture Receiver
        val audioChunkReceiver = AudioChunkReceiver(object : ReceiverListener {
            override fun onChunkRecv(context:Context, audioChunk: ShortArray) {
                val parent = context as WakeWordService
                parent.audioChunk = audioChunk
            }
        })
        registerReceiver(audioChunkReceiver, IntentFilter("AUDIO_CHUNK_STREAM"))

        this.wwHandlerThread.start()
        this.handler = Handler(wwHandlerThread.looper)

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            this.modelFile = intent.getStringExtra("MODEL_FILE").toString()
            this.audioFile = intent.getStringExtra("ALERT_AUDIO_FILE").toString()
        }
        this.mediaPlayer = MediaPlayer.create(this, this.assetFilePath(this, this.audioFile).toUri())

        this.startRecognize()
        return START_STICKY
    }

    fun initializeWakeWordModel() {
        val assetFilePath = this.assetFilePath(this, this.modelFile);
        val module = LiteModuleLoader.load(assetFilePath);
        // Test forward pass
        val tensorBuffer = Tensor.allocateFloatBuffer(1088);
        val tensorSize:LongArray = longArrayOf(1088);
        val randomTensor = Tensor.fromBlob(tensorBuffer, tensorSize);
        val output:Tensor = module.forward(IValue.from(randomTensor)).toTensor();
        val outputValue = output.dataAsFloatArray;
        this.model = module
    }

    fun mergeAdjacentLabels(labels: ArrayList<StateMachine.Label>): ArrayList<StateMachine.Label> {
        if (labels.size == 0) return labels
        var current = labels[0].label
        var pos = 0
        var outLabels = arrayListOf<StateMachine.Label>()
        labels.slice(IntRange(1, labels.size - 1)).forEachIndexed {index, it ->
            val idx = index + 1
            if (it.label == current) pos++
            else {
                var toMerge = labels.slice(IntRange(pos, idx - 1))
                var merged = StateMachine.Label(toMerge[0].label, toMerge[0].timeStamp, toMerge.map { it -> it.confidence}.max())
                outLabels.add(merged)
                pos = idx
            }
            current = it.label
        }
        var toMerge = labels.slice(IntRange(pos, labels.size - 1))
        var merged = StateMachine.Label(toMerge[0].label, toMerge[0].timeStamp, toMerge.map { it -> it.confidence}.max())
        outLabels.add(merged)
        return outLabels
    }


    fun clamp(`val`: Float, min: Float, max: Float): Float {
        return Math.max(min, Math.min(max, `val`))
    }

//    fun isConfidencesSatified(confidences: ArrayList<Float>): Boolean {
//        return confidences[0] > 0.7f && confidences[1] > 0.7f && confidences[2] > 0.6f
//    }

    private fun startRecognize() {
        initializeWakeWordModel()
        handler.post {
            while (true) {
                if (this.audioChunk.size < 32) continue
                var audioChunk = this.audioChunk.map {
                    it ->
                    clamp((it / 32767f)*1.0f,-1f,1f)
                }
//                audioChunk = this.audioChunk.map {
//                        it ->
//                    (2*((it - audioChunk.min())/(audioChunk.max() - audioChunk.min()))) - 1
//                }
//                audioChunk = audioChunk.reversed()
                var i = 0
                var labels:ArrayList<StateMachine.Label> = ArrayList<StateMachine.Label>();
                var labelsNum: ArrayList<Int> = arrayListOf();
                var confidences: ArrayList<Float> = arrayListOf();
                while (i < (audioChunk.size - this.windowSize)) {
                    var windowed = audioChunk.slice(IntRange(i, i + this.windowSize - 1)).toFloatArray()

//                    Log.d("audio frame",windowed.map {
//                        it -> "%.6f".format(it)
//                    }.joinToString(","))

                    val inputTensor: Tensor = Tensor.fromBlob(windowed, longArrayOf(windowed.size.toLong()))
                    val output:Tensor = this.model.forward(IValue.from(inputTensor)).toTensor();
                    val o = output.dataAsFloatArray;
                    val predictedLabel = o.withIndex().maxByOrNull { it.value }?.index as Int
                    val predictScore = o.withIndex().maxByOrNull { it.value }?.value as Float
                    if (predictedLabel != 3) {
                        labelsNum.add(predictedLabel)
                        val label:StateMachine.Label = StateMachine.Label(predictedLabel, System.currentTimeMillis(), predictScore)
                        labels.add(label)
                        confidences.add(predictScore)
//                        Log.d("ww",predictedLabel.toString() + " " +predictScore.toString())
                    }
                    i += this.strideSize
                }

                labels = mergeAdjacentLabels(labels)
                val isGoal = stateMachine.update(labels)
                if (isGoal) {
                    this.mediaPlayer.start()
                    Log.d("isGoal","detected at " + System.currentTimeMillis().toString())
                }
                if (labels.size > 0) {
                    var b:StringBuilder = java.lang.StringBuilder()
                    b.append(stateMachine.getState())
                    b.append(" | ")
                    labels.forEach {
                        b.append(it.label)
                        b.append(" ")
                    }
                    b.append("-------")
                    labels.forEach {
                        b.append(it.confidence)
                        b.append(" ")
                    }
                    Log.d("ww", b.toString())
                }
            }
        }
    }

    private fun recognize(): ArrayList<Int> {
        /// Recognize : return recognized class sconfidenceThresholdoftmaxly
        return arrayListOf(1,2,3,4)
    }
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        this.isRun = false
        super.onDestroy()
    }
}

class AudioCaptureService : Service() {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var audioDataCallback: ((ByteArray) -> Unit)? = null
    private val audioBufferSize = 500 // Adjust the buffer size as needed
    private var maxSize:Int = 32;
    private var currentSize:Int = 0
    private var chunkBuffer:ShortArray = ShortArray(0);
    private val audioHandlerThread = HandlerThread("AudioCaptureThread")
    private lateinit var audioHandler: Handler

    override fun onCreate() {
        super.onCreate()
        println("Start Audio Record Service")
        audioHandlerThread.start()
        audioHandler = Handler(audioHandlerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startRecording()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    private fun startRecording() {
        if (isRecording) {
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.DEFAULT,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            this.audioBufferSize
        )

        audioRecord?.startRecording()
        isRecording = true

        audioHandler.post {
            val audioBuffer = ShortArray(audioBufferSize)

            while (isRecording) {
                val sizeRead = audioRecord?.read(audioBuffer, 0, audioBufferSize) ?: 0
                if (sizeRead > 0) {
                    val data:ShortArray = audioBuffer.copyOf(sizeRead)
                    if (this.currentSize >= this.maxSize ) {
                        this.chunkBuffer = this.chunkBuffer.slice(IntRange(this.audioBufferSize, (this.maxSize * this.audioBufferSize) - 1)).toShortArray()
                        this.currentSize --;
                    }
                    this.chunkBuffer += data
                    this.currentSize ++;
                    val IntData = data.map {
                        it ->  it.toInt()
                    }
//                    Log.d("StreamAudio",IntData.sum().toString() + " | " + data.size)
//                    Log.d("StreamAudio", IntData.min().toString() + " | " + IntData.max().toString())
//                    var all = 0
//                    for (c in data)
//                        all += Math.abs(c.toInt())
//                    println(all/data.size)
                    // send broadcast
                    val sendLevel = Intent()
                    sendLevel.action = "AUDIO_CHUNK_STREAM"
                    sendLevel.putExtra("AUDIO_CHUNK", this.chunkBuffer)
                    sendBroadcast(sendLevel)
                }
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
    }
}


@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
            text = "Hello $name!",
            modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    HowlTestAppTheme {
        Greeting("Android")
    }
}