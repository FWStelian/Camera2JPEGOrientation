package com.futureworkshops.camera2jpegorientation.presentation.camera;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.HapticFeedbackConstants;
import android.view.View;

import com.futureworkshops.camera2jpegorientation.JpegOrientationApp;
import com.futureworkshops.camera2jpegorientation.R;
import com.futureworkshops.camera2jpegorientation.presentation.common.BaseActivity;
import com.futureworkshops.camera2jpegorientation.presentation.viewer.JpegViewerActivity;
import com.futureworkshops.camera2jpegorientation.widget.camera.CameraParams;
import com.futureworkshops.camera2jpegorientation.widget.camera.CameraView;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class CameraActivity extends BaseActivity {
    
    private static final String TAG = CameraActivity.class.getSimpleName();
    
    @BindView(R.id.cameraView)
    CameraView cameraView;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        
        ButterKnife.bind(this);
        
        //  setup camera
        setupCamera();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        
        //  start camera preview
        if (cameraView != null) {
            cameraView.start();
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        
        //  stop camera preview
        if (cameraView != null) {
            cameraView.stop();
        }
    }
    
    @OnClick(R.id.shutter)
    void shuterBtnClicked(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        
        // TODO: 12/03/2018 take picture
        cameraView.takePicture()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(pair -> {
                    handlePhotoData(pair);
                },
                throwable -> {
                    Log.e(TAG, "shuterBtnClicked: ", throwable);
                });
    }
    
    private void handlePhotoData(Pair<byte[], Integer> pair) {
        ((JpegOrientationApp)getApplicationContext()).setCameraData(pair);
    
        // TODO: 12/03/2018 launch viewer activity
        startActivity(new Intent(this, JpegViewerActivity.class));
        
    }
    
    private void setupCamera() {
        cameraView.setFacing(CameraParams.FACING_BACK);
        cameraView.setFlash(CameraParams.FLASH_OFF);
    }
}
