package com.iodaniel.cameraio

import android.content.ContentValues.TAG
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.util.*


object Util {

    fun getFirstCameraIdFacing(context: Context, facing: Int = CameraMetadata.LENS_FACING_BACK): String? {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            // Get list of all compatible cameras
            val cameraIds = cameraManager.cameraIdList.filter {
                val characteristics = cameraManager.getCameraCharacteristics(it)
                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) ?: false
            }

            // Iterate over the list of cameras and return the first one matching desired
            // lens-facing configuration
            cameraIds.forEach {
                val characteristics = cameraManager.getCameraCharacteristics(it)
                if (characteristics.get(CameraCharacteristics.LENS_FACING) == facing) {
                    return it
                }
            }

            // If no camera matched desired orientation, return the first one from the list
            return cameraIds.firstOrNull()
        } catch (e: CameraAccessException) {

        }
        return ""
    }

    fun filterCameraIdsFacing(context: Context, cameraIds: List<String>, facing: Int): List<String> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return cameraIds.filter {
            val characteristics = cameraManager.getCameraCharacteristics(it)
            characteristics.get(CameraCharacteristics.LENS_FACING) == facing
        }
    }

    fun getCameraList(context: Context): MutableList<String> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return cameraManager.cameraIdList.toMutableList()
    }

    fun setTextureTransform(characteristics: CameraCharacteristics, textureView: TextureView) {
        val previewSize: Size = getPreviewSize(characteristics)
        val width: Int = previewSize.width
        val height: Int = previewSize.height
        val sensorOrientation = getCameraSensorOrientation(characteristics)
        // Indicate the size of the buffer the texture should expect
        textureView.surfaceTexture!!.setDefaultBufferSize(width, height)
        // Save the texture dimensions in a rectangle
        val viewRect = RectF(0F, 0F, textureView.width.toFloat(), textureView.height.toFloat())
        // Determine the rotation of the display
        var rotationDegrees = 0f
        try {
            rotationDegrees = getDisplayRotation(textureView).toFloat()
        } catch (ignored: Exception) {
        }
        val w: Float
        val h: Float
        if ((sensorOrientation - rotationDegrees) % 180 == 0f) {
            w = width.toFloat()
            h = height.toFloat()
        } else {
            // Swap the width and height if the sensor orientation and display rotation don't match
            w = height.toFloat()
            h = width.toFloat()
        }
        val viewAspectRatio = viewRect.width() / viewRect.height()
        val imageAspectRatio = w / h
        // This will make the camera frame fill the texture view, if you'd like to fit it into the view swap the "<" sign for ">"
        val scale: PointF = if (viewAspectRatio < imageAspectRatio) {
            // If the view is "thinner" than the image constrain the height and calculate the scale for the texture width
            PointF(viewRect.height() / viewRect.width() * (height.toFloat() / width.toFloat()), 1f)
        } else {
            PointF(1f, viewRect.width() / viewRect.height() * (width.toFloat() / height.toFloat()))
        }
        if (rotationDegrees % 180 != 0f) {
            // If we need to rotate the texture 90º we need to adjust the scale
            val multiplier = if (viewAspectRatio < imageAspectRatio) w / h else h / w
            scale.x *= multiplier
            scale.y *= multiplier
        }
        val matrix = Matrix()
        // Set the scale
        matrix.setScale(scale.x, scale.y, viewRect.centerX(), viewRect.centerY())
        if (rotationDegrees != 0f) {
            // Set rotation of the device isn't upright
            matrix.postRotate(0 - rotationDegrees, viewRect.centerX(), viewRect.centerY())
        }
        // Transform the texture
        textureView.setTransform(matrix)
    }

    private fun getDisplayRotation(textureView: TextureView): Int {
        return when (textureView.display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun getPreviewSize(characteristics: CameraCharacteristics): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewSizes: Array<Size> = map!!.getOutputSizes(SurfaceTexture::class.java)
        // TODO: decide on which size fits your view size the best
        return previewSizes[0]
    }

    private fun getCameraSensorOrientation(characteristics: CameraCharacteristics): Int {
        val cameraOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
        return (360 - (cameraOrientation ?: 0)) % 360
    }

    /**
     * In this sample, we choose a video size with 3x4 for  aspect ratio. for more perfectness 720 as
     * well Also, we don't use sizes
     * larger than 1080p, since MediaRecorder cannot handle such a high-resolution video.
     *
     * @param choices The list of available sizes
     * @return The video size 1080p,720px
     */
    fun chooseVideoSize(choices: Array<Size>): Size {
        for (size in choices) {
            if (1920 == size.width && 1080 == size.height) {
                return size
            }
        }
        for (size in choices) {
            if (size.width == size.height * 4 / 3 && size.width <= 1080) {
                return size
            }
        }
        Log.e(TAG, "Couldn't find any suitable video size")
        return choices[choices.size - 1]
    }

    /**
     * Given `choices` of `Size`s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values, and whose aspect
     * ratio matches with the specified value.
     *
     * @param choices The list of sizes that the camera supports for the intended output class
     * @param width The minimum desired width
     * @param height The minimum desired height
     * @param aspectRatio The aspect ratio
     * @return The optimal `Size`, or an arbitrary one if none were big enough
     */

    fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int, aspectRatio: Size): Size? {
        // Collect the supported resolutions that are at least as big as the preview Surface
        val bigEnough: MutableList<Size> = ArrayList()
        val w = aspectRatio.width
        val h = aspectRatio.height
        for (option in choices) {
            if (option.height == option.width * h / w && option.width >= width && option.height >= height) {
                bigEnough.add(option)
            }
        }
        // Pick the smallest of those, assuming we found any
        return if (bigEnough.size > 0) {
            Collections.min(bigEnough, CompareSizesByArea())
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size")
            choices[0]
        }
    }

    /**
     * Compares two `Size`s based on their areas.
     */
    internal class CompareSizesByArea : Comparator<Size?> {
        override fun compare(o1: Size?, o2: Size?): Int {
            return java.lang.Long.signum(o1!!.width.toLong() * o1.height - o2!!.width.toLong() * o2.height)
        }
    }
}