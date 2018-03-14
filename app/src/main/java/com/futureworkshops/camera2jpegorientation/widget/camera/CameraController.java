package com.futureworkshops.camera2jpegorientation.widget.camera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.Single;
import io.reactivex.SingleEmitter;
import io.reactivex.SingleOnSubscribe;

/**
 * Created by stelian on 16/08/2017.
 */

public class CameraController {
    
    public static final int FLASH_MODE_OFF = 0;
    public static final int FLASH_MODE_ON = 1;
    public static final int FLASH_MODE_TORCH = 2;
    public static final int FLASH_MODE_AUTO = 3;
    public static final int FLASH_MODE_RED_EYE = 4;
    
    /**
     * Tag for the {@link Log}.
     */
    private static final String TAG = CameraController.class.getCanonicalName();
    
    /**
     * Camera state: Device is closed.
     */
    private static final int STATE_CLOSED = 0;
    
    /**
     * Camera state: Device is opened, but is not capturing.
     */
    private static final int STATE_OPENED = 1;
    
    /**
     * Camera state: Showing camera preview.
     */
    private static final int STATE_PREVIEW = 2;
    
    /**
     * Camera state: Waiting for 3A convergence before capturing a photo.
     */
    private static final int STATE_WAITING_FOR_3A_CONVERGENCE = 3;
    
    /**
     * Timeout for the pre-capture sequence.
     */
    private static final long PRECAPTURE_TIMEOUT_MS = 1000;
    
    /**
     * Default aspect ratio of the screen. Most devices (phones) support 16:9 aspect ratio that's why we
     * choose it.
     */
    private static final Size DEFAULT_ASPECT_RATIO = new Size(1920, 1080);
    
    @IntDef({
        FLASH_MODE_OFF,
        FLASH_MODE_ON,
        FLASH_MODE_TORCH,
        FLASH_MODE_AUTO,
        FLASH_MODE_RED_EYE
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface InternalFlashMode {
    
    }
    
    private static final SparseIntArray INTERNAL_FACINGS = new SparseIntArray();
    
    static {
        INTERNAL_FACINGS.put(CameraParams.FACING_BACK, CameraCharacteristics.LENS_FACING_BACK);
        INTERNAL_FACINGS.put(CameraParams.FACING_FRONT, CameraCharacteristics.LENS_FACING_FRONT);
    }
    
    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }
    
    /**
     * An additional thread for running tasks that shouldn't block the UI.  This is used for all
     * callbacks from the {@link CameraDevice} and {@link CameraCaptureSession}s.
     */
    private HandlerThread mBackgroundThread;
    
    
    /**
     * A counter for tracking corresponding {@link CaptureRequest}s and {@link CaptureResult}s
     * across the {@link CameraCaptureSession} capture callbacks.
     */
    private final AtomicInteger mRequestCounter = new AtomicInteger();
    
    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private final Semaphore mCameraOpenCloseLock = new Semaphore(1);
    
    /**
     * A lock protecting camera state.
     */
    private final Object mCameraStateLock = new Object();
    
    
    // *********************************************************************************************
    // State protected by mCameraStateLock.
    //
    // The following state is used across both the UI and background threads.  Methods with "Locked"
    // in the name expect mCameraStateLock to be held while calling.
    
    /**
     * ID of the current {@link CameraDevice}.
     */
    private String mCameraId;
    
    /**
     * Store which camera to use based on camera location (front/back of device).
     */
    private int mFacing;
    
    /**
     * The current Flash Mode. By defaut we use auto mode.
     */
    private int mFlashMode = FLASH_MODE_AUTO;
    
    private final CameraManager mCameraManager;
    
    /**
     * A {@link CameraCaptureSession } for camera preview.
     */
    private CameraCaptureSession mCaptureSession;
    
    /**
     * A reference to the open {@link CameraDevice}.
     */
    private CameraDevice mCameraDevice;
    
    /**
     * List of sizes available for camera preview.
     */
    private final List<Size> mPreviewSizes = new ArrayList<>();
    
    /**
     * List of sizes available for JPEG capture.
     */
    private final List<Size> mPictureSizes = new ArrayList<>();
    
    /**
     * The {@link Size} of camera preview.
     */
    private Size mPreviewSize;
    
    /**
     * The {@link Size} of captured image.
     */
    private Size mJpegSize;
    
    /**
     * The {@link CameraCharacteristics} for the currently configured camera device.
     */
    private CameraCharacteristics mCharacteristics;
    
    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;
    
    /**
     * {@link ImageReader} that handles JPEG image captures.
     */
    private ImageReader mJpegImageReader;
    
    /**
     * Whether or not the currently configured camera device is fixed-focus.
     */
    private boolean mNoAFRun = false;
    
    private boolean mLegacyDevice = false;
    
    /**
     * Number of pending user requests to capture a photo.
     */
    private int mPendingUserCaptures = 0;
    
    /**
     * Request ID to {@link SingleEmitter} mapping for in-progress JPEG captures.
     */
    private final TreeMap<Integer, SingleEmitter<Pair<byte[], Integer>>> mJpegEmitterQueue = new TreeMap<>();
    
    /**
     * {@link CaptureRequest.Builder} for the camera preview
     */
    private CaptureRequest.Builder mPreviewRequestBuilder;
    
    /**
     * The state of the camera device.
     * @see #mPreCaptureCallback
     */
    private int mState = STATE_CLOSED;
    
    /**
     * Timer to use with pre-capture sequence to ensure a timely capture if 3A convergence is
     * taking too long.
     */
    private long mCaptureTimer;
    
    //**********************************************************************************************
    
    private final SurfaceInfo mSurfaceInfo = new SurfaceInfo();
    private Size mAspectRatio = DEFAULT_ASPECT_RATIO;
    private int mDisplayOrientation;
    private Callback mCallback;
    private Context mContext;
    
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
        = new TextureView.SurfaceTextureListener() {
        
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            mSurfaceInfo.configure(surface, width, height);
            configureTransform();
            
            // notify listener that we computed the preview size only if we know the selected preview size
            if (mPreviewSize != null) {
                notifyPreviewSizesAvailable();
            }
            
            // start preview if session is already available
            if (mState != STATE_CLOSED) {
                createCameraPreviewSessionLocked();
            }
            
        }
        
        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            mSurfaceInfo.configure(surface, width, height);
            configureTransform();
        }
        
        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            synchronized (mCameraStateLock) {
                mPreviewSize = null;
                mSurfaceInfo.configure(null, 0, 0);
            }
            return true;
        }
        
        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
        
    };
    
    
    /**
     * {@link CameraDevice.StateCallback} is called when the currently active {@link CameraDevice}
     * changes its state.
     * This is called on {@link #mBackgroundThread}.
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here if
            // the TextureView displaying this has been set up.
            synchronized (mCameraStateLock) {
                mState = STATE_OPENED;
                mCameraOpenCloseLock.release();
                mCameraDevice = cameraDevice;
                
                // Start the preview session if the TextureView has been set up already.
                if (mPreviewSize != null && mSurfaceInfo.surface != null) {
                    createCameraPreviewSessionLocked();
                }
                
                mCallback.onCameraOpened();
            }
        }
        
        @Override
        public void onClosed(@NonNull CameraDevice cameraDevice) {
            synchronized (mCameraStateLock) {
                mState = STATE_CLOSED;
                mCameraOpenCloseLock.release();
                cameraDevice.close();
                mCameraDevice = null;
                mCallback.onCameraClosed();
            }
        }
        
        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            synchronized (mCameraStateLock) {
                mState = STATE_CLOSED;
                mCameraOpenCloseLock.release();
                cameraDevice.close();
                mCameraDevice = null;
                mCallback.onCameraClosed();
            }
        }
        
        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            Log.e(TAG, "Received camera device error: " + error);
            synchronized (mCameraStateLock) {
                mState = STATE_CLOSED;
                mCameraOpenCloseLock.release();
                cameraDevice.close();
                mCameraDevice = null;
            }
        }
        
    };
    
    /**
     * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
     * JPEG image is ready to be saved.
     */
    private final ImageReader.OnImageAvailableListener mOnJpegImageAvailableListener
        = new ImageReader.OnImageAvailableListener() {
        
        @Override
        public void onImageAvailable(ImageReader reader) {
            synchronized (mCameraStateLock) {
                final Map.Entry<Integer, SingleEmitter<Pair<byte[], Integer>>> entry = mJpegEmitterQueue.firstEntry();
                final SingleEmitter<Pair<byte[], Integer>> emitter = entry.getValue();
                
                if (emitter != null && !emitter.isDisposed()) {
                    if (reader == null) {
                        emitter.onError(new Exception("ImageReader already closed."));
                        mJpegEmitterQueue.remove(entry.getKey());
                        return;
                    }
                    
                    Image image;
                    try {
                        image = reader.acquireNextImage();
                    } catch (IllegalStateException e) {
                        emitter.onError(new Exception("Too many images queued for saving, dropping image for request: " +
                            entry.getKey()));
                        mJpegEmitterQueue.remove(entry.getKey());
                        return;
                    }
                    
                    if (image != null) {
                        Image.Plane[] planes = image.getPlanes();
                        if (planes.length > 0) {
                            ByteBuffer buffer = planes[0].getBuffer();
                            byte[] data = new byte[buffer.remaining()];
                            buffer.get(data);
                            
                            // send image data and camera rotation
                            int cameraRotation = sensorToDeviceRotation(mCharacteristics, mDisplayOrientation);
                            emitter.onSuccess(new Pair<>(data, cameraRotation));
                        }
                        
                        // close the image to free up image reader resources
                        image.close();
                        
                        // remove the request from queue
                        mJpegEmitterQueue.remove(entry.getKey());
                    } else {
                        emitter.onError(new Exception("Error reading image for request " +
                            entry.getKey()));
                        mJpegEmitterQueue.remove(entry.getKey());
                    }
                    
                }
            }
        }
        
    };
    
    
    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events for the preview and
     * pre-capture sequence.
     */
    private CameraCaptureSession.CaptureCallback mPreCaptureCallback
        = new CameraCaptureSession.CaptureCallback() {
        
        private void process(CaptureResult result) {
            synchronized (mCameraStateLock) {
                switch (mState) {
                    case STATE_PREVIEW: {
                        // when in preview mode we don't do anything
                        break;
                    }
                    case STATE_WAITING_FOR_3A_CONVERGENCE: {
                        boolean readyToCapture = true;
                        if (!mNoAFRun) {
                            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                            if (afState == null) {
                                break;
                            }
                            
                            // If auto-focus has reached locked state, we are ready to capture
                            readyToCapture =
                                (afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                                    afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                                    afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED);
                        }
                        
                        // If we are running on an non-legacy device, we should also wait until
                        // auto-exposure and auto-white-balance have converged as well before
                        // taking a picture.
                        if (!isLegacyLocked()) {
                            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                            Integer awbState = result.get(CaptureResult.CONTROL_AWB_STATE);
                            if (aeState == null || awbState == null) {
                                break;
                            }
                            
                            readyToCapture = readyToCapture &&
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED &&
                                awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED;
                        }
                        
                        // If we haven't finished the pre-capture sequence but have hit our maximum
                        // wait timeout, too bad! Begin capture anyway.
                        if (!readyToCapture && hitTimeoutLocked()) {
                            Log.w(TAG, "Timed out waiting for pre-capture sequence to complete.");
                            readyToCapture = true;
                        }
                        
                        if (readyToCapture && mPendingUserCaptures > 0) {
                            // Capture once for each user tap of the "Picture" button.
                            while (mPendingUserCaptures > 0) {
                                // if we have multiple requests, the mPendingUserCaptures will help
                                // us map images to the correct emitters
                                captureStillPictureLocked(mPendingUserCaptures);
                                mPendingUserCaptures--;
                            }
                            // After this, the camera will go back to the normal state of preview.
                            mState = STATE_PREVIEW;
                        }
                    }
                }
            }
        }
        
        @Override
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request,
                                        CaptureResult partialResult) {
            process(partialResult);
        }
        
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                       TotalCaptureResult result) {
            process(result);
        }
        
    };
    
    
    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles the still JPEG and RAW capture
     * request.
     */
    private final CameraCaptureSession.CaptureCallback mCaptureCallback
        = new CameraCaptureSession.CaptureCallback() {
        
        @Override
        public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request,
                                     long timestamp, long frameNumber) {
            mCallback.onCaptureStarted();
        }
        
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                       TotalCaptureResult result) {
            synchronized (mCameraStateLock) {
                // release focus trigger
                finishedCaptureLocked();
            }
        }
        
        @Override
        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request,
                                    CaptureFailure failure) {
            synchronized (mCameraStateLock) {
                // release focus trigger
                finishedCaptureLocked();
                
                // noty emitter of failed captures and remove it from the queue
                final AtomicInteger requestTag = (AtomicInteger) request.getTag();
                final SingleEmitter<Pair<byte[], Integer>> emitter = mJpegEmitterQueue.get(requestTag);
                
                if (emitter != null && !emitter.isDisposed()) {
                    emitter.onError(new Exception("Capture failed with reason : " + failure.toString()));
                }
                
                mJpegEmitterQueue.remove(requestTag);
            }
        }
        
    };
    
    
    public CameraController(@NonNull Context context, @NonNull Callback callback) {
        mContext = context;
        mCallback = callback;
        mCameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }
    
    /**
     * Set the display orientation and
     * @param displayOrientation
     */
    public void setDisplayOrientation(int displayOrientation) {
        mDisplayOrientation = displayOrientation;
        configureTransform();
    }
    
    /**
     * Get the {@link TextureView.SurfaceTextureListener}  that needs to be added
     * to the {@link SurfaceTexture} that shows camera preview frames.
     */
    public TextureView.SurfaceTextureListener getSurfaceTextureListener() {
        return mSurfaceTextureListener;
    }
    
    /**
     * Configure camera dependencies and start a preview session.
     */
    public void startCamera() {
        startBackgroundThreads();
        
        synchronized (mCameraStateLock) {
            chooseCameraIdByFacing();
            collectCameraInfo();
            chooseImageSizes();
            
            // notify listener that we computed the preview size only if we have a surface available
            if (mSurfaceInfo.width > 0) {
                notifyPreviewSizesAvailable();
            }
            
            // prepare image reader
            prepareImageReaders();
            
            // open camera
            openCamera();
        }
    }
    
    /**
     * Stop the camera session and release all associated resources.
     */
    public void stopCamera() {
        closeCamera();
        stopBackgroundThreads();
    }
    
    /**
     * Initiate a still image capture.
     * <p/>
     * This function sends a capture request that initiates a pre-capture sequence in our state
     * machine that waits for auto-focus to finish, ending in a "locked" state where the lens is no
     * longer moving, waits for auto-exposure to choose a good exposure value, and waits for
     * auto-white-balance to converge.
     */
    public Single<Pair<byte[], Integer>> takePicture() {
        
        return Single.create(new SingleOnSubscribe<Pair<byte[], Integer>>() {
            
            @Override
            public void subscribe(SingleEmitter<Pair<byte[], Integer>> emitter) throws Exception {
                synchronized (mCameraStateLock) {
                    mPendingUserCaptures++;
                    
                    // If we already triggered a pre-capture sequence, or are in a state where we cannot
                    // do this, return immediately.
                    if (mState == STATE_WAITING_FOR_3A_CONVERGENCE) {
                        // the pre-capture is already started; image can be scheduled for capture
                        final int requestTag = mRequestCounter.getAndIncrement();
                        mJpegEmitterQueue.put(requestTag, emitter);
                    } else if (mState != STATE_PREVIEW) {
                        emitter.onError(new Exception(
                            String.format("Camera state %d can't trigger image capture", mState)));
                    }
                    
                    try {
                        // Trigger an auto-focus run if camera is capable. If the camera is already focused,
                        // this should do nothing.
                        if (!mNoAFRun) {
                            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                CameraMetadata.CONTROL_AF_TRIGGER_START);
                        }
                        
                        // If this is not a legacy device, we can also trigger an auto-exposure metering
                        // run.
                        if (!isLegacyLocked()) {
                            // Tell the camera to lock focus.
                            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                                CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                        }
                        
                        // Update state machine to wait for auto-focus, auto-exposure, and
                        // auto-white-balance (aka. "3A") to converge.
                        mState = STATE_WAITING_FOR_3A_CONVERGENCE;
                        
                        // Start a timer for the pre-capture sequence.
                        startTimerLocked();
                        
                        // Replace the existing repeating request with one with updated 3A triggers.
                        mCaptureSession.capture(mPreviewRequestBuilder.build(), mPreCaptureCallback,
                            mBackgroundHandler);
                        
                        // we use the request tag to save a reference to the emitter
                        // this tag will also be added to the capture request triggered after camera
                        // is ready to take a picture
                        final int requestTag = mRequestCounter.getAndIncrement();
                        mJpegEmitterQueue.put(requestTag, emitter);
                        
                    } catch (CameraAccessException e) {
                        emitter.onError(e);
                    }
                }
            }
        });
    }
    
    /**
     * Check if the camera is opened.
     */
    public boolean isCameraOpened() {
        return mCameraDevice != null;
    }
    
    /**
     * Set the orientation based on which we choose the camera. See {@link #INTERNAL_FACINGS }.
     * This should be called before starting the camera !
     */
    public void setFacing(int facing) {
        synchronized (mCameraStateLock) {
            if (mFacing == facing) {
                return;
            }
            mFacing = facing;
        }
    }
    
    /**
     * Set the desired flash mode to be used when taking a picture.
     * <p>
     * <p> THIS HAS NO EFFECT ON THE PREVIEW!</p>
     * @param flash
     */
    public void setFlash(@InternalFlashMode int flash) {
        if (mFlashMode != flash) {
            synchronized (mCameraStateLock) {
                mFlashMode = flash;
                if (mCaptureSession != null) {
                    try {
                        updateFlashModeLocked();
//                        mCaptureSession.setRepeatingRequest(
//                                mPreviewRequestBuilder.build(),
//                                mPreCaptureCallback, mBackgroundHandler);
                    } catch (Exception e) {
                        mFlashMode = FLASH_MODE_AUTO;
                        updateFlashModeLocked();
                    }
                }
            }
        }
    }
    
    /**
     * Get the current Flash mode
     * @return one of
     * {@link #FLASH_MODE_AUTO,#FLASH_MODE_OFF,#FLASH_MODE_ON,#FLASH_MODE_RED_EYE,#FLASH_MODE_TORCH}
     */
    @InternalFlashMode
    public int getFlash() {
        return mFlashMode;
    }
    
    /**
     * Get the orientation based on which we choose the camera. See {@link #INTERNAL_FACINGS }.
     */
    public int getFacing() {
        return mFacing;
    }
    
    /**
     * Get the aspect ratio used for the camera preview.
     * @return
     */
    public Size getSurfaceAspectRatio() {
        return mAspectRatio;
    }
    
    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThreads() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        
        synchronized (mCameraStateLock) {
            mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        }
    }
    
    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThreads() {
        if (null != mBackgroundThread) {
            mBackgroundThread.quitSafely();
        }
        
        try {
            if (null != mBackgroundThread) {
                mBackgroundThread.join();
                mBackgroundThread = null;
                mBackgroundHandler = null;
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Chooses a camera ID by the specified camera facing ({@link #mFacing}).
     * <p>
     * <p>This rewrites {@link #mCameraId}, {@link #mCharacteristics}, and optionally
     * {@link #mFacing}.</p>
     */
    private void chooseCameraIdByFacing() {
        try {
            int internalFacing = INTERNAL_FACINGS.get(mFacing);
            final String[] ids = mCameraManager.getCameraIdList();
            
            for (String id : ids) {
                CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(id);
                Integer internal = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (internal == null) {
                    throw new NullPointerException("Unexpected state: LENS_FACING null");
                }
                if (internal == internalFacing) {
                    mCameraId = id;
                    mCharacteristics = characteristics;
                    return;
                }
            }
            // Not found
            mCameraId = ids[0];
            mCharacteristics = mCameraManager.getCameraCharacteristics(mCameraId);
            Integer internal = mCharacteristics.get(CameraCharacteristics.LENS_FACING);
            if (internal == null) {
                throw new NullPointerException("Unexpected state: LENS_FACING null");
            }
            for (int i = 0, count = INTERNAL_FACINGS.size(); i < count; i++) {
                if (INTERNAL_FACINGS.valueAt(i) == internal) {
                    mFacing = INTERNAL_FACINGS.keyAt(i);
                    return;
                }
            }
            // The operation can reach here when the only camera device is an external one.
            // We treat it as facing back.
            mFacing = CameraParams.FACING_BACK;
        } catch (CameraAccessException e) {
            throw new RuntimeException("Failed to get a list of camera devices", e);
        }
    }
    
    /**
     * Collects some information from {@link #mCharacteristics}.
     * <p>
     * <p>This rewrites {@link #mPreviewSizes} and {@link #mPictureSizes}.</p>
     */
    private void collectCameraInfo() {
        StreamConfigurationMap map = mCharacteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            throw new IllegalStateException("Failed to get configuration map: " + mCameraId);
        }
        
        // get preview sizes
        mPreviewSizes.clear();
        final Size[] previewOutputSizes = map.getOutputSizes(SurfaceTexture.class);
        if (previewOutputSizes != null) {
            mPreviewSizes.addAll(Arrays.asList(previewOutputSizes));
            Collections.sort(mPreviewSizes, new CompareSizesByArea());
        }
        
        // get picture sizes
        mPictureSizes.clear();
        // try to get hi-res output sizes for Marshmallow and higher
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            final Size[] outputSizes = map.getHighResolutionOutputSizes(ImageFormat.JPEG);
            if (outputSizes != null) {
                mPictureSizes.addAll(Arrays.asList(outputSizes));
                Collections.sort(mPictureSizes, new CompareSizesByArea());
            }
        }
        // fallback camera sizes and lower than Marshmallow
        if (mPictureSizes.size() == 0) {
            final Size[] outputSizes = map.getOutputSizes(ImageFormat.JPEG);
            if (outputSizes != null) {
                mPictureSizes.addAll(Arrays.asList(outputSizes));
                Collections.sort(mPictureSizes, new CompareSizesByArea());
            }
        }
        
        //determine if device has Legacy support
        mLegacyDevice = mCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ==
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY;
        
        //  determine if we can run Auto-Focus
        Float minFocusDist =
            mCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        
        // If MINIMUM_FOCUS_DISTANCE is 0, lens is fixed-focus and we need to skip the AF run.
        mNoAFRun = (minFocusDist == null || minFocusDist == 0);
    }
    
    /**
     * Select preview and capture image size based on aspect ratio.
     */
    private void chooseImageSizes() {
        // FIXME: 20/09/2017 limit picture size to 1080dp because processing high res images crashes the app
//        mJpegSize = new Size(1920, 1080);
        mJpegSize = Collections.max(mPictureSizes, new CompareSizesByArea());
        mPreviewSize = chooseOptimalSize();
        
        // check if the surface was prepared before preview size was determined
        // this can happen when we open the camera for the first time and need to request permissions
        if (mSurfaceInfo.surface != null) {
            // we need to configure the surface transform & notify preview size listeners
            configureTransform();
            notifyPreviewSizesAvailable();
        }
    }
    
    /**
     * Notify listeners when the preview size has been computed. The preview size will be
     * adjusted to match the {@code mDisplayOrientation} before being sent to the listeners.
     */
    private void notifyPreviewSizesAvailable() {
        
        // if device is portrait we need to swap width & height from the
        // chosen preview size
        if (mDisplayOrientation % 180 == 0) {
            final Size adjustedPreviewSize;
            final int width = mPreviewSize.getWidth();
            final int height = mPreviewSize.getHeight();
            if (width > height) {
                adjustedPreviewSize = new Size(height, width);
            } else {
                // preview size is already adjusted to portrait orientation
                adjustedPreviewSize = mPreviewSize;
            }
            
            mCallback.onSizesAvailable(adjustedPreviewSize,
                new Size(mSurfaceInfo.width, mSurfaceInfo.height));
            
        } else {
            mCallback.onSizesAvailable(mPreviewSize,
                new Size(mSurfaceInfo.width, mSurfaceInfo.height));
        }
    }
    
    /**
     * Create {@link ImageReader}s for image preview and capture requests.
     */
    private void prepareImageReaders() {
        mJpegImageReader = ImageReader.newInstance(mJpegSize.getWidth(), mJpegSize.getHeight(),
            ImageFormat.JPEG, 5);
        mJpegImageReader.setOnImageAvailableListener(mOnJpegImageAvailableListener, mBackgroundHandler);
    }
    
    /**
     * Opens the camera specified by {@link #mCameraId}.
     */
    @SuppressWarnings("MissingPermission")
    private void openCamera() {
        try {
            // Wait for any previously running session to finish.
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            
            String cameraId;
            Handler backgroundHandler;
            synchronized (mCameraStateLock) {
                cameraId = mCameraId;
                backgroundHandler = mBackgroundHandler;
            }
            
            // Attempt to open the camera. mStateCallback will be called on the background handler's
            // thread when this succeeds or fails.
            mCameraManager.openCamera(cameraId, mStateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }
    
    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     * <p/>
     * Call this only with {@link #mCameraStateLock} held.
     */
    private void createCameraPreviewSessionLocked() {
        try {
            // We configure the size of default buffer to be the size of camera preview we want.
            mSurfaceInfo.surface.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            
            // This is the output Surface that is drawn on screen.
            Surface surface = new Surface(mSurfaceInfo.surface);
            
            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder = mCameraDevice
                .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);
            
            // Here, we create a CameraCaptureSession for camera preview.
            // this is called on mBackgroundThread so we don't need to set it in the callback
            mCameraDevice.createCaptureSession(Arrays.asList(surface,
                mJpegImageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                        startPreview(cameraCaptureSession);
                    }
                    
                    @Override
                    public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                        Log.e(TAG, "Failed to configure capture session.");
                    }
                    
                    @Override
                    public void onClosed(@NonNull CameraCaptureSession session) {
                        if (mCaptureSession != null && mCaptureSession.equals(session)) {
                            mCaptureSession = null;
                        }
                    }
                }, null  // this is already called from the background thread
            
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    
    private void startPreview(@NonNull final CameraCaptureSession cameraCaptureSession) {
        synchronized (mCameraStateLock) {
            // The camera is already closed
            if (null == mCameraDevice) {
                return;
            }
            
            try {
                setup3AControlsLocked();
                
                // Finally, we start displaying the camera preview.
                // This needs to run on the main thread to actually see preview frames
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    
                    @Override
                    public void run() {
                        try {
                            cameraCaptureSession.setRepeatingRequest(
                                mPreviewRequestBuilder.build(),
                                mPreCaptureCallback, mBackgroundHandler);
                            mState = STATE_PREVIEW;
                        } catch (CameraAccessException | IllegalStateException e) {
                            Log.e(TAG, "Failed to start camera preview.", e);
                        }
                    }
                });
                
                
            } catch (IllegalStateException e) {
                e.printStackTrace();
                return;
            }
            // When the session is ready, we start displaying the preview.
            mCaptureSession = cameraCaptureSession;
        }
    }
    
    /**
     * Configures the transform matrix for TextureView based on {@link #mDisplayOrientation} and
     * {@link #mSurfaceInfo}.
     */
    private void configureTransform() {
        if (mPreviewSize != null) {
            Matrix matrix = new Matrix();
            
            if (mDisplayOrientation % 180 == 90) {
                // Rotate the camera preview when the screen is landscape.
                final float[] surfaceCoordinates = {
                    0.f, 0.f, // top left
                    mSurfaceInfo.width, 0.f, // top right
                    0.f, mSurfaceInfo.height, // bottom left
                    mSurfaceInfo.width, mSurfaceInfo.height, // bottom right
                };
                
                final float[] targetCoordinates;
                if (mDisplayOrientation == 90) { // Clockwise
                    targetCoordinates =
                        new float[] {
                            0.f, mSurfaceInfo.height, // top left
                            0.f, 0.f, // top right
                            mSurfaceInfo.width, mSurfaceInfo.height, // bottom left
                            mSurfaceInfo.width, 0.f, // bottom right
                        };
                } else {
                    // Counter-clockwise
                    targetCoordinates = new float[] {
                        mSurfaceInfo.width, 0.f, // top left
                        mSurfaceInfo.width, mSurfaceInfo.height, // top right
                        0.f, 0.f, // bottom left
                        0.f, mSurfaceInfo.height, // bottom right
                    };
                }

             /* Set the matrix such that the specified src points would map to
               the specified dst points.
               The "points" are represented as an array of floats,
               order [x0, y0, x1, y1, ...], where each "point" is 2 float values.
              */
                matrix.setPolyToPoly(surfaceCoordinates, 0, targetCoordinates, 0, 4);
            }
            mCallback.onTransformUpdated(matrix);
        }
    }
    
    /**
     * Check if we are using a device that only supports the LEGACY hardware level.
     * <p/>
     * Call this only with {@link #mCameraStateLock} held.
     * @return true if this is a legacy device.
     */
    private boolean isLegacyLocked() {
        return mLegacyDevice;
    }
    
    /**
     * Start the timer for the pre-capture sequence.
     * <p/>
     * Call this only with {@link #mCameraStateLock} held.
     */
    private void startTimerLocked() {
        mCaptureTimer = SystemClock.elapsedRealtime();
    }
    
    /**
     * Check if the timer for the pre-capture sequence has been hit.
     * <p/>
     * Call this only with {@link #mCameraStateLock} held.
     * @return true if the timeout occurred.
     */
    private boolean hitTimeoutLocked() {
        return (SystemClock.elapsedRealtime() - mCaptureTimer) > PRECAPTURE_TIMEOUT_MS;
    }
    
    /**
     * Given a list of {@code Size}s supported by a camera({@code mPreviewSizes}), choose the smallest one that
     * is at least as large as the respective texture view size, and whose aspect ratio matches with
     * {@code mAspectRatio}. If such size  doesn't exist, choose the largest one.
     * @return The optimal {@code Size}, or the largest one if none were big enough
     */
    private Size chooseOptimalSize() {
        final float ratioFLoat = mAspectRatio.getWidth() * 1.0f / mAspectRatio.getHeight();
        
        for (Size size : mPreviewSizes) {
            // if we have a matching aspect ratio
            final float ratio = size.getWidth() * 1.0f / size.getHeight();
            if (ratio == ratioFLoat) {
                if (size.getWidth() >= mAspectRatio.getWidth() &&
                    size.getHeight() >= mAspectRatio.getHeight()) {
                    return size;
                }
            }
        }
        
        // if we didn't find any suitable size, return the largest one
        return mPreviewSizes.get(mPreviewSizes.size() - 1);
    }
    
    /**
     * Configure the  {@link #mPreviewRequestBuilder} to use auto-focus, auto-exposure, and
     * auto-white-balance controls if available.
     * <p/>
     * Call this only with {@link #mCameraStateLock} held.
     */
    private void setup3AControlsLocked() {
        // Enable auto-magical 3A run by camera device
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_MODE,
            CaptureRequest.CONTROL_MODE_AUTO);
        
        // update flash mode (auto-exposure mode)
        updateAutoFocusLocked();
        
        // update flash mode (auto-exposure mode)
        updateFlashModeLocked();
        
        
        // If there is an auto-magical white balance control mode available, use it.
        updateWhiteBalanceLocked();
    }
    
    /**
     * Configure the {@link #mPreviewRequestBuilder} to use the user preferred flash mode. Default
     * value is {@link #FLASH_MODE_AUTO}.
     * <p> The auto-exposure can control flash mode.
     * Call this only with {@link #mCameraStateLock} held.
     */
    private void updateFlashModeLocked() {
        // auto-exposure is enabled but does not control the flash
        int aeMode = CaptureRequest.CONTROL_AE_MODE_ON;
        int flashMode = CaptureRequest.FLASH_MODE_OFF;
        switch (mFlashMode) {
            case CameraParams.FLASH_OFF:
                // auto-exposure is enabled but does not control the flash
                aeMode = CaptureRequest.CONTROL_AE_MODE_ON;
                
                flashMode = CaptureRequest.FLASH_MODE_OFF;
                break;
            case CameraParams.FLASH_ON:
                // ae can be on but it must not control the flash
                aeMode = CaptureRequest.CONTROL_AE_MODE_ON;
                
                // force use the flash
                flashMode = CaptureRequest.FLASH_MODE_SINGLE;
                break;
            case CameraParams.FLASH_TORCH:
                // should use camera device to handle torch state (available from api >= 23)
            case CameraParams.FLASH_AUTO:
                // ae is on and will trigger flash when capturing in low light conditions
                aeMode = CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH;
                
                flashMode = CaptureRequest.FLASH_MODE_OFF;
                break;
            case CameraParams.FLASH_RED_EYE:
                // like CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH and,if necessary, will trigger a red eye reduction flash during preCapture
                aeMode = CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE;
                
                flashMode = CaptureRequest.FLASH_MODE_OFF;
                break;
        }
        
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, aeMode);
        mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE, mFlashMode);
    }
    
    /**
     * Configure the {@link #mPreviewRequestBuilder} to use auto white-balance if the camera
     * supports it.
     * <p>
     * Call this only with {@link #mCameraStateLock} held.
     */
    private void updateWhiteBalanceLocked() {
        if (contains(mCharacteristics.get(
            CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),
            CaptureRequest.CONTROL_AWB_MODE_AUTO)) {
            // Allow AWB to run auto-magically if this device supports this
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO);
        }
    }
    
    /**
     * Configure the {@link #mPreviewRequestBuilder} to use auto focus.
     * <p>
     * Call this only with {@link #mCameraStateLock} held.
     */
    private void updateAutoFocusLocked() {
        if (!mNoAFRun) {
            // If there is a "continuous picture" mode available, use it, otherwise default to AUTO.
            if (contains(mCharacteristics.get(
                CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            } else {
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_AUTO);
            }
        }
    }
    
    /**
     * Return true if the given array contains the given integer.
     * @param modes array to check.
     * @param mode integer to get for.
     * @return true if the array contains the given integer, otherwise false.
     */
    private boolean contains(int[] modes, int mode) {
        if (modes == null) {
            return false;
        }
        for (int i : modes) {
            if (i == mode) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Closes the current {@link CameraDevice}.
     */
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            synchronized (mCameraStateLock) {
                
                // abort all captions
                try {
                    mCaptureSession.abortCaptures();
                } catch (Exception e) {
                    Log.w(TAG, e.getMessage());
                }
                
                // Reset state and clean up resources used by the camera.
                // Note: After calling this, the ImageReaders will be closed after any background
                // tasks saving Images from these readers have been completed.
                mPendingUserCaptures = 0;
                mState = STATE_CLOSED;
                if (null != mCaptureSession) {
                    mCaptureSession.close();
                    mCaptureSession = null;
                }
                if (null != mCameraDevice) {
                    mCameraDevice.close();
                    mCameraDevice = null;
                }
                if (null != mJpegImageReader) {
                    mJpegImageReader.close();
                    mJpegImageReader = null;
                }
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }
    
    /**
     * Send a capture request to the camera device that initiates a capture targeting the JPEG output.
     * <p> {@code currentCaptureNumber} is used to match the request to the proper emitter by
     * subtracting this number from {@code # mRequestCounter}.</p>
     * <p/>
     * Call this only with {@link #mCameraStateLock} held.
     * @param currentCaptureNumber the number of the current capture
     */
    private void captureStillPictureLocked(int currentCaptureNumber) {
        final int requestTag = mRequestCounter.get() - currentCaptureNumber;
        final SingleEmitter emitter = mJpegEmitterQueue.get(requestTag);
        
        // only proceed if the emiter still exists and is still subscribed
        if (emitter != null && !emitter.isDisposed()) {
            try {
                if (null == mCameraDevice) {
                    // notify emitter that we don't have a camera device anymore
                    emitter.onError(new Exception("Camera device is no longer available"));
                    return;
                }
                
                // This is the CaptureRequest.Builder that we use to take a picture.
                final CaptureRequest.Builder captureBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                
                captureBuilder.addTarget(mJpegImageReader.getSurface());
                
                // Use the same AE and AF modes as the preview.
                captureBuilder.set(CaptureRequest.CONTROL_MODE,
                    mPreviewRequestBuilder.get(CaptureRequest.CONTROL_MODE));
                
                captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AF_MODE));
                
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AE_MODE));
                
                final Integer flashMode = mPreviewRequestBuilder.get(CaptureRequest.FLASH_MODE);
                captureBuilder.set(CaptureRequest.FLASH_MODE, flashMode);
                
                // if we force the flash we need to update auto-exposure  precapture trigger
                if (flashMode == CaptureRequest.FLASH_MODE_SINGLE) {
                    captureBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                }
                
                captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE,
                    mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AWB_MODE));
                
                // fixme this may not work on SAMSUNG devices
                // Set orientation.
//                captureBuilder.set(CaptureRequest.JPEG_ORIENTATION,
//                        sensorToDeviceRotation(mCharacteristics, mDisplayOrientation));
                
                // Set request tag to easily track results in callbacks.
                captureBuilder.setTag(requestTag);
                
                CaptureRequest request = captureBuilder.build();
                
                mCaptureSession.capture(request, mCaptureCallback, mBackgroundHandler);
                
            } catch (CameraAccessException e) {
                emitter.onError(e);
            }
        }
    }
    
    
    /**
     * Rotation need to transform from the camera sensor orientation to the device's current
     * orientation.
     * @param c the {@link CameraCharacteristics} to query for the camera sensor
     * orientation.
     * @param deviceOrientation the current device orientation relative to the native device
     * orientation.
     * @return the total rotation from the sensor orientation to the current device orientation.
     */
    private static int sensorToDeviceRotation(CameraCharacteristics c, int deviceOrientation) {
        int sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        
        // Get device orientation in degrees
        deviceOrientation = ORIENTATIONS.get(deviceOrientation);
        
        // Reverse device orientation for front-facing cameras
        if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
            deviceOrientation = -deviceOrientation;
        }
        
        // Calculate desired JPEG orientation relative to camera orientation to make
        // the image upright relative to the device orientation
        return (sensorOrientation + deviceOrientation + 360) % 360;
    }
    
    /**
     * Called after a RAW/JPEG capture has completed; resets the AF trigger state for the
     * pre-capture sequence.
     * <p/>
     * Call this only with {@link #mCameraStateLock} held.
     */
    private void finishedCaptureLocked() {
        try {
            // Reset the auto-focus trigger in case AF didn't run quickly enough.
            if (!mNoAFRun) {
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                
                mCaptureSession.capture(mPreviewRequestBuilder.build(), mPreCaptureCallback,
                    mBackgroundHandler);
                
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            }
            
            // reset AE trigger
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    
    public interface Callback {
        
        void onCameraOpened();
        
        void onCameraClosed();
        
        /**
         * Called when image capture has started.
         */
        void onCaptureStarted();
        
        void onSizesAvailable(Size previewSize, Size surfaceSize);
        
        void onTransformUpdated(Matrix matrix);
    }
    
    /**
     * Comparator based on area of the given {@link Size} objects.
     */
    class CompareSizesByArea implements Comparator<Size> {
        
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                (long) rhs.getWidth() * rhs.getHeight());
        }
        
    }
}
