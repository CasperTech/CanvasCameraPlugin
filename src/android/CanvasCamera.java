package com.virtuoworks.cordova.plugin.canvascamera;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.*;
import android.util.Base64;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.*;
import android.widget.Toast;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.*;

public class CanvasCamera extends CordovaPlugin
{
    private static final String TAG = "CanvasCamera";
    private static final int REQUEST_CAMERA_PERMISSION_RESULT = 0;
    private static final int REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT = 1;
    private static final boolean LOGGING = false; //false to disable logging

    private final static String[] PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    protected final String K_WIDTH_KEY = "width";
    protected final String K_HEIGHT_KEY = "height";
    protected final String K_CANVAS_KEY = "canvas";
    protected final String K_CAPTURE_KEY = "capture";
    protected final String K_SCAN = "scan";

    private static final int SEC_START_CAPTURE = 0;
    private static final int SEC_STOP_CAPTURE = 1;
    private static final int SEC_FLASH_MODE = 2;
    private static final int SEC_CAMERA_POSITION = 3;

    private Activity mActivity = null;
    private TextureView mTextureView;
    private CameraDevice mCameraDevice;
    private HandlerThread mBackgroundHandlerThread;
    private Handler mBackgroundHandler;
    private String mCameraId;
    private Size mPreviewSize;
    private String lastResult = "";
    private ImageReader mImageReader;
    private JSONArray mArgs;
    private CallbackContext mStartCaptureCallbackContext;
    private CallbackContext mCurrentCallbackContext;
    private int count;
    private boolean mScan = false;
    private int mWidth = 1280;
    private int mHeight = 720;
    private int mCanvasWidth = 1280;
    private int mCanvasHeight = 720;
    private int mImageFormat = ImageFormat.YUV_420_888;
    private boolean mPreviewing = true;
    private SurfaceTexture mPreviewSurface;

    private boolean shownDialog = false;
    private volatile boolean decodeRunning = false;

    private boolean initPreviewSurface()
    {
        if (mActivity != null)
        {
            mTextureView = new TextureView(mActivity);
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
            WindowManager mW = (WindowManager) mActivity.getSystemService(Context.WINDOW_SERVICE);
            int screenWidth = mW.getDefaultDisplay().getWidth();
            int screenHeight = mW.getDefaultDisplay().getHeight();
            mActivity.addContentView(mTextureView, new ViewGroup.LayoutParams(screenWidth, screenHeight));
            if (LOGGING) Log.i(TAG, "Camera preview surface initialized.");
            return true;
        }
        else
        {
            if (LOGGING) Log.w(TAG, "Could not initialize preview surface.");
            return false;
        }
    }

    private void removePreviewSurface()
    {
        if (mTextureView != null)
        {
            try
            {
                ViewGroup parentViewGroup = (ViewGroup) mTextureView.getParent();
                if (parentViewGroup != null)
                {
                    parentViewGroup.removeView(mTextureView);
                }
                if (LOGGING) Log.i(TAG, "Camera preview surface removed.");
            }
            catch (Exception e)
            {
                if (LOGGING) Log.w(TAG, "Could not remove view : " + e.getMessage());
            }
        }
    }

    private TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener()
    {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height)
        {
            mPreviewSurface = surface;
            mTextureView.setVisibility(View.INVISIBLE);
            mTextureView.setAlpha(0);
            setupCamera(width, height);
            connectCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height)
        {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface)
        {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface)
        {

        }
    };

    private void configureTransform(int viewWidth, int viewHeight)
    {
        if (null == mTextureView || null == mPreviewSize)
        {
            return;
        }
        int rotation = mActivity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation)
        {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) viewHeight / mPreviewSize.getHeight(), (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        else if (Surface.ROTATION_180 == rotation)
        {
            matrix.postRotate(180, centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    private CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback()
    {
        @Override
        public void onOpened(CameraDevice camera)
        {
            mCameraDevice = camera;
            startPreview();
        }

        @Override
        public void onDisconnected(CameraDevice camera)
        {
            camera.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error)
        {
            camera.close();
            mCameraDevice = null;
        }
    };

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView)
    {
        mActivity = cordova.getActivity();
        super.initialize(cordova, webView);
    }

    @Override
    public void onStart()
    {
        super.onStart();
    }

    @Override
    public void onStop()
    {
        super.onStop();
        if (mTextureView != null)
        {
            removePreviewSurface();
        }
        mPreviewing = false;
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();
        cordova.getThreadPool().shutdownNow();
    }

    @Override
    public void onResume(boolean multitasking)
    {
        super.onResume(multitasking);
        if (mPreviewing && mTextureView != null)
        {
            startCamera();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig)
    {
        super.onConfigurationChanged(newConfig);
    }

    private void showDialog(String message)
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity);
        builder.setMessage(message)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        // FIRE ZE MISSILES!
                    }
                });
        // Create the AlertDialog object and return it
        builder.create().show();
    }

    private boolean startCamera()
    {
        return initPreviewSurface();
    }

    private int getDisplayOrientation()
    {
        return 0;
    }

    private int getDisplayRotation()
    {
        int rotation = mActivity.getWindowManager().getDefaultDisplay().getRotation();

        int degrees = 0;
        switch (rotation)
        {
            case Surface.ROTATION_0:
                degrees = 0;
                break; // Natural orientation.
            case Surface.ROTATION_90:
                degrees = 90;
                break; // Landscape left.
            case Surface.ROTATION_180:
                degrees = 180;
                break; // Upside down.
            case Surface.ROTATION_270:
                degrees = 270;
                break; // Landscape right.
            default:
                degrees = 0;
                break;
        }

        return degrees;
    }

    private byte[] dataToJpeg(byte[] byteArray, int width, int height)
    {
        if (byteArray.length > 0)
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // The second parameter is the actual image format
            YuvImage yuvImage = new YuvImage(byteArray, ImageFormat.NV21, width, height, null);
            // width and height define the size of the bitmap filled with the preview image
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 100, out);
            // returns the jpeg as bytes array
            return out.toByteArray();
        }
        else
        {
            return byteArray;
        }
    }

    private int[] calculateAspectRatio(int origWidth, int origHeight, int targetWidth, int targetHeight)
    {
        int newWidth = targetWidth;
        int newHeight = targetHeight;

        // If no new width or height were specified return the original bitmap
        if (newWidth <= 0 && newHeight <= 0)
        {
            newWidth = origWidth;
            newHeight = origHeight;
        }
        // Only the width was specified
        else if (newWidth > 0 && newHeight <= 0)
        {
            newHeight = (int) (newWidth / (double) origWidth * origHeight);
        }
        // only the height was specified
        else if (newWidth <= 0 && newHeight > 0)
        {
            newWidth = (int) (newHeight / (double) origHeight * origWidth);
        }
        // If the user specified both a positive width and height
        // (potentially different aspect ratio) then the width or height is
        // scaled so that the image fits while maintaining aspect ratio.
        // Alternatively, the specified width and height could have been
        // kept and Bitmap.SCALE_TO_FIT specified when scaling, but this
        // would result in whitespace in the new image.
        else
        {
            double newRatio = newWidth / (double) newHeight;
            double origRatio = origWidth / (double) origHeight;

            if (origRatio > newRatio)
            {
                newHeight = (newWidth * origHeight) / origWidth;
            }
            else if (origRatio < newRatio)
            {
                newWidth = (newHeight * origWidth) / origHeight;
            }
        }

        int[] widthHeight = new int[2];
        widthHeight[0] = newWidth;
        widthHeight[1] = newHeight;

        return widthHeight;
    }

    private byte[] getResizedAndRotatedImage(byte[] byteArray, int targetWidth, int targetHeight, int angle)
    {
        if (byteArray.length > 0)
        {
            // Sets bitmap factory options
            BitmapFactory.Options bOptions = new BitmapFactory.Options();
            // Set inJustDecodeBounds=true to check dimensions
            bOptions.inJustDecodeBounds = true;
            // Decode unscaled unrotated bitmap boundaries only
            BitmapFactory.decodeByteArray(byteArray, 0, byteArray.length, bOptions);

            if (targetWidth > 0 && targetHeight > 0)
            {
                // Calculate aspect ratio
                int[] widthHeight = calculateAspectRatio(bOptions.outWidth, bOptions.outHeight, targetWidth, targetHeight);

                int width = widthHeight[0];
                int height = widthHeight[1];

                bOptions.inSampleSize = 1;
                // Adjust inSampleSize
                if (bOptions.outHeight > height || bOptions.outWidth > width)
                {
                    final int halfOutHeight = bOptions.outHeight / 2;
                    final int halfOutWidth = bOptions.outWidth / 2;

                    while ((halfOutHeight / bOptions.inSampleSize) >= height && (halfOutWidth / bOptions.inSampleSize) >= width)
                    {
                        bOptions.inSampleSize *= 2;
                    }
                }
                // Set inJustDecodeBounds=false to get all pixels
                bOptions.inJustDecodeBounds = false;
                // Decode unscaled unrotated bitmap
                Bitmap bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.length, bOptions);
                // Create scaled bitmap
                bitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);

                if (angle != 0)
                {
                    final Matrix matrix = new Matrix();


                    // Rotation ?
                    if (angle != 0)
                    {
                        // Rotation
                        matrix.postRotate(angle);
                    }

                    // Create rotated bitmap
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, false);
                }

                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream);

                // Recycling bitmap
                bitmap.recycle();

                return byteArrayOutputStream.toByteArray();
            }
            else
            {
                return byteArray;
            }
        }
        else
        {
            return byteArray;
        }
    }

    private void crash() {
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(1);
    }

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener()
    {

        @Override
        public void onImageAvailable(final ImageReader reader)
        {
            Runnable renderFrame = new Runnable()
            {
                public void run()
                {
                    Log.e(TAG, "onImageAvailable: " + count++);
                    Image img = null;
                    img = reader.acquireLatestImage();

                    if (img == null) return;

                    try
                    {
                        /*
                        ByteBuffer buffer = img.getPlanes()[0].getBuffer();
                        byte[] data = new byte[buffer.remaining()];
                        buffer.get(data);
                        int width = img.getWidth();
                        int height = img.getHeight();
                        */

                        Image.Plane Y = img.getPlanes()[0];
                        Image.Plane U = img.getPlanes()[1];
                        Image.Plane V = img.getPlanes()[2];

                        int Yb = Y.getBuffer().remaining();
                        int Ub = U.getBuffer().remaining();
                        int Vb = V.getBuffer().remaining();

                        final byte[] data = new byte[Yb + Ub + Vb];
                        //your data length should be this byte array length.

                        Y.getBuffer().get(data, 0, Yb);
                        U.getBuffer().get(data, Yb, Ub);
                        V.getBuffer().get(data, Yb+ Ub, Vb);
                        final int width = img.getWidth();
                        final int height = img.getHeight();


                        if (mPreviewing && data.length > 0)
                        {
                            // Get display orientation.
                            int displayOrientation = getDisplayOrientation();
                            // Getting output file paths.

                            if (mScan)
                            {

                                if (!decodeRunning)
                                {
                                    decodeRunning = true;
                                    Runnable r = new Runnable()
                                    {
                                        public void run()
                                        {
                                            LuminanceSource source = new PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false);
                                            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
                                            String sResult = "";

                                            MultiFormatReader multiFormatReader = new MultiFormatReader();
                                            GenericMultipleBarcodeReader reader = new GenericMultipleBarcodeReader(multiFormatReader);

                                            Hashtable<DecodeHintType, Object> hints = new Hashtable<DecodeHintType, Object>();
                                            try
                                            {
                                                Result[] results = reader.decodeMultiple(bitmap, hints);
                                                JSONObject output = new JSONObject();

                                                JSONArray barcodes = new JSONArray();
                                                for (Result result : results)
                                                {
                                                    JSONObject codeResult = new JSONObject();

                                                    String barcodeText = result.getText();
                                                    try
                                                    {
                                                        codeResult.put("codeText", barcodeText);
                                                    }
                                                    catch (JSONException e)
                                                    {

                                                    }
                                                    JSONArray points = new JSONArray();
                                                    ResultPoint[] resultPoints = result.getResultPoints();
                                                    for (ResultPoint point : resultPoints)
                                                    {
                                                        JSONObject coords = new JSONObject();
                                                        try
                                                        {
                                                            coords.put("x", point.getX());
                                                            coords.put("y", point.getY());
                                                            points.put(coords);
                                                        }
                                                        catch (JSONException e)
                                                        {

                                                        }
                                                    }
                                                    try
                                                    {
                                                        codeResult.put("coordinates", points);
                                                        barcodes.put(codeResult);
                                                    }
                                                    catch (JSONException e)
                                                    {

                                                    }
                                                }
                                                try
                                                {
                                                    output.put("codes", barcodes);
                                                }
                                                catch (JSONException e)
                                                {

                                                }
                                                PluginResult result = new PluginResult(PluginResult.Status.OK, getPluginResultMessage("OK", output));
                                                result.setKeepCallback(true);
                                                mStartCaptureCallbackContext.sendPluginResult(result);
                                            }
                                            catch (NotFoundException e)
                                            {

                                            }
                                            finally
                                            {
                                                decodeRunning = false;
                                            }
                                        }
                                    };
                                    Thread t = new Thread(r);
                                    t.start();
                                }

/*
                                LuminanceSource source = new PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false);
                                BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
                                String sResult = "";

                                MultiFormatReader multiFormatReader = new MultiFormatReader();
                                GenericMultipleBarcodeReader reader = new GenericMultipleBarcodeReader(multiFormatReader);

                                Hashtable<DecodeHintType, Object> hints = new Hashtable<DecodeHintType, Object>();
                                //hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

                                try
                                {
                                    results = reader.decodeMultiple(bitmap, hints);
                                }
                                catch (NotFoundException e)
                                {

                                }

 */
                            }

                            // JSON output for images.
                            JSONObject images = new JSONObject();

                            // Creating fullsize image.
                            byte[] fullsizeData = dataToJpeg(data, width, height);

                            fullsizeData = getResizedAndRotatedImage(fullsizeData, mCanvasWidth, mCanvasHeight, displayOrientation);

                            // JSON output for fullsize image
                            JSONObject fullsize = new JSONObject();

                            String fullsizeDataToB64 = "data:image/jpeg;base64," + Base64.encodeToString(fullsizeData, Base64.DEFAULT);
                            try
                            {
                                fullsize.put("data", fullsizeDataToB64);
                            }
                            catch (JSONException e)
                            {
                                if (LOGGING)
                                    Log.e(TAG, "Cannot put data.output.images.fullsize.data  into JSON result : " + e.getMessage());
                            }
                            if (fullsize.length() > 0)
                            {
                                try
                                {
                                    images.put("fullsize", fullsize);

                                    try
                                    {
                                        fullsize.put("rotation", displayOrientation);
                                    }
                                    catch (JSONException e)
                                    {
                                        if (LOGGING)
                                            Log.e(TAG, "Cannot put data.output.images.fullsize.rotation into JSON result : " + e.getMessage());
                                    }

                                    try
                                    {
                                        fullsize.put("timestamp", (new java.util.Date()).getTime());
                                    }
                                    catch (JSONException e)
                                    {
                                        if (LOGGING)
                                            Log.e(TAG, "Cannot put data.output.images.fullsize.timestamp into JSON result : " + e.getMessage());
                                    }

                                }
                                catch (JSONException e)
                                {
                                    if (LOGGING)
                                        Log.e(TAG, "Cannot put data.output.images.fullsize into JSON result : " + e.getMessage());
                                }

                                // JSON output
                                JSONObject output = new JSONObject();


                                try
                                {
                                    output.put("images", images);
                                }
                                catch (JSONException e)
                                {
                                    if (LOGGING)
                                        Log.e(TAG, "Cannot put data.output.images into JSON result : " + e.getMessage());
                                }

                                if (mPreviewing)
                                {
                                    PluginResult result = new PluginResult(PluginResult.Status.OK, getPluginResultMessage("OK", output));
                                    result.setKeepCallback(true);
                                    mStartCaptureCallbackContext.sendPluginResult(result);
                                }
                            }
                        }
                    }
                    catch (Exception ignored)
                    {
                        Log.e(TAG, "Reader shows an exception! ", ignored);
                        /* Ignored */
                    }
                    finally
                    {
                        if (img != null) img.close();

                    }
                }
            };
            cordova.getThreadPool().submit(renderFrame);
        }

    };

    private void setupCamera(int width, int height)
    {
        CameraManager cameraManager = (CameraManager)mActivity.getSystemService(Context.CAMERA_SERVICE);
        try
        {
            for (String cameraId : cameraManager.getCameraIdList())
            {
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);

                StringBuilder stringBuilder = new StringBuilder();

                for (int i=0; i<cameraCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES).length; i++)
                {
                    if (cameraCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)[i] == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)
                    {
                        stringBuilder.append("BACKWARD_COMPATIBLE" + "  ");
                    }
                    else if (cameraCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)[i] == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
                    {
                        stringBuilder.append("MANUAL_POST_PROCESSING" + "  ");
                    }
                    else if (cameraCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)[i] == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                    {
                        stringBuilder.append("MANUAL_SENSOR" + "  ");
                    }
                    else if (cameraCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)[i] == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
                    {
                        stringBuilder.append("RAW" + "  ");
                    }
                }
                Log.d("Capabilities: ", stringBuilder.toString());

                int[] h = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);

                float yourMinFocus = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                float yourMaxFocus = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE);

                List<CaptureResult.Key<?>> s = cameraCharacteristics.getAvailableCaptureResultKeys();

                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT)
                {
                    continue;
                }
                /*
                StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                int deviceOrientation = getWindowManager().getDefaultDisplay().getRotation();
                mTotalRotation = sensorToDeviceRotation(cameraCharacteristics, deviceOrientation);
                boolean swapRotation = mTotalRotation == 90 || mTotalRotation == 270;
                int rotatedWidth = width;
                int rotatedHeight = height;
                swapRotation = false;
                if (swapRotation)
                {
                    rotatedWidth = height;
                    rotatedHeight = width;
                }
                */

                //List<Size> outputSizes = Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888));
                //Size largest = Collections.max(outputSizes, new CompareSizeByArea());

                mPreviewSize = new Size(mWidth, mHeight);
                mImageReader = ImageReader.newInstance(mWidth, mHeight, mImageFormat, 2);
                mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);
                mCameraId = cameraId;
                return;
            }
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }
    }

    private void connectCamera()
    {
        CameraManager cameraManager = (CameraManager) mActivity.getSystemService(Context.CAMERA_SERVICE);
        try
        {
            cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler);
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }
    }

    private void startPreview()
    {
        try
        {
            Surface mImageSurface = mImageReader.getSurface();
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            Surface surface = new Surface(mPreviewSurface);

            mPreviewRequestBuilder.addTarget(mImageSurface);
            mPreviewRequestBuilder.addTarget(surface);
            mCameraDevice.createCaptureSession(Arrays.asList(mImageSurface, surface), new CameraCaptureSession.StateCallback()
            {

                @Override
                public void onConfigured(CameraCaptureSession cameraCaptureSession)
                {
                    // The camera is already closed
                    Log.e(TAG, "onConfigured");
                    if (mCameraDevice == null) return;

                    // When the session is ready, we start displaying the preview.
                    mPreviewCaptureSession = cameraCaptureSession;
                    try
                    {
                        CameraManager cameraManager = (CameraManager)mActivity.getSystemService(Context.CAMERA_SERVICE);
                        CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(mCameraId);
                        int level = cameraCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);


                        // Auto focus should be continuous for camera preview.
                        mPreviewRequestBuilder.set( CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                        mPreviewRequestBuilder.set( CaptureRequest.LENS_FOCUS_DISTANCE, cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE));
                        // Flash is automatically enabled when necessary.
                        //                                mPreviewRequestBuilder.set(CONTROL_AE_MODE, CONTROL_AE_MODE_ON_AUTO_FLASH); // no need for flash now

                        // Finally, we start displaying the camera preview.
                        mPreviewRequest = mPreviewRequestBuilder.build();
                        mPreviewCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler);
                    }
                    catch (CameraAccessException e)
                    {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession cameraCaptureSession)
                {
                    Log.d(TAG, "onConfigureFailed: startPreview");
                }
            }, null);
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }
    }

    private CameraCaptureSession mPreviewCaptureSession;
    private final CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback()
    {
        private void process(CaptureResult result)
        {
        }

        @Override
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request, CaptureResult partialResult)
        {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result)
        {
            process(result);
        }

    };
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private CaptureRequest mPreviewRequest;


    private static SparseIntArray ORIENTATIONS = new SparseIntArray();

    static
    {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    private void closeCamera()
    {
        if (mCameraDevice != null)
        {
            mCameraDevice.close();
            mCameraDevice = null;
        }
    }

    private void startBackgroundThread()
    {
        mBackgroundHandlerThread = new HandlerThread("Camera2VideoImage");
        mBackgroundHandlerThread.start();
        mBackgroundHandler = new Handler(mBackgroundHandlerThread.getLooper());
    }

    private void stopBackgroundThread()
    {
        mBackgroundHandlerThread.quitSafely();
        try
        {
            mBackgroundHandlerThread.join();
            mBackgroundHandlerThread = null;
            mBackgroundHandler = null;
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }

    private JSONObject getPluginResultMessage(String message)
    {

        JSONObject output = new JSONObject();
        JSONObject images = new JSONObject();

        try
        {
            output.put("images", images);
        }
        catch (JSONException e)
        {
            if (LOGGING) Log.e(TAG, "Cannot put data.output.images into JSON result : " + e.getMessage());
        }

        return getPluginResultMessage(message, output);
    }

    private JSONObject getPluginResultMessage(String message, JSONObject output)
    {

        JSONObject pluginResultMessage = new JSONObject();
        try
        {
            pluginResultMessage.put("output", output);
        }
        catch(JSONException e)
        {

        }
        return pluginResultMessage;
    }

    private synchronized void startCapture(CallbackContext callbackContext)
    {
        mStartCaptureCallbackContext = callbackContext;

        if (startCamera())
        {
            deferPluginResultCallback(mStartCaptureCallbackContext);
            if (LOGGING) Log.i(TAG, "Capture started !");
        }
        else
        {
            mStartCaptureCallbackContext.sendPluginResult(new PluginResult(PluginResult.Status.INVALID_ACTION, getPluginResultMessage("Unable to start capture.")));
        }
    }

    private synchronized void startCapture(JSONArray args, CallbackContext callbackContext)
    {

        mStartCaptureCallbackContext = callbackContext;

        // parse options
        try
        {
            parseOptions(args.getJSONObject(0));
        }
        catch (Exception e)
        {
            if (LOGGING) Log.e(TAG, "Options parsing error : " + e.getMessage());
            mStartCaptureCallbackContext.sendPluginResult(new PluginResult(PluginResult.Status.JSON_EXCEPTION, getPluginResultMessage(e.getMessage())));
            return;
        }

        startCapture(mStartCaptureCallbackContext);
    }

    private void deferPluginResultCallback(final CallbackContext callbackContext)
    {
        PluginResult pluginResult = new PluginResult(PluginResult.Status.NO_RESULT);
        pluginResult.setKeepCallback(true);
        callbackContext.sendPluginResult(pluginResult);
    }

    private void parseOptions(JSONObject options) throws Exception
    {
        if (options == null)
        {
            return;
        }


        if (options.has(K_SCAN))
        {
            mScan = options.getBoolean(K_SCAN);
        }

        if (options.has(K_WIDTH_KEY))
        {
            mWidth = canvas.getInt(K_WIDTH_KEY);
        }
        if (options.has(K_HEIGHT_KEY))
        {
            mHeight = canvas.getInt(K_HEIGHT_KEY);
        }

        // canvas
        if (options.has(K_CANVAS_KEY))
        {
            JSONObject canvas = options.getJSONObject(K_CANVAS_KEY);
            if (canvas.has(K_WIDTH_KEY))
            {
                mCanvasWidth = canvas.getInt(K_WIDTH_KEY);
            }
            if (canvas.has(K_HEIGHT_KEY))
            {
                mCanvasHeight = canvas.getInt(K_HEIGHT_KEY);
            }
        }
    }

    @Override
    public boolean execute(String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException
    {
        mArgs = args;
        mCurrentCallbackContext = callbackContext;

        if (PermissionHelper.hasPermission(this, Manifest.permission.CAMERA) && PermissionHelper.hasPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) && PermissionHelper.hasPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        {
            if ("startCapture".equals(action))
            {
                if (LOGGING) Log.i(TAG, "Starting async startCapture thread...");
                mActivity.runOnUiThread(new Runnable()
                {
                    public void run()
                    {
                        startCapture(mArgs, mCurrentCallbackContext);
                    }
                });
                return true;
            }
            else if ("stopCapture".equals(action))
            {
                if (LOGGING) Log.i(TAG, "Starting async stopCapture thread...");
                mActivity.runOnUiThread(new Runnable()
                {
                    public void run()
                    {
                        //stopCapture(mCurrentCallbackContext);
                    }
                });
                return true;
            }
            else if ("flashMode".equals(action))
            {
                if (LOGGING) Log.i(TAG, "Starting async flashMode thread...");
                mActivity.runOnUiThread(new Runnable()
                {
                    public void run()
                    {
                        //flashMode(mArgs, mCurrentCallbackContext);
                    }
                });
                return true;
            }
            else if ("cameraPosition".equals(action))
            {
                if (LOGGING) Log.i(TAG, "Starting async cameraPosition thread...");
                mActivity.runOnUiThread(new Runnable()
                {
                    public void run()
                    {
                        // cameraPosition(mArgs, mCurrentCallbackContext);
                    }
                });
                return true;
            }
        }
        else
        {
            if ("startCapture".equals(action))
            {
                deferPluginResultCallback(mCurrentCallbackContext);
                PermissionHelper.requestPermissions(this, SEC_START_CAPTURE, PERMISSIONS);
                return true;
            }
            else if ("stopCapture".equals(action))
            {
                deferPluginResultCallback(mCurrentCallbackContext);
                PermissionHelper.requestPermission(this, SEC_STOP_CAPTURE, Manifest.permission.CAMERA);
                return true;
            }
            else if ("flashMode".equals(action))
            {
                deferPluginResultCallback(mCurrentCallbackContext);
                PermissionHelper.requestPermission(this, SEC_FLASH_MODE, Manifest.permission.CAMERA);
                return true;
            }
            else if ("cameraPosition".equals(action))
            {
                deferPluginResultCallback(mCurrentCallbackContext);
                PermissionHelper.requestPermission(this, SEC_CAMERA_POSITION, Manifest.permission.CAMERA);
                return true;
            }
        }

        return false;
    }
}
