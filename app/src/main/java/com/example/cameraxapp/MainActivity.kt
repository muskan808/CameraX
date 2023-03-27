package com.example.cameraxapp

import android.Manifest
import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.cameraxapp.databinding.ActivityMainBinding
import com.google.android.gms.tasks.Task
import com.google.android.gms.vision.Frame
import com.google.android.gms.vision.text.TextRecognizer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var textView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)

        setContentView(viewBinding.root)
            title = ""
        supportActionBar?.hide()

        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        super.onResume()

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Set up the listeners for take photo and video capture buttons
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        cameraExecutor = Executors.newSingleThreadExecutor()
        textView = findViewById(R.id.text_view)
        textView.visibility = View.GONE
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder().build()

            // Set back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                // Unbind use case before rebinding
                cameraProvider.unbindAll()
                // Bind use cases to camera
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time stamped name and MediaStore entry
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.ROOT).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX")
        }

        // Create output options object which contain file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        ).build()

        // Set up capture image listener, which is triggered after photo has been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo captured failed: ${exception.message}", exception)
                }

                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                   // val msg = "Photo capture succeeded: ${outputFileResults.savedUri}"
                   // Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                   // Log.d(TAG, msg)

                    viewBinding.imageView.setImageURI(outputFileResults.savedUri)
                    viewBinding.imageView.visibility = View.VISIBLE
                    outputFileResults.savedUri?.let { recognizeTextFromImage(it) }
                }
            }
        )
    }
    private fun recognizeTextFromImage(imageUri: Uri) {
        textView.text = ""
        // Load the image from the given URI into a Bitmap object
        val inputStream = contentResolver.openInputStream(imageUri)
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream?.close()

        // Create an InputImage object from the Bitmap
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        // Set up TextRecognition
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        // Run text recognition on the InputImage
        val result: Task<Text> = recognizer.process(inputImage)
        result.addOnSuccessListener { visionText ->
            // Task completed successfully
            val text = visionText.text.replace(" ", "")
            Log.d(TAG, "Recognized text: $text")

            if (text.isEmpty()) {
                // Show a message if no text is recognized
                Toast.makeText(this, "No vehicle number is recognized", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }

            // Extract ANPR number from the text
            val linearLayout = findViewById<LinearLayout>(R.id.LinearLayout)
            val textList = text.split("\n")
            val pattern = "[A-Z]{2}\\d{0,2}[A-Z]{0,2}\\d{0,4}".toRegex()

            for (line in textList) {

                // Check if the the last 4 Digits are numbers
                if (pattern.matches(line)) {
                    // Create a new LinearLayout to hold the TextView and Button for the line
                    val lineLayout = LinearLayout(this)
                    lineLayout.orientation = LinearLayout.HORIZONTAL
                    lineLayout.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    // Create a new TextView for the line of text
                    val textView = TextView(this)
                    val heading = "Recognized Text:\n"
                    textView.text = heading+line
                    textView.setTextColor(Color.MAGENTA)
                    textView.layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )

                    // Create a new Button for copying the line to the clipboard
                    val copyButton = ImageButton(this)
                    copyButton.setImageResource(R.drawable.ic_copy) // replace with your own icon
                    copyButton.background = null
                    copyButton.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    copyButton.setOnClickListener {
                        val clipboardManager =
                            getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        val clipData = ClipData.newPlainText("text", line)
                        clipboardManager.setPrimaryClip(clipData)
                        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT)
                            .show()
                    }
                    // Add the TextView and Button to the LinearLayout for the line
                    lineLayout.addView(textView)
                    lineLayout.addView(copyButton)
                    // Add the LinearLayout for the line to the overall layout
                    linearLayout.addView(lineLayout)
                }
            }
        }
            .addOnFailureListener { e ->
                // Task failed with an exception
                Log.e(TAG, "Text recognition failed", e)
                Toast.makeText(baseContext, "Text recognition failed", Toast.LENGTH_LONG).show()
            }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this, "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS: Array<String> = mutableListOf(
            Manifest.permission.CAMERA,
        ).apply {
        }.toTypedArray()
    }
}
