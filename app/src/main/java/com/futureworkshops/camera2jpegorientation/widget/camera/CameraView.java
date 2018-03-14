package com.futureworkshops.camera2jpegorientation.widget.camera;

import android.content.Context;
import android.graphics.Matrix;
import android.media.AudioManager;
import android.media.MediaActionSound;
import android.support.annotation.AttrRes;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.Size;
import android.view.TextureView;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.FrameLayout;

import com.futureworkshops.camera2jpegorientation.R;


import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import io.reactivex.Single;


/**
 * Created by stelian on 28/04/2017.
 */

public class CameraView extends FrameLayout {

    private static final String SAMSUNG_MANUFCATURER_NAME = "samsung";

    @IntDef({
            CameraParams.FLASH_OFF,
            CameraParams.FLASH_ON,
            CameraParams.FLASH_AUTO,
            CameraParams.FLASH_RED_EYE,
            CameraParams.FLASH_TORCH
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FlashMode {
    }

    /**
     * Direction the camera faces relative to device screen.
     */
    @IntDef({CameraParams.FACING_BACK, CameraParams.FACING_FRONT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Facing {
    }

    private static final int DEFAULT_SHUTTER_ANIM_DURATION = 100;
    private TextureView mTextureView;
    private final CameraController mCameraController;
    /**
     * Used for playing sounds when different camera operations take place.
     */
    private MediaActionSound mMediaActionSound;
    private AlphaAnimation mCaptureAnimation;

    private final DisplayOrientationDetector mDisplayOrientationDetector;
    private boolean mAdjustViewBounds;
    private boolean mRequestLayoutOnOpen;
    private CameraStateCallback mCameraStateCallback;
    private PreviewSizeListener mPreviewSizeListener;

    private CameraController.Callback mCameraControllerCallback = new CameraController.Callback() {
        @Override
        public void onCameraOpened() {
            if (mRequestLayoutOnOpen) {
                mRequestLayoutOnOpen = false;
                requestLayout();
            }

            if (mCameraStateCallback != null) {
                mCameraStateCallback.onCameraOpened(CameraView.this);
            }
        }

        @Override
        public void onCameraClosed() {
            if (mCameraStateCallback != null) {
                mCameraStateCallback.onCameraClosed(CameraView.this);
            }
        }

        /**
         * Called when image capture has started. USed internally to play a capture sound
         * and a simple animation.
         */
        @Override
        public void onCaptureStarted() {
            playShutterSound();

            mTextureView.post(new Runnable() {
                @Override
                public void run() {
                    mTextureView.startAnimation(mCaptureAnimation);
                }
            });
        }

        @Override
        public void onSizesAvailable(Size previewSize, Size surfaceSize) {
            if (mPreviewSizeListener != null) {
                Size preview = new Size(previewSize.getWidth(), previewSize.getHeight());
                Size surface = new Size(surfaceSize.getWidth(), surfaceSize.getHeight());
                mPreviewSizeListener.onPreviewSizesCalculated(preview, surface);
            }
        }

        @Override
        public void onTransformUpdated(Matrix matrix) {
            mTextureView.setTransform(matrix);
        }
    };

    public CameraView(@NonNull Context context) {
        this(context, null);
    }

    public CameraView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CameraView(@NonNull Context context, @Nullable AttributeSet attrs, @AttrRes int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        // don't bother initialising components when View is used in designer
        if (isInEditMode()) {
            mCameraController = null;
            mDisplayOrientationDetector = null;
            return;
        }

        // internal setup
        mCameraController = new CameraController(context, mCameraControllerCallback);

        // inflate layout
        inflate(context, R.layout.csu_camera_view, this);
        mTextureView = (TextureView) findViewById(R.id.csu_cv_texture_view);
        mTextureView.setSurfaceTextureListener(mCameraController.getSurfaceTextureListener());

        // create MediaActionSound instance and preload the sound to reduce latency
        initMediaSound();

        mCaptureAnimation = new AlphaAnimation(1f, 0f);
        mCaptureAnimation.setDuration(DEFAULT_SHUTTER_ANIM_DURATION);
        mCaptureAnimation.setRepeatCount(1);
        mCaptureAnimation.setRepeatMode(Animation.REVERSE);

        // Display orientation detector
        mDisplayOrientationDetector = new DisplayOrientationDetector(context) {
            @Override
            public void onDisplayOrientationChanged(int displayOrientation) {
                mCameraController.setDisplayOrientation(displayOrientation);
            }
        };
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!isInEditMode()) {
            mDisplayOrientationDetector.enable(getDisplay());
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (!isInEditMode()) {
            mDisplayOrientationDetector.disable();
        }
        super.onDetachedFromWindow();
    }

    public void setCameraStateCallback(CameraStateCallback cameraStateCallback) {
        mCameraStateCallback = cameraStateCallback;
    }

    public void setPreviewSizeListener(PreviewSizeListener previewSizeListener) {
        mPreviewSizeListener = previewSizeListener;
    }

    /**
     * @param adjustViewBounds {@code true} if you want the CameraView to adjust its bounds to
     *                         preserve the aspect ratio of camera.
     * @see #getAdjustViewBounds()
     */
    public void setAdjustViewBounds(boolean adjustViewBounds) {
        if (mAdjustViewBounds != adjustViewBounds) {
            mAdjustViewBounds = adjustViewBounds;
            requestLayout();
        }
    }

    /**
     * @return True when this CameraView is adjusting its bounds to preserve the aspect ratio of
     * camera.
     * @see #setAdjustViewBounds(boolean)
     */
    public boolean getAdjustViewBounds() {
        return mAdjustViewBounds;
    }

    /**
     * Chooses camera by the direction it faces.
     *
     * @param facing The camera facing. Must be either {@link CameraParams#FACING_BACK} or
     *               {@link CameraParams#FACING_FRONT}.
     */
    public void setFacing(@Facing int facing) {
        mCameraController.setFacing(facing);
    }

    /**
     * Gets the direction that the current camera faces.
     */
    @Facing
    public int getFacing() {
        //noinspection WrongConstant
        return mCameraController.getFacing();
    }
    
    /**
     * Sets the flash mode.
     *
     * @param flash The desired flash mode.
     */
    public void setFlash(@FlashMode int flash) {
        mCameraController.setFlash(flash);
    }

    /**
     * Gets the current flash mode.
     *
     * @return The current flash mode.
     */
    @FlashMode
    public int getFlash() {
        final int intrnalFlash = mCameraController.getFlash();
        int flash;

        switch (intrnalFlash) {
            case CameraController.FLASH_MODE_AUTO:
                flash = CameraParams.FLASH_AUTO;
                break;
            case CameraController.FLASH_MODE_OFF:
                flash = CameraParams.FLASH_OFF;
                break;
            case CameraController.FLASH_MODE_ON:
                flash = CameraParams.FLASH_ON;
                break;
            case CameraController.FLASH_MODE_RED_EYE:
                flash = CameraParams.FLASH_RED_EYE;
                break;
            case CameraController.FLASH_MODE_TORCH:
                flash = CameraParams.FLASH_TORCH;
                break;
            default:
                flash = CameraParams.FLASH_AUTO;
        }

        return flash;
    }

    /**
     * Open a camera device and start showing camera preview. This is typically called from
     * {@link android.app.Activity#onResume()}.
     */
    public void start() throws SecurityException {
        mCameraController.startCamera();

        // media sound is released every time we call stop() so make sure to init when we start
        if (mMediaActionSound == null) {
            initMediaSound();
        }
    }

    /**
     * Stop camera preview and close the device. This is typically called from
     * {@link android.app.Activity#onPause()}.
     */
    public void stop() {
        mCameraController.stopCamera();
        if (null != mMediaActionSound) {
            mMediaActionSound.release();
            mMediaActionSound = null;
        }
    }

    /**
     * @return {@code true} if the camera is opened.
     */
    public boolean isCameraOpened() {
        return mCameraController.isCameraOpened();
    }

    /**
     * Capture a still image trying to use auto-focus,
     * auto-white-balance and auto-exposure(if not changed by user).
     * <p/>
     * The capture process is already running in a background thread!
     *
     * @return a {@link Pair} containing the image data ({@code byte[]}) and the rotation that
     * needs to be applied to the image in roder to match the current device rotation.
     */
    public Single<Pair<byte[], Integer>> takePicture() {
        return mCameraController.takePicture();
    }
    

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Handle android:adjustViewBounds
        if (mAdjustViewBounds) {
            if (!isCameraOpened()) {
                mRequestLayoutOnOpen = true;
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                return;
            }

            final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
            final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
            final Size ratio = getAspectRatio();
            assert ratio != null;
            float ratioFloat = ratio.getWidth() / ratio.getHeight();

            if (widthMode == MeasureSpec.EXACTLY && heightMode != MeasureSpec.EXACTLY) {
                int height = (int) (MeasureSpec.getSize(widthMeasureSpec) * ratioFloat);
                if (heightMode == MeasureSpec.AT_MOST) {
                    height = Math.min(height, MeasureSpec.getSize(heightMeasureSpec));
                }
                super.onMeasure(widthMeasureSpec,
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            } else if (widthMode != MeasureSpec.EXACTLY && heightMode == MeasureSpec.EXACTLY) {
                int width = (int) (MeasureSpec.getSize(heightMeasureSpec) * ratioFloat);
                if (widthMode == MeasureSpec.AT_MOST) {
                    width = Math.min(width, MeasureSpec.getSize(widthMeasureSpec));
                }
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        heightMeasureSpec);
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        // Measure the TextureView
        if (!isInEditMode()) {
            int width = getMeasuredWidth();
            int height = getMeasuredHeight();
            Size ratio = getAspectRatio();

            if (mDisplayOrientationDetector.getLastKnownDisplayOrientation() % 180 == 0) {
                ratio = new Size(ratio.getHeight(), ratio.getWidth());
            }
            assert ratio != null;
            if (height < width * ratio.getHeight() / ratio.getWidth()) {
                mTextureView.measure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(width * ratio.getHeight() / ratio.getWidth(),
                                MeasureSpec.EXACTLY));
            } else {
                mTextureView.measure(
                        MeasureSpec.makeMeasureSpec(height * ratio.getWidth() / ratio.getHeight(),
                                MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
        }
    }

    private Size getAspectRatio() {
        return mCameraController.getSurfaceAspectRatio();
    }

    private void initMediaSound() {
        mMediaActionSound = new MediaActionSound();
        mMediaActionSound.load(MediaActionSound.SHUTTER_CLICK);
    }

    /**
     * Play shutter sound when auto-capture is triggered or user captures picture manually.
     */
    private void playShutterSound() {
        final String manufacturer = android.os.Build.MANUFACTURER;

        if (manufacturer.toLowerCase().contains(SAMSUNG_MANUFCATURER_NAME)) {
            playSamsungShutterSound();
        } else {
            if (mMediaActionSound != null) {
                mMediaActionSound.play(MediaActionSound.SHUTTER_CLICK);
            }
        }
    }

    /**
     * Manually check sound settings on Samsung devices before playing the shutter sound.
     */
    private void playSamsungShutterSound() {
        // Because Samsung doesn't honour MediaActionSound settings we need to handle it manually
        AudioManager audio = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (audio.getRingerMode() == AudioManager.RINGER_MODE_NORMAL) {
            if (mMediaActionSound != null) {
                mMediaActionSound.play(MediaActionSound.SHUTTER_CLICK);
            }
        }
    }

    /**
     * Interface used to notify listeners when the {@link android.hardware.camera2.CameraDevice}
     * has been opened or closed.
     */
    public interface CameraStateCallback {
        /**
         * Called when camera is opened.
         *
         * @param cameraView The associated {@link CameraView}.
         */
        void onCameraOpened(CameraView cameraView);

        /**
         * Called when camera is closed.
         *
         * @param cameraView The associated {@link CameraView}.
         */
        void onCameraClosed(CameraView cameraView);
    }

    /**
     * Interface used to notify listeners when the preview size has been determined.
     */
    public interface PreviewSizeListener {
        /**
         * Called when preview sizes have been calculated.
         * <p> {@code cameraPreviewSize} may be different from {@code surfacePreviewSize}
         * but the Camera will handle scaling automatically.</p>
         * <p> <strong>The camera preview size will match device orientation!</strong>
         * This means that for a device in portrait mode the preview size width and height
         * will be inverted. </p>
         *
         * @param cameraPreviewSize  size of the frames captured by the camera
         * @param surfacePreviewSize size of the surface on which frames will be drawn
         */
        void onPreviewSizesCalculated(Size cameraPreviewSize, Size surfacePreviewSize);
    }

}
