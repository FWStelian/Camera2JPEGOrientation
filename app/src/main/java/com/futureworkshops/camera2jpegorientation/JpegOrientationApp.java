package com.futureworkshops.camera2jpegorientation;

import android.app.Application;
import android.util.Pair;

/**
 * Created by stelian on 12/03/2018.
 */

public class JpegOrientationApp extends Application {
    
    /**
     * Inappropriate way of saving the camera information to make it accessible between activities.
     */
    private Pair<byte[], Integer> lastCameraData;
    
    @Override
    public void onCreate() {
        super.onCreate();
    }
    
    public synchronized void setCameraData(Pair<byte[], Integer> cameraData) {
        lastCameraData = cameraData;
    }
    
    public synchronized Pair<byte[], Integer> getLastCameraData() {
        return lastCameraData;
    }
}
