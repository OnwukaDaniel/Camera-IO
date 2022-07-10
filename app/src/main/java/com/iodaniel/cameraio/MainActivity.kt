package com.iodaniel.cameraio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.CamcorderProfile
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.*
import android.util.Size
import android.util.SparseIntArray
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.iodaniel.cameraio.Util.filterCameraIdsFacing
import com.iodaniel.cameraio.Util.getCameraList
import com.iodaniel.cameraio.Util.getFirstCameraIdFacing
import com.iodaniel.cameraio.databinding.ActivityMainBinding
import com.iodaniel.cameraio.databinding.VideoFragmentBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*


class MainActivity : AppCompatActivity() {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val CAMERA = Manifest.permission.CAMERA
    private val RECORDAUDIO = Manifest.permission.RECORD_AUDIO
    private var cameraDeviceX: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var imageDimension = Size(640, 1080)
    private var captureBuilder: CaptureRequest.Builder? = null
    private var captureBuilderFront: CaptureRequest.Builder? = null
    private var handler = Handler(Looper.myLooper()!!)
    private var handlerFront = Handler(Looper.myLooper()!!)
    private var imageReaderSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var cameraId = ""
    private var mVideoSize: Size = Size(0, 0)
    private var mPreviewSize: Size = Size(0, 0)
    private lateinit var mediaRecorder: MediaRecorder
    private var mIsRecordingVideo = false
    private var mCurrentFile: File? = null

    // Check state orientation
    private var ORIENTATION = SparseIntArray()

    init {
        ORIENTATION.append(Surface.ROTATION_0, 90)
        ORIENTATION.append(Surface.ROTATION_90, 0)
        ORIENTATION.append(Surface.ROTATION_180, 270)
        ORIENTATION.append(Surface.ROTATION_270, 180)
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permission ->
        if (permission[REQUIRED_PERMISSIONS[0]] == false) showRationale()
        if (permission[REQUIRED_PERMISSIONS[1]] == false) showRationale()
        if (REQUIRED_PERMISSIONS.size > 2 && permission[REQUIRED_PERMISSIONS[2]] == false) showRationale()
        if (permission[CAMERA] == true && permission[RECORDAUDIO] == true) {
            if (REQUIRED_PERMISSIONS.size > 2) {
                if (permission[REQUIRED_PERMISSIONS[2]] == true) startCamera()
                return@registerForActivityResult
            }
        }
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera(CameraCharacteristics.LENS_FACING_BACK)
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            Util.setTextureTransform(characteristics, binding.textureView)
            imageDimension = Size(width, height)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDeviceX = camera
            createCameraPreview()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraDevice.close()
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            val errorMsg = when (error) {
                ERROR_CAMERA_DEVICE -> "Fatal (device)"
                ERROR_CAMERA_DISABLED -> "Device policy"
                ERROR_CAMERA_IN_USE -> "Camera in use"
                ERROR_CAMERA_SERVICE -> "Fatal (service)"
                ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                else -> "Unknown"
            }
            Snackbar.make(binding.root, errorMsg, Snackbar.LENGTH_LONG).show()
            cameraDevice.close()
            cameraDeviceX = null
        }
    }

    private fun showRationale() {
        Snackbar.make(binding.root, "Accept permissions", Snackbar.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onResume() {
        super.onResume()
        for ((index, i) in REQUIRED_PERMISSIONS.withIndex()) {
            if (ContextCompat.checkSelfPermission(applicationContext, i) == PackageManager.PERMISSION_DENIED) {
                permissionLauncher.launch(REQUIRED_PERMISSIONS)
                break
            }
            if (index == REQUIRED_PERMISSIONS.size - 1 && ContextCompat.checkSelfPermission(applicationContext, i) == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            }
            if (index == REQUIRED_PERMISSIONS.size - 1 && ContextCompat.checkSelfPermission(applicationContext, i) == PackageManager.PERMISSION_DENIED) break
        }

        scope.launch {
            if (binding.textureView.isAvailable) {
                openCamera(CameraCharacteristics.LENS_FACING_BACK)
            } else {
                binding.textureView.surfaceTextureListener = textureListener
            }
            binding.videoToggle.setOnClickListener {
                val intent = Intent(this@MainActivity, ActivityVideo::class.java)
                startActivity(intent)
                overridePendingTransition(R.anim.enter_left_to_right, R.anim.exit_left_to_right)
            }
            binding.pictureToggle.setOnClickListener {
                onResume()
                if (supportFragmentManager.backStackEntryCount > 0) onBackPressed()
            }
        }

        var dX: Float = 0F
        var dY: Float = 0F
        binding.frontTCard.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                }
                MotionEvent.ACTION_MOVE -> v.animate()
                    .x(event.rawX + dX)
                    .y(event.rawY + dY)
                    .setDuration(0)
                    .start()
                else -> return@setOnTouchListener false
            }
            return@setOnTouchListener true
        }
    }

    override fun onBackPressed() {
        onResume()
        if (supportFragmentManager.backStackEntryCount > 0) onBackPressed() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        if (cameraDeviceX == null) return
        cameraDeviceX!!.close()
    }

    private fun getCameraDevices() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraIdList = cameraManager.cameraIdList
        // iterate over available camera devices
        for (cameraId in cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val cameraLensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            val cameraCapabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)

            // check if the selected camera device supports basic features
            // ensures backward compatibility with the original Camera API
            val isBackwardCompatible = cameraCapabilities?.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) ?: false
            println("These are the cameras cameraLensFacing ************************************** $cameraLensFacing")
        }
    }

    private fun filterCompatibleCameras(cameraIds: Array<String>): List<String> {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return cameraIds.filter {
            val characteristics = cameraManager.getCameraCharacteristics(it)
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) ?: false
        }
    }

    private fun startCamera() {
        binding.textureView.surfaceTextureListener = textureListener
    }

    private fun getNextCameraId(currCameraId: String? = null): String? {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        // Get all front, back and external cameras in 3 separate lists
        val cameraIds = filterCompatibleCameras(cameraManager.cameraIdList)
        val backCameras = filterCameraIdsFacing(this, cameraIds, CameraMetadata.LENS_FACING_BACK)
        val frontCameras = filterCameraIdsFacing(this, cameraIds, CameraMetadata.LENS_FACING_FRONT)
        val externalCameras = filterCameraIdsFacing(this, cameraIds, CameraMetadata.LENS_FACING_EXTERNAL)

        // The recommended order of iteration is: all external, first back, first front
        val allCameras = (externalCameras + listOf(backCameras.firstOrNull(), frontCameras.firstOrNull())).filterNotNull()

        // Get the index of the currently selected camera in the list
        val cameraIndex = allCameras.indexOf(currCameraId)

        // The selected camera may not be in the list, for example it could be an
        // external camera that has been removed by the user
        return if (cameraIndex == -1) {
            // Return the first camera from the list
            allCameras.getOrNull(0)
        } else {
            // Return the next camera from the list, wrap around if necessary
            allCameras.getOrNull((cameraIndex + 1) % allCameras.size)
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val rotation = windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0F, 0F, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0F, 0F, mPreviewSize.height.toFloat(), mPreviewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale: Float = (viewHeight.toFloat() / mPreviewSize.height).coerceAtLeast(viewWidth.toFloat() / mPreviewSize.width)
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        }
        binding.textureView.setTransform(matrix)
    }

    private fun closePreviewSession() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession!!.close()
            cameraCaptureSession = null
        }
    }

    companion object {
        //private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }.toTypedArray()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(lensFacing: Int) {
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(applicationContext) else MediaRecorder()
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraList = getCameraList(this)
        val backCameras = filterCameraIdsFacing(this, cameraList, CameraCharacteristics.LENS_FACING_BACK)
        if (backCameras.isEmpty()) {
            Snackbar.make(binding.root, "No Camera device", Snackbar.LENGTH_LONG).show()
            finish()
            return
        }

        val width = imageDimension.width
        val height = imageDimension.height
        cameraId = getFirstCameraIdFacing(this, lensFacing)!!
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        assert(map != null)
        mVideoSize = Util.chooseVideoSize(map!!.getOutputSizes(MediaRecorder::class.java))
        mPreviewSize = Util.chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java), width, height, mVideoSize)!!
        //configureTransform(width, height)
        cameraManager.openCamera(cameraId, cameraStateCallback, handler)
        //if (hasFrontCamera) {
        //    val frontCameraId = filterCameraIdsFacing(cameraList, CameraCharacteristics.LENS_FACING_FRONT)[0]
        //    cameraManager.openCamera(frontCameraId, cameraStateCallbackFront, handlerFront)
        //}
        binding.captureCircle.setOnClickListener {
            cameraId = getFirstCameraIdFacing(this, lensFacing)!!
            if (cameraId != "") takePicture(cameraId)
            scope.launch {
                handler.post {
                    kotlin.run {
                        binding.capture.visibility = View.VISIBLE
                    }
                }
                delay(1000)
                handler.post {
                    kotlin.run {
                        binding.capture.visibility = View.GONE
                    }
                }
            }
        }
        binding.rotate.setOnClickListener {
            val char = cameraManager.getCameraCharacteristics(cameraId)
            when (char.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> {
                    openCamera(CameraCharacteristics.LENS_FACING_FRONT)
                }
                CameraCharacteristics.LENS_FACING_FRONT -> {
                    openCamera(CameraCharacteristics.LENS_FACING_BACK)
                }
            }
        }
    }

    private fun takePicture(cameraId: String) {
        if (cameraDeviceX != null) {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics: CameraCharacteristics? = cameraManager.getCameraCharacteristics(cameraId)
            val size = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG)
            captureBuilder = cameraCaptureSession!!.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder!!.addTarget(imageReaderSurface!!)
            captureBuilder!!.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) this.display!!.rotation else windowManager.defaultDisplay.rotation
            captureBuilder!!.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATION.get(rotation))
            val file = File(Environment.getExternalStorageDirectory().path + "/" + UUID.randomUUID().toString() + ".jpeg")
            imageReader!!.setOnImageAvailableListener({
                var image: Image? = null
                try {
                    image = imageReader!!.acquireLatestImage()
                    if (image == null) return@setOnImageAvailableListener
                    val byteBuffer = image.planes[0].buffer
                    val bytes = ByteArray(byteBuffer.capacity())
                    byteBuffer.get(bytes)
                    // SAVE
                    var outputStream: OutputStream? = null
                    try {
                        outputStream = FileOutputStream(file)
                        outputStream.write(bytes)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        outputStream?.close()
                    }
                } catch (e: CameraAccessException) {
                    e.printStackTrace()
                } finally {
                    image?.close()
                }
            }, handler)
            val cameraCaptureListener: CameraCaptureSession.CaptureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                    Snackbar.make(binding.root, "Saved at $file", Snackbar.LENGTH_LONG).show()
                }
            }
            cameraCaptureSession!!.capture(captureBuilder!!.build(), cameraCaptureListener, handler)
        } else println("Camera is null ******************************************************** ")
    }

    private fun createCameraPreview() {
        try {
            closePreviewSession()
            val surfaceTexture = binding.textureView.surfaceTexture
            val frontSurfaceTexture = binding.frontTextureView.surfaceTexture
            assert(surfaceTexture != null && frontSurfaceTexture != null)
            surfaceTexture!!.setDefaultBufferSize(imageDimension.width, imageDimension.height)
            val rect = Rect()
            binding.frontTextureView.getLocalVisibleRect(rect)
            frontSurfaceTexture!!.setDefaultBufferSize(rect.width(), rect.height())
            val surface = Surface(surfaceTexture)
            val frontSurface = Surface(frontSurfaceTexture)
            imageReader = ImageReader.newInstance(rect.width(), rect.height(), ImageFormat.JPEG, 5)
            imageReaderSurface = imageReader!!.surface
            captureBuilder = cameraDeviceX!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureBuilder!!.addTarget(surface)
            captureBuilder!!.addTarget(frontSurface)
            cameraDeviceX!!.createCaptureSession(mutableListOf(surface, frontSurface, imageReaderSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDeviceX == null) return
                    cameraCaptureSession = session
                    updatePreview()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Snackbar.make(binding.root, "Changed", Snackbar.LENGTH_LONG).show()
                }
            }, handler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun updatePreview() {
        if (cameraDeviceX == null) {
            Snackbar.make(binding.root, "Error occurred", Snackbar.LENGTH_LONG).show()
        }
        captureBuilder!!.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        try {
            cameraCaptureSession!!.setRepeatingRequest(captureBuilder!!.build(), captureSessionCallbackListener, handler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private val captureSessionCallbackListener = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            super.onCaptureStarted(session, request, timestamp, frameNumber)
        }

        override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
            super.onCaptureProgressed(session, request, partialResult)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            super.onCaptureCompleted(session, request, result)
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            super.onCaptureFailed(session, request, failure)
        }

        override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
            super.onCaptureSequenceCompleted(session, sequenceId, frameNumber)
        }

        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            super.onCaptureSequenceAborted(session, sequenceId)
        }

        override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
            super.onCaptureBufferLost(session, request, target, frameNumber)
        }
    }

    private val captureSessionCallbackListenerFront = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            super.onCaptureStarted(session, request, timestamp, frameNumber)
        }

        override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
            super.onCaptureProgressed(session, request, partialResult)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            super.onCaptureCompleted(session, request, result)
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            super.onCaptureFailed(session, request, failure)
        }

        override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
            super.onCaptureSequenceCompleted(session, sequenceId, frameNumber)
        }

        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            super.onCaptureSequenceAborted(session, sequenceId)
        }

        override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
            super.onCaptureBufferLost(session, request, target, frameNumber)
        }
    }
}

class VideoPage : AppCompatActivity() {
    private val binding by lazy { VideoFragmentBinding.inflate(layoutInflater) }
    private val CAMERA = Manifest.permission.CAMERA
    private val RECORDAUDIO = Manifest.permission.RECORD_AUDIO
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var imageDimension = Size(640, 1080)
    private var captureBuilder: CaptureRequest.Builder? = null
    private var handler = Handler(Looper.myLooper()!!)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var cameraId = ""
    private var mVideoSize: Size = Size(0, 0)
    private var mPreviewSize: Size = Size(0, 0)
    private lateinit var mediaRecorder: MediaRecorder
    private var mIsRecordingVideo = false
    private var mCurrentFile: File? = null
    private var recordingTIme = 0
    private val SENSOR_ORIENTATION_INVERSE_DEGREES = 270
    private val SENSOR_ORIENTATION_DEFAULT_DEGREES = 90
    private val INVERSE_ORIENTATIONS = SparseIntArray()
    private val DEFAULT_ORIENTATIONS = SparseIntArray()

    init {
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0)
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90)
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180)
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270)

        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_90, 0)
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_0, 90)
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_270, 180)
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_180, 270)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
    }

    override fun onResume() {
        super.onResume()
        startCamera()
        scope.launch {
            if (binding.videoTextureView.isAvailable) {
                openCamera(CameraCharacteristics.LENS_FACING_BACK)
            } else {
                binding.videoTextureView.surfaceTextureListener = textureListener
            }
        }
    }

    override fun onPause() {
        super.onPause()
        closeCamera()
    }

    private fun startCamera() {
        binding.videoTextureView.surfaceTextureListener = textureListener
    }

    private fun closeCamera() {
        try {
            closePreviewSession()
            if (null != cameraDevice) {
                mediaRecorder.release()
                cameraDevice = null
            }
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.")
        }
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera(CameraCharacteristics.LENS_FACING_BACK)
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            Util.setTextureTransform(characteristics, binding.videoTextureView)
            imageDimension = Size(width, height)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreview()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraDevice.close()
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            val errorMsg = when (error) {
                ERROR_CAMERA_DEVICE -> "Fatal (device)"
                ERROR_CAMERA_DISABLED -> "Device policy"
                ERROR_CAMERA_IN_USE -> "Camera in use"
                ERROR_CAMERA_SERVICE -> "Fatal (service)"
                ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                else -> "Unknown"
            }
            Snackbar.make(binding.root, errorMsg, Snackbar.LENGTH_LONG).show()
            cameraDevice.close()
            this@VideoPage.cameraDevice = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(lensFacing: Int) {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraList = getCameraList(this)
        val backCameras = filterCameraIdsFacing(this, cameraList, CameraCharacteristics.LENS_FACING_BACK)
        if (backCameras.isEmpty()) {
            Snackbar.make(binding.root, "No Camera device", Snackbar.LENGTH_LONG).show()
            return
        }

        val width = imageDimension.width
        val height = imageDimension.height
        cameraId = getFirstCameraIdFacing(this, lensFacing)!!
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        assert(map != null)
        mVideoSize = Util.chooseVideoSize(map!!.getOutputSizes(MediaRecorder::class.java))
        mPreviewSize = Util.chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java), width, height, mVideoSize)!!
        //configureTransform(width, height)
        cameraManager.openCamera(cameraId, cameraStateCallback, handler)

        binding.record.setOnClickListener {
            if (mIsRecordingVideo) {
                try {
                    stopRecordingVideo()
                    updatePreview()
                } catch (e: java.lang.Exception) {
                    e.printStackTrace()
                }
            } else {
                startRecordingVideo()
            }
        }
    }

    private fun createCameraPreview() {
        try {
            closePreviewSession()
            val surfaceTexture = binding.videoTextureView.surfaceTexture
            assert(surfaceTexture != null)
            surfaceTexture!!.setDefaultBufferSize(imageDimension.width, imageDimension.height)
            val surface = Surface(surfaceTexture)
            captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            captureBuilder!!.addTarget(surface)
            //captureBuilder!!.addTarget(mediaRecorder.surface)
            cameraDevice!!.createCaptureSession(mutableListOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    cameraCaptureSession = session
                    updatePreview()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Snackbar.make(binding.root, "Changed", Snackbar.LENGTH_LONG).show()
                }
            }, handler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun updatePreview() {
        if (cameraDevice == null) {
            Snackbar.make(binding.root, "Error occurred", Snackbar.LENGTH_LONG).show()
        }
        captureBuilder!!.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        try {
            cameraCaptureSession!!.setRepeatingRequest(captureBuilder!!.build(), captureSessionCallbackListener, handler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun closePreviewSession() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession!!.close()
            cameraCaptureSession = null
        }
    }

    @SuppressLint("SwitchIntDef")
    @Throws(IOException::class)
    private fun setUpMediaRecorder() {
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

        mCurrentFile = getOutputMediaFile()!!
        mediaRecorder.setOutputFile(mCurrentFile!!.absolutePath)
        val profile = CamcorderProfile.get(CamcorderProfile.QUALITY_480P)
        mediaRecorder.setVideoFrameRate(profile.videoFrameRate)
        mediaRecorder.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight)
        println("SIZE *************************** ${profile.videoFrameWidth} ----- ${profile.videoFrameHeight}")
        mediaRecorder.setVideoEncodingBitRate(profile.videoBitRate)
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mediaRecorder.setAudioEncodingBitRate(profile.audioBitRate)
        mediaRecorder.setAudioSamplingRate(profile.audioSampleRate)
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display!!.rotation else windowManager.defaultDisplay.rotation
        when (rotation) {
            SENSOR_ORIENTATION_DEFAULT_DEGREES -> mediaRecorder.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation))
            SENSOR_ORIENTATION_INVERSE_DEGREES -> mediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation))
        }
        mediaRecorder.prepare()
    }

    private fun emitTime(input: Long): String {
        val instance = Calendar.getInstance()
        instance.timeInMillis = input
        val secs = instance.get(Calendar.SECOND).toString()
        val min = instance.get(Calendar.MINUTE).toString()
        val hour = instance.get(Calendar.HOUR_OF_DAY).toString()
        val time = if (min == "0") "0:$secs" else "$min:$secs"
        return if (hour == "1") time else "$hour:$min:$secs"
    }

    private fun startRecordingVideo() {
        try {
            closePreviewSession()
            setUpMediaRecorder()
            val texture: SurfaceTexture = binding.videoTextureView.surfaceTexture!!
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            Util.setTextureTransform(characteristics, binding.videoTextureView)
            texture.setDefaultBufferSize(mPreviewSize.width, mPreviewSize.height)
            captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

            val previewSurface = Surface(texture)
            val recorderSurface: Surface = mediaRecorder.surface
            val surfaces: MutableList<Surface> = arrayListOf(previewSurface, recorderSurface)
            captureBuilder!!.addTarget(previewSurface)
            captureBuilder!!.addTarget(recorderSurface)
            // Start a capture session
            cameraDevice!!.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cameraCaptureSessionL: CameraCaptureSession) {
                    cameraCaptureSession = cameraCaptureSessionL
                    updatePreview()
                    runOnUiThread {
                        mIsRecordingVideo = true
                        Snackbar.make(binding.root, "Started recording", Snackbar.LENGTH_LONG).show()
                        binding.recordCircle.visibility = View.VISIBLE
                        mediaRecorder.start()
                        scope.launch {
                            (1..Int.MAX_VALUE).asFlow().collect { value ->
                                delay(1000)
                                runOnUiThread {
                                    if (!mIsRecordingVideo) return@runOnUiThread
                                    binding.videoTimer.text = emitTime((value * 1000).toLong())
                                }
                            }
                        }
                    }
                }

                override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                }
            }, handler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    @Throws(java.lang.Exception::class)
    fun stopRecordingVideo() {
        mIsRecordingVideo = false
        Snackbar.make(binding.root, "Record saved at ${mCurrentFile!!.absolutePath}", Snackbar.LENGTH_LONG).show()
        try {
            if (null != cameraDevice) {
                mediaRecorder.stop()
                mediaRecorder.release()
            }
            closePreviewSession()
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.")
        }
        /*try {
            cameraCaptureSession!!.stopRepeating()
            cameraCaptureSession!!.abortCaptures()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        mediaRecorder.reset()*/
        binding.recordCircle.visibility = View.GONE
    }

    /**
     * Create directory and return file
     * returning video file
     */
    private fun getOutputMediaFile(): File? {
        // External sdcard file location
        val mediaStorageDir = File(Environment.getExternalStorageDirectory(), "Camera-IO")
        // Create storage directory if it does not exist
        if (!mediaStorageDir.exists() && !mediaStorageDir.mkdirs()) return null
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(mediaStorageDir.path + File.separator + "VID_" + timeStamp + ".mp4")
    }

    private val captureSessionCallbackListener = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            super.onCaptureStarted(session, request, timestamp, frameNumber)
        }

        override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
            super.onCaptureProgressed(session, request, partialResult)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            super.onCaptureCompleted(session, request, result)
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            super.onCaptureFailed(session, request, failure)
        }

        override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
            super.onCaptureSequenceCompleted(session, sequenceId, frameNumber)
        }

        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            super.onCaptureSequenceAborted(session, sequenceId)
        }

        override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
            super.onCaptureBufferLost(session, request, target, frameNumber)
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.enter_right_to_left, R.anim.exit_right_to_left)
    }
}