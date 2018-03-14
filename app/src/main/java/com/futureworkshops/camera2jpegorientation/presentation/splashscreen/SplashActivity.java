package com.futureworkshops.camera2jpegorientation.presentation.splashscreen;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.futureworkshops.camera2jpegorientation.R;
import com.futureworkshops.camera2jpegorientation.presentation.camera.CameraActivity;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;

public class SplashActivity extends AppCompatActivity {
    
    public static final int RC_HANDLE_CAMERA_PERM = 2;
    
    private static final String TAG = SplashActivity.class.getSimpleName();
    
    @BindView(R.id.permissionControlsLayout)
    LinearLayout permissionControlsLayout;
    
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        
        ButterKnife.bind(this);
    }
    
    @Override
    protected void onStart() {
        super.onStart();
        
        // check camera permissions
        checkCameraPermissions();
    }
    
    private void checkCameraPermissions() {
        // Check for the camera permission before accessing the camera.  If the
        // permission is not granted yet, request permission.
        int rc = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        if (rc == PackageManager.PERMISSION_GRANTED) {
            handleCameraPermissionGranted();
        } else {
            // show permission required view
            permissionControlsLayout.setVisibility(View.VISIBLE);
        }
    }
    
    /**
     * Handles the requesting of the camera permission.  This includes
     * showing a "Snackbar" message of why the permission is needed then
     * sending the request.
     */
    @OnClick(R.id.grantPermission)
    public void requestCameraPermission(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        Log.w(TAG, "Camera permission is not granted. Requesting permission");
        
        final String[] permissions = new String[] {Manifest.permission.CAMERA};
        
        if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            ActivityCompat.requestPermissions(this, permissions, RC_HANDLE_CAMERA_PERM);
            return;
        }
        
        final Activity thisActivity = this;
        
        View.OnClickListener listener = new View.OnClickListener() {
            
            @Override
            public void onClick(View view) {
                ActivityCompat.requestPermissions(thisActivity, permissions, RC_HANDLE_CAMERA_PERM);
            }
        };
        
        showSnackbarWithAction(this,
            getString(R.string.permission_camera_rationale),
            listener);
        
    }
    
    /**
     * Callback for the result from requesting permissions. This method
     * is invoked for every call on {@link #requestPermissions(String[], int)}.
     * <p>
     * <strong>Note:</strong> It is possible that the permissions request interaction
     * with the user is interrupted. In this case you will receive empty permissions
     * and results arrays which should be treated as a cancellation.
     * </p>
     * @param requestCode The request code passed in {@link #requestPermissions(String[], int)}.
     * @param permissions The requested permissions. Never null.
     * @param grantResults The grant results for the corresponding permissions
     * which is either {@link PackageManager#PERMISSION_GRANTED}
     * or {@link PackageManager#PERMISSION_DENIED}. Never null.
     * @see #requestPermissions(String[], int)
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        // handle only the permissions we request
        // the code bellow is fine because we ask permission separately!
        if (requestCode == RC_HANDLE_CAMERA_PERM) {
            final boolean permissionGranted = grantResults.length != 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (permissionGranted) {
                // we have permission, so create the camera source
                handleCameraPermissionGranted();
            } else {
                handleCameraPermissionNotGranted(grantResults);
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }
        
    }
    
    private void handleCameraPermissionNotGranted(@NonNull int[] grantResults) {
        Log.d(TAG, "Permission not granted: results len = " + grantResults.length +
            " Result code = " + (grantResults.length > 0 ? grantResults[0] : "(empty)"));
        
        DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
            
            public void onClick(DialogInterface dialog, int id) {
                finish();
            }
        };
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.app_name))
            .setMessage(R.string.no_camera_permission)
            .setPositiveButton(R.string.action_ok, listener)
            .show();
    }
    
    private void handleCameraPermissionGranted() {
        startActivity(new Intent(this, CameraActivity.class));
    }
    
    private void showSnackbarWithAction(Activity activity, String message, View.OnClickListener onClickListener) {
        final Snackbar snackbar = Snackbar.make(getViewForActivity(activity), message, Snackbar.LENGTH_INDEFINITE)
            .setAction(activity.getString(R.string.action_ok), onClickListener);
        
        // increase number of lines shown in snackbar
        View snackbarView = snackbar.getView(); //get your snackbar view
        TextView textView = (TextView) snackbarView.findViewById(android.support.design.R.id.snackbar_text); //Get reference of snackbar textview
        textView.setMaxLines(3);
        
        snackbar.show();
    }
    
    private static View getViewForActivity(@NonNull Activity activity) {
        return activity.getWindow().getDecorView().findViewById(android.R.id.content);
    }
}
