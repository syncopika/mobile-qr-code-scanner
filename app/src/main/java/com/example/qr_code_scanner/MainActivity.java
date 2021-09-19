package com.example.qr_code_scanner;

import android.Manifest;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.Result;
import com.google.zxing.NotFoundException;
import com.google.zxing.ChecksumException;
import com.google.zxing.FormatException;

import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.CameraDevice.StateCallback;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCaptureSession.CaptureCallback;
import android.hardware.camera2.CaptureRequest.Builder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import android.view.TextureView;
import android.util.Size;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;

import com.google.android.material.snackbar.Snackbar;

import java.util.Collections;


@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CAMERA_PERMISSION = 100; // id for specifying camera-specific action. can be any value.
    private static final String TAG = "MainActivity"; // for logging
    private CameraManager cManager;
    private String cameraId;
    private CameraDevice camera;
    private Size cameraPreviewSize;
    private int cameraDirection;
    private CameraCaptureSession captureSession;
    private CaptureCallback captureCallback;
    private CaptureRequest captureRequest;
    private TextureView.SurfaceTextureListener surfaceTextureListener;
    private TextureView textureView;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private StateCallback stateCallback;
    private Builder captureRequestBuilder;
    private Button captureButton;

    // set up a background thread and backgroundHandler for openCamera
    private void startBackgroundThread(){
        backgroundThread = new HandlerThread("camera-background-thread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread(){
        if(backgroundHandler != null){
            backgroundThread.quitSafely();
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    // set up camera preview attributes (i.e. dimensions)
    private void setupCamera(){
        try {
            if(cManager.getCameraIdList().length == 0){
                throw new CameraAccessException(CameraAccessException.CAMERA_ERROR);
            }
            cameraId = cManager.getCameraIdList()[0];
            CameraCharacteristics camChars = cManager.getCameraCharacteristics(cameraId);

            if(camChars.get(CameraCharacteristics.LENS_FACING) == cameraDirection){
                StreamConfigurationMap streamConfigMap = camChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                cameraPreviewSize = streamConfigMap.getOutputSizes(SurfaceTexture.class)[0];
            }
        }catch(CameraAccessException err){
            err.printStackTrace();
        }
    }

    // activate the camera
    private void openCamera(){
        try {
            //Log.i(TAG, "in openCamera. checking permissions to use camera...");
            if(ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED){
                cManager.openCamera(cameraId, stateCallback, backgroundHandler);
            }
        }catch(CameraAccessException err){
            err.printStackTrace();
        }
    }

    // stop the camera
    private void closeCamera(){
        if(captureSession != null){
            captureSession.close();
            captureSession = null;
        }

        if(camera != null){
            camera.close();
            camera = null;
        }
    }

    // establishes the camera's capture session so it can take pictures
    private void createCameraSession(){
        try {
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            surfaceTexture.setDefaultBufferSize(cameraPreviewSize.getWidth(), cameraPreviewSize.getHeight());

            Surface previewSurface = new Surface(surfaceTexture);

            captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(previewSurface);

            camera.createCaptureSession(
                    Collections.singletonList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (camera == null) {
                                return;
                            }

                            try {
                                captureRequest = captureRequestBuilder.build();
                                MainActivity.this.captureSession = session;
                                MainActivity.this.captureSession.setRepeatingRequest(captureRequest, null, backgroundHandler);
                            } catch (CameraAccessException err) {
                                err.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {}
                    },
                    backgroundHandler);

        }catch(CameraAccessException err){
            err.printStackTrace();
        }
    }

    private void processImage(){
        // get image and process it for the QR code
        // textureView is holding the current image
        Bitmap currImage = textureView.getBitmap();
        int imageWidth = currImage.getWidth();
        int imageHeight = currImage.getHeight();
        int[] currImagePixels = new int[imageWidth*imageHeight]; // each element represents a color (so it's not like each element represents a color channel)
        currImage.getPixels(currImagePixels, 0, imageWidth, 0, 0, imageWidth, imageHeight);

        // zxing stuff
        RGBLuminanceSource imgData = new RGBLuminanceSource(imageWidth, imageHeight, currImagePixels);
        HybridBinarizer processedImg = new HybridBinarizer(imgData); // this class does some image processing to the image to make it easier to find the QR code, if present
        BinaryBitmap imgBitmap = new BinaryBitmap(processedImg);
        QRCodeReader reader = new QRCodeReader();

        try {
            Result qrCodeResult = reader.decode(imgBitmap);
            String data = qrCodeResult.getText();

            // TODO: add an action to the Snackbar (i.e. option to open the link (need permission))
            Snackbar.make(textureView, data, Snackbar.LENGTH_SHORT).setAction("Action", new View.OnClickListener(){
                @Override
                public void onClick(View v) {
                    Intent goToLink = new Intent(Intent.ACTION_VIEW, Uri.parse(data));
                    startActivity(goToLink);
                }
            }).show();

        }catch(NotFoundException err){
            Snackbar.make(textureView, "no QR code found!", Snackbar.LENGTH_SHORT).show();
        }catch(ChecksumException err){
            Snackbar.make(textureView, "a QR code was detected but something seemed off. please try again!", Snackbar.LENGTH_SHORT).show();
        }catch(FormatException err){
            Snackbar.make(textureView, "a QR code was detected but something seemed off. please try again!", Snackbar.LENGTH_SHORT).show();
        }

        try {
            // reset
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            captureRequest = captureRequestBuilder.build();
            MainActivity.this.captureSession.setRepeatingRequest(captureRequest, null, backgroundHandler);
        }catch(CameraAccessException err){
            err.printStackTrace();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // ask for permission from user to use camera
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);

        // set up camera manager and texture listener
        cManager = (CameraManager) this.getSystemService(Context.CAMERA_SERVICE);
        cameraDirection = CameraCharacteristics.LENS_FACING_BACK;

        textureView = (TextureView) findViewById(R.id.texture_view); // find view by the id associated with the TextureView element in the layout xml
        captureButton = (Button) findViewById(R.id.takepicture_btn);

        surfaceTextureListener = new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                // textureView is used to show the camera stream.
                // when the surface of the textureview is ready to be used, we can set up the camera since it relies on the surface
                setupCamera();
                openCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
        };

        // set up callback for camera state
        stateCallback = new CameraDevice.StateCallback(){
            @Override
            public void onOpened(CameraDevice cameraDevice) {
                // camera is on, start the session
                MainActivity.this.camera = cameraDevice;
                createCameraSession();
            }

            @Override
            public void onDisconnected(CameraDevice cameraDevice) {
                cameraDevice.close();
                MainActivity.this.camera = null;
            }

            @Override
            public void onError(CameraDevice cameraDevice, int error) {
                cameraDevice.close();
                MainActivity.this.camera = null;
            }
        };

        // do the QR code capture/processing only when camera is focused
        // we'll use this callback in the request to capture/process the QR code image on button press
        captureCallback = new CaptureCallback() {
            @Override
            public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber){
                //Log.i(TAG, "ON CAPTURE STARTED...");
            }

            @Override
            public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult){
                // this doesn't seem to be getting called at all :( at least on a Samsung Galaxy J3
                // I think ideally we'd try to check if the camera is focused here and if so, call processImage().
                //Log.i(TAG, "partial result control af state: " + partialResult.get(CaptureResult.CONTROL_AF_STATE));
            }

            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                //Log.i(TAG, "result control af state: " + result.get(CaptureResult.CONTROL_AF_STATE));
                processImage(); // the camera may not be focused when this is called but I think this is the best I can do atm. for the most part it still seems to work just fine for me.
            }
        };

        captureButton.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v) {
                try{
                    MainActivity.this.captureSession.stopRepeating();

                    // autofocus the camera
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
                    captureRequest = captureRequestBuilder.build(); // request needs to be rebuilt with new settings

                    MainActivity.this.captureSession.capture(captureRequest, captureCallback, backgroundHandler);

                }catch(CameraAccessException err){
                    err.printStackTrace();
                }
            }
        });
    }

    @Override
    protected void onResume(){
        // I think this happens if the phone comes back from sleeping
        super.onResume();
        startBackgroundThread();
        if(textureView.isAvailable()){
            setupCamera();
            openCamera();
        }else{
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    protected void onStop(){
        super.onStop();
        closeCamera();
        stopBackgroundThread();
    }

}