/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bluedangertango.batvision;

import com.google.atap.tango.ux.UxExceptionEvent;
import com.google.atap.tango.ux.UxExceptionEventListener;
import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.Tango.OnTangoUpdateListener;
import com.google.atap.tangoservice.TangoConfig;
import com.google.atap.tangoservice.TangoCoordinateFramePair;
import com.google.atap.tangoservice.TangoErrorException;
import com.google.atap.tangoservice.TangoEvent;
import com.google.atap.tangoservice.TangoInvalidException;
import com.google.atap.tangoservice.TangoOutOfDateException;
import com.google.atap.tangoservice.TangoPoseData;
import com.google.atap.tangoservice.TangoXyzIjData;

import com.google.atap.tango.ux.TangoUx;
import com.google.atap.tango.ux.TangoUxLayout;
import com.projecttango.batvision.R;

import android.graphics.Color;


import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

// Playing audio
import android.media.MediaPlayer;

import java.nio.FloatBuffer;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Main Activity class for the Point Cloud Sample. Handles the connection to the {@link Tango}
 * service and propagation of Tango XyzIj data to OpenGL and Layout views. OpenGL rendering logic is
 * delegated to the {@link PCrenderer} class.
 */
public class PointCloudActivity extends Activity {

    private static final String TAG = PointCloudActivity.class.getSimpleName();
    private static final int SECS_TO_MILLISECS = 1000;
    private Tango mTango;
    private TangoConfig mConfig;
    int maxDepthPoints;
    float currentDistance = 0;
    int maxBufferSamples = 5;
    ArrayList<Float> tmpCurDistBuffer = new ArrayList<Float>(Collections.nCopies(maxBufferSamples, 0f));
    int bufferPos = 0;

    private TextView mAverageZTextView;

    MediaPlayer mp = null;
    boolean mediaReady = false;
    float targetVolume = 0f;
    float currentVolume = 0f;

    private int count;
    private int mPreviousPoseStatus;
    private float mPosePreviousTimeStamp;
    private float mXyIjPreviousTimeStamp;
    private float mCurrentTimeStamp;
    private boolean mIsTangoServiceConnected;
    private TangoPoseData mPose;

    private TangoUx mTangoUx;
    private TangoUxLayout mTangoUxLayout;

    private TextView banner;
    private RelativeLayout mBackground;

    private static final int UPDATE_INTERVAL_MS = 100;
    public static Object poseLock = new Object();
    public static Object depthLock = new Object();



    /*
     * This is an advanced way of using UX exceptions. In most cases developers can just use the in
     * built exception notifications using the Ux Exception layout. In case a developer doesn't want
     * to use the default Ux Exception notifications, he can set the UxException listener as shown
     * below.
     * In this example we are just logging all the ux exceptions to logcat, but in a real app,
     * developers should use these exceptions to contextually notify the user and help direct the
     * user in using the device in a way Tango service expects it.
     */
    private UxExceptionEventListener mUxExceptionListener = new UxExceptionEventListener() {

        @Override
        public void onUxExceptionEvent(UxExceptionEvent uxExceptionEvent) {

        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_jpoint_cloud);
        setTitle(R.string.app_name);
        mp = MediaPlayer.create(PointCloudActivity.this,R.raw.whitenoise);
        mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer player) {
                mediaReady = true;
                mp.setLooping(true); // This may not work???
                mp.start();
                mp.setVolume(0, 0);
            }
        });
        banner = (TextView) findViewById(R.id.banner);

        mBackground = (RelativeLayout) findViewById(R.id.container);

        mTango = new Tango(this);
        mConfig = new TangoConfig();
        mConfig = mTango.getConfig(TangoConfig.CONFIG_TYPE_CURRENT);
        mConfig.putBoolean(TangoConfig.KEY_BOOLEAN_DEPTH, true);

        mTangoUx = new TangoUx.Builder(this).build();
        mTangoUxLayout = (TangoUxLayout) findViewById(R.id.layout_tango);
        mTangoUx = new TangoUx.Builder(this).setTangoUxLayout(mTangoUxLayout).build();
        mTangoUx.setUxExceptionEventListener(mUxExceptionListener);


        maxDepthPoints = mConfig.getInt("max_point_cloud_elements");

        mIsTangoServiceConnected = false;
        startUIThread();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mTangoUx.stop();
        try {
            mTango.disconnect();
            mIsTangoServiceConnected = false;
        } catch (TangoErrorException e) {
            Toast.makeText(getApplicationContext(), R.string.TangoError, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mTangoUx.start();
        if (!mIsTangoServiceConnected) {
            startActivityForResult(
                    Tango.getRequestPermissionIntent(Tango.PERMISSIONTYPE_MOTION_TRACKING),
                    Tango.TANGO_INTENT_ACTIVITYCODE);
        }
        //Log.i(TAG, "onResumed");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // Check which request we're responding to
        if (requestCode == Tango.TANGO_INTENT_ACTIVITYCODE) {
            //Log.i(TAG, "Triggered");
            // Make sure the request was successful
            if (resultCode == RESULT_CANCELED) {
                Toast.makeText(this, R.string.motiontrackingpermission, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            try {
                setTangoListeners();
            } catch (TangoErrorException e) {
                Toast.makeText(this, R.string.TangoError, Toast.LENGTH_SHORT).show();
            } catch (SecurityException e) {
                Toast.makeText(getApplicationContext(), R.string.motiontrackingpermission,
                        Toast.LENGTH_SHORT).show();
            }
            try {
                mTango.connect(mConfig);
                mIsTangoServiceConnected = true;
            } catch (TangoOutOfDateException outDateEx) {
                if (mTangoUx != null) {
                    mTangoUx.onTangoOutOfDate();
                }
            } catch (TangoErrorException e) {
                Toast.makeText(getApplicationContext(), R.string.TangoError, Toast.LENGTH_SHORT)
                        .show();
            }
            setUpExtrinsics();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }


    private void setUpExtrinsics() {
        // Set device to imu matrix in Model Matrix Calculator.
        TangoPoseData device2IMUPose = new TangoPoseData();
        TangoCoordinateFramePair framePair = new TangoCoordinateFramePair();
        framePair.baseFrame = TangoPoseData.COORDINATE_FRAME_IMU;
        framePair.targetFrame = TangoPoseData.COORDINATE_FRAME_DEVICE;
        try {
            device2IMUPose = mTango.getPoseAtTime(0.0, framePair);
        } catch (TangoErrorException e) {
            Toast.makeText(getApplicationContext(), R.string.TangoError, Toast.LENGTH_SHORT).show();
        }

        // Set color camera to imu matrix in Model Matrix Calculator.
        TangoPoseData color2IMUPose = new TangoPoseData();

        framePair.baseFrame = TangoPoseData.COORDINATE_FRAME_IMU;
        framePair.targetFrame = TangoPoseData.COORDINATE_FRAME_CAMERA_COLOR;
        try {
            color2IMUPose = mTango.getPoseAtTime(0.0, framePair);
        } catch (TangoErrorException e) {
            Toast.makeText(getApplicationContext(), R.string.TangoError, Toast.LENGTH_SHORT).show();
        }
    }

    private void setTangoListeners() {
        // Configure the Tango coordinate frame pair
        final ArrayList<TangoCoordinateFramePair> framePairs = new ArrayList<TangoCoordinateFramePair>();
        framePairs.add(new TangoCoordinateFramePair(
                TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                TangoPoseData.COORDINATE_FRAME_DEVICE));
        // Listen for new Tango data
        mTango.connectListener(framePairs, new OnTangoUpdateListener() {

            @Override
            public void onPoseAvailable(final TangoPoseData pose) {
                // Passing in the pose data to UX library produce exceptions.
                if (mTangoUx != null) {
                    mTangoUx.updatePoseStatus(pose.statusCode);
                }
                // Make sure to have atomic access to Tango Pose Data so that
                // render loop doesn't interfere while Pose call back is updating
                // the data.
                synchronized (poseLock) {
                    mPose = pose;
                    mPosePreviousTimeStamp = (float) pose.timestamp;
                    if (mPreviousPoseStatus != pose.statusCode) {
                        count = 0;
                    }
                    count++;
                    mPreviousPoseStatus = pose.statusCode;
                }
            }

            @Override
            public void onXyzIjAvailable(final TangoXyzIjData xyzIj) {
                if (mTangoUx != null) {
                    mTangoUx.updateXyzCount(xyzIj.xyzCount);
                }
                // Make sure to have atomic access to TangoXyzIjData so that
                // render loop doesn't interfere while onXYZijAvailable callback is updating
                // the point cloud data.
                synchronized (depthLock) {
                    mCurrentTimeStamp = (float) xyzIj.timestamp;

                    mXyIjPreviousTimeStamp = mCurrentTimeStamp;
                    try {
                        // Average all the z values
                        int count = xyzIj.xyzCount;
                        float totalDistance = 0;
                        FloatBuffer pts = xyzIj.xyz;
                        for (int i = 0; i < count; i++) {
                            totalDistance += pts.get(((i + 1) * 3) - 1);
                        }
                        //totalDistance = pts.get(3);
                        float avgDistance = totalDistance / count;
                        tmpCurDistBuffer.set(bufferPos, avgDistance);
                        bufferPos++;
                        if (bufferPos >= maxBufferSamples-1)
                            bufferPos = 0;
                        currentDistance = getMedian(tmpCurDistBuffer);

                        //Log.i("LOOK HERE", "Average distance is " + avgDistance);

                    } catch (TangoErrorException e) {
                        Toast.makeText(getApplicationContext(), R.string.TangoError,
                                Toast.LENGTH_SHORT).show();
                    } catch (TangoInvalidException e) {
                        Toast.makeText(getApplicationContext(), R.string.TangoError,
                                Toast.LENGTH_SHORT).show();
                    }
                }
            }

            @Override
            public void onTangoEvent(final TangoEvent event) {
                if (mTangoUx != null) {
                    mTangoUx.onTangoEvent(event);
                }

            }

            @Override
            public void onFrameAvailable(int cameraId) {
                // We are not using onFrameAvailable for this application.
            }
        });
    }


    private void onDistanceKnown(float distance) {
        this.playSound(distance);
        this.updateGui(distance);
    }

    private void updateGui(float distance) {
        // Update the background color
        int color = getColor(distance);
        mBackground.setBackgroundColor(color);


        // Update the text
        int text = R.string.far_msg;
        if (distance < 1) {
            text = R.string.close_msg;
        } else if (distance < 3) {
            text = R.string.medium_msg;
        }

        // Set the text
        banner.setText(text);
    }

    // Play a sound given the distance from the users (in meters)
    // distance is probably between 0 & 5
    private void playSound(float distance) {

        if (!mediaReady) {
            return;
        }

        try {
            // 0 -> 1; 5 -> 0
            // 1 - (Math.min(distance,5))/maxDistance
            int maxDistance = 3;
            targetVolume = 1 - (Math.min(distance, maxDistance)/maxDistance);
            //Log.i("AUDIO", "Setting targetVolume to "+targetVolume+" (distance is "+distance+")");
            float dv = 0.025f;
            float diff = Math.abs(targetVolume - currentVolume);
            int iterations = (int) (diff/dv);
            if (targetVolume - currentVolume < 0) {
              dv *= -1;
            }

            try {
                for (int i = 0; i < iterations; i++) {
                    currentVolume += dv;
                    mp.setVolume(currentVolume, currentVolume);
                    Thread.sleep(10);
                }

            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            mp.setVolume(targetVolume, targetVolume);
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
    }

    // generate a color based on the distance
    private int getColor(float distance) {
        // We want to color to go from red to blue depending on the distance (0-240)
        if (distance > 5)
            distance = 5;
        float hue = distance * 240.0f/5.0f;
        
        float[] hsv = {hue, 1.0f, 1.0f};
        return Color.HSVToColor(hsv);
    }

    private float getMedian(ArrayList<Float> data) {
        Collections.sort(data);
        return data.get(data.size()/2);
    }

    /**
     * Create a separate thread to update Log information on UI at the specified interval of
     * UPDATE_INTERVAL_MS. This function also makes sure to have access to the mPose atomically.
     */
    private void startUIThread() {
        new Thread(new Runnable() {
            final DecimalFormat threeDec = new DecimalFormat("0.000");

            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(UPDATE_INTERVAL_MS);
                        // Update the UI with TangoPose information
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                synchronized (poseLock) {
                                    if (mPose == null) {
                                        return;
                                    }
                                    onDistanceKnown(currentDistance);

                                }
                            }
                        });
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
    }
}
