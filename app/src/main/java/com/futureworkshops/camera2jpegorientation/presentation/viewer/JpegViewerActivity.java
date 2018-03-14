package com.futureworkshops.camera2jpegorientation.presentation.viewer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.support.media.ExifInterface;
import android.util.Log;
import android.util.Pair;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;

import com.bumptech.glide.Glide;
import com.futureworkshops.camera2jpegorientation.JpegOrientationApp;
import com.futureworkshops.camera2jpegorientation.R;
import com.futureworkshops.camera2jpegorientation.presentation.common.BaseActivity;
import com.futureworkshops.camera2jpegorientation.widget.camera.JpegSaver;

import java.io.File;
import java.io.IOException;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;

public class JpegViewerActivity extends BaseActivity {
    
    @BindView(R.id.imageView)
    ImageView imageView;
    
    private JpegOrientationApp jpegOrientationApp;
    private JpegSaver jpegSaver;
    private String currentFilePath;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_jpeg_viewer);
        
        ButterKnife.bind(this);
        
        setupToolbar(true);
        
        jpegOrientationApp = (JpegOrientationApp) getApplicationContext();
        jpegSaver = new JpegSaver(this);
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        deletePreviousFile();
    }
    
    @OnClick(R.id.decodeByteArrayBtn)
    void onDecodeByteArrayClicked(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        
        final Pair<byte[], Integer> cameraData = jpegOrientationApp.getLastCameraData();
        
        if (cameraData != null) {
            final Bitmap bitmap = BitmapFactory.decodeByteArray(cameraData.first,
                0, cameraData.first.length);
            
            imageView.setScaleType(ScaleType.FIT_CENTER);   //required
            imageView.setImageBitmap(bitmap);
        }
        
    }
    
    @OnClick(R.id.decodeFileBtn)
    void onDecodeFileClicked(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        
        // delete previous file
        deletePreviousFile();
        
        final Pair<byte[], Integer> cameraData = jpegOrientationApp.getLastCameraData();
        
        if (cameraData != null) {
            String fileName = String.valueOf(System.currentTimeMillis()) + ".jpeg";
            currentFilePath = jpegSaver
                .saveTempJpeg(cameraData.first, fileName, cameraData.second);
            
            try {
                ExifInterface exifInterface = new ExifInterface(currentFilePath);
                int exifRotation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                Log.d("JpegViewerActivity", "current exif orientation: " + String.valueOf(exifRotation));
                
                // automatically reads EXIF and rotates image
                Glide.with(this)
                    .load(currentFilePath)
                    .into(imageView);
//
                // not really working
//                Picasso.get()
//                    .load(currentFilePath)
//                    .into(imageView);
                
//                final Bitmap bitmap = BitmapFactory.decodeFile(currentFilePath);
//                imageView.setImageBitmap(bitmap);
//
//                // calculate the scale
//                float scaleWidth = ((float) imageView.getWidth()) / bitmap.getWidth();
//                float scaleHeight = ((float) imageView.getHeight()) / bitmap.getHeight();
//                int angle = exifInterface.getRotationDegrees();
//
//                // swap dimensions if we need to rotate the image
//                if (angle == 90 || angle == 270) {
//                    float tmp = scaleWidth;
//                    scaleWidth = scaleHeight;
//                    scaleHeight = tmp;
//                }
//
//                imageView.setScaleType(ImageView.ScaleType.MATRIX);   //required
//                Matrix matrix = new Matrix();
//
//                matrix.preRotate((float) angle, imageView.getPivotX(), imageView.getPivotY());
//                matrix.postScale(scaleWidth, scaleHeight);
//
//
//                imageView.setImageMatrix(matrix);
                
            } catch (IOException e) {
                e.printStackTrace();
            }
            
        }
    }
    
    private void deletePreviousFile() {
        if (currentFilePath != null) {
            final File file = new File(currentFilePath);
            file.delete();
        }
    }
    
}
