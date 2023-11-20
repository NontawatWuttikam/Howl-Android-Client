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
import android.os.SystemClock.sleep
import android.util.Log
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

        val wakewordIntent = Intent(this, WakeWordService::class.java)
        startService(wakewordIntent)

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
        constructor (label:Int, timeStamp:Long) {
            this.label = label
            this.timeStamp = timeStamp
        }
    }
    private var goal = -1
    private var currentState = 0
    private var lastTimeStamp = -0L
    private var intervalThreshold = 0L
    private var delayInterval = 0L
    private var lastGoalTimeStamp = 0L

    constructor (length:Int, intervalThreshold:Long, delayInterval:Long) {
        this.goal = length
        this.currentState = 0
        this.lastTimeStamp - 1
        this.intervalThreshold = intervalThreshold
        this.delayInterval = delayInterval
        this.lastGoalTimeStamp = -1

    }

    public fun getState():Int {
        return this.currentState
    }

    fun transition(label:Label):Boolean {
        var isGoal = false
        val word = label.label
        val t = label.timeStamp

        if (t - this.lastTimeStamp > this.intervalThreshold) {
            this.currentState = 0
        }
        if (word == this.currentState) {
            this.currentState += 1
            this.lastTimeStamp = t
        }
        else if (word < this.currentState) {
            return false
        }
        else {
            this.currentState = 0
            return false
        }

        if (this.currentState == this.goal) {
            this.currentState = 0
            isGoal = true
            if (isGoal && (t - this.lastGoalTimeStamp < this.delayInterval)) return false
            this.lastGoalTimeStamp = t
            this.lastTimeStamp = t
        }

        return isGoal
    }

    public fun update(labels: List<Label>):Boolean {
        for (label in labels) {
            val isGoal = this.transition(label)
//            Log.d("update",this.currentState.toString())
            if (isGoal) return true
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

    private var stateMachine:StateMachine = StateMachine(3,1000,1000) //in millisec
    private var confidenceThreshold = 0.0f
    private var audioChunk:ShortArray = ShortArray(0);
    private lateinit var model:Module;
    private var isRun = true;
    private val wwHandlerThread = HandlerThread("RecognizerThread")
    private lateinit var handler:Handler;
    private val sampleRate = 16000
    private val strideSize = (this.sampleRate * 0.068).toInt()
    private val windowSize = (this.sampleRate * 0.5).toInt()
    private val mediaPlayer by lazy {
        MediaPlayer.create(this, this.assetFilePath(this, "huh.wav").toUri())
    }

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
        this.startRecognize()
        return START_STICKY
    }

    fun initializeWakeWordModel() {
        val assetFilePath = this.assetFilePath(this, "hello_cape_bee_traced_full.ptl");
        val module = LiteModuleLoader.load(assetFilePath);
        // Test forward pass
        val tensorBuffer = Tensor.allocateFloatBuffer(1088);
        val tensorSize:LongArray = longArrayOf(1088);
        val randomTensor = Tensor.fromBlob(tensorBuffer, tensorSize);
        val output:Tensor = module.forward(IValue.from(randomTensor)).toTensor();
        val outputValue = output.dataAsFloatArray;
        this.model = module
    }



    private fun startRecognize() {
        initializeWakeWordModel()
        handler.post {
            while (true) {
                if (this.audioChunk.size < 32) continue
                var audioChunk = this.audioChunk.map {
                    it ->
                    (it / 32767f)
                }
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
                    if (predictedLabel != 3 && predictScore > this.confidenceThreshold) {
                        labelsNum.add(predictedLabel)
                        val label:StateMachine.Label = StateMachine.Label(predictedLabel, System.currentTimeMillis())
                        labels.add(label)
                        confidences.add(predictScore)
//                        Log.d("ww",predictedLabel.toString() + " " +predictScore.toString())
                    }
                    i += this.strideSize
                }
                val isGoal = stateMachine.update(labels)
                if (isGoal) {
//                     broadcast to mainactivity
//                        val sendDetectSignal = Intent()
//                        sendDetectSignal.action = "WAKEWORD_DETECTED"
//                        sendDetectSignal.putExtra("DETECTED", true)
//                        sendBroadcast(sendDetectSignal)
                    this.mediaPlayer.start()
                    Log.d("isGoal","detected at " + System.currentTimeMillis().toString())
                }
                if (labels.size > 0 ) {
                    var b:StringBuilder = java.lang.StringBuilder()
                    b.append(stateMachine.getState())
                    b.append(" | ")
                    labels.forEach {
                        b.append(it.label)
                        b.append(" ")
                    }
                    b.append("-------")
                    confidences.forEach {
                        b.append(it)
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