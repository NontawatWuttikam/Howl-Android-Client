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

    private var audioChunk:ShortArray = ShortArray(0);
    private lateinit var model:Module;
    private var isRun = true;
    private val wwHandlerThread = HandlerThread("RecognizerThread")
    private lateinit var handler:Handler;
    private val sampleRate = 16000
    private val strideSize = (this.sampleRate * 0.068).toInt()
    private val windowSize = (this.sampleRate * 0.5).toInt()

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
        val assetFilePath = this.assetFilePath(this, "hey_ff_traced_full.ptl");
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
                val audioChunk = this.audioChunk.map {
                    it -> (it / 32767.0).toFloat()
                }
                var i = 0
                var labels:ArrayList<Int> = arrayListOf()
                while (i < (audioChunk.size - this.windowSize)) {
                    var windowed = audioChunk.slice(IntRange(i, i + this.windowSize - 1)).toFloatArray()
                    val inputTensor: Tensor = Tensor.fromBlob(windowed, longArrayOf(windowed.size.toLong()))
                    val output:Tensor = this.model.forward(IValue.from(inputTensor)).toTensor();
                    val o = output.dataAsFloatArray;
                    val predictedLabel = o.withIndex().maxByOrNull { it.value }?.index as Int
                    if (predictedLabel != 3 && !labels.contains(predictedLabel)) {
                        labels.add(predictedLabel)
                        println(predictedLabel)
                    }
                    i += this.strideSize
                }
                labels.forEach {
                    println(it)
                }
            }
        }
    }

    private fun recognize(): ArrayList<Int> {
        /// Recognize : return recognized class softmaxly
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
            MediaRecorder.AudioSource.MIC,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize.coerceAtLeast(audioBufferSize)
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