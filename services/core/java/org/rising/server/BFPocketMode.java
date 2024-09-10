package org.rising.server;

import android.app.ActivityManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

public class BFPocketMode extends Service {
    private static final String TAG = "BFPocketMode";
    
    private static final String BATTERY_FRIENDLY_POCKET_MODE_ENABLED = "battery_friendly_pocket_mode_enabled";

    private static final float PROXIMITY_THRESHOLD = 1.0f;
    private static final float LIGHT_THRESHOLD = 2.0f;
    private static final float GRAVITY_THRESHOLD = -0.6f;
    private static final int MIN_INCLINATION = 75;
    private static final int MAX_INCLINATION = 100;

    private Context mContext;
    private SensorManager mSensorManager;
    private Sensor mProximitySensor;
    private Sensor mLightSensor;
    private Sensor mAccelerometerSensor;

    private float[] gravityValues;
    private float proximitySensorValue = -1f;
    private float lightSensorValue = -1f;
    private int inclinationAngle = -1;

    private boolean mIsInBfPocket = false;
    private boolean mIsScreenOn = true;

    private PocketModeService mPocketModeService;

    @Override
    public void onCreate() {
        super.onCreate();
        mContext = this;
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mProximitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        mAccelerometerSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        IntentFilter screenStateFilter = new IntentFilter();
        screenStateFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mScreenStateBroadcastReceiver, screenStateFilter);

        mPocketModeService = PocketModeService.getInstance(mContext);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (isBatteryFriendlyPocketModeEnabled()) {
            enableSensors();
        } else {
            stopSelf();
        }
        return START_STICKY;
    }

    private boolean isBatteryFriendlyPocketModeEnabled() {
        return Settings.Secure.getIntForUser(getContentResolver(), 
            BATTERY_FRIENDLY_POCKET_MODE_ENABLED, 0, ActivityManager.getCurrentUser()) == 1;
    }

    @Override
    public void onDestroy() {
        disableSensors();
        unregisterReceiver(mScreenStateBroadcastReceiver);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void enableSensors() {
        if (mSensorManager != null) {
            mSensorManager.registerListener(mSensorEventListener, mProximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
            mSensorManager.registerListener(mSensorEventListener, mLightSensor, SensorManager.SENSOR_DELAY_NORMAL);
            mSensorManager.registerListener(mSensorEventListener, mAccelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    private void disableSensors() {
        if (mSensorManager != null) {
            mSensorManager.unregisterListener(mSensorEventListener);
        }
    }

    private final SensorEventListener mSensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                gravityValues = event.values.clone();
                double gravityMagnitude = Math.sqrt(
                        gravityValues[0] * gravityValues[0] +
                        gravityValues[1] * gravityValues[1] +
                        gravityValues[2] * gravityValues[2]);

                gravityValues[0] = (float) (gravityValues[0] / gravityMagnitude);
                gravityValues[1] = (float) (gravityValues[1] / gravityMagnitude);
                gravityValues[2] = (float) (gravityValues[2] / gravityMagnitude);

                inclinationAngle = (int) Math.round(Math.toDegrees(Math.acos(gravityValues[2])));
            } else if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
                proximitySensorValue = event.values[0];
            } else if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
                lightSensorValue = event.values[0];
            }

            detectPocketMode();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private void detectPocketMode() {
        if (!mIsScreenOn) {
            disableSensors();
            return;
        }

        boolean isBfProxInPocket = mProximitySensor != null && proximitySensorValue != -1f && proximitySensorValue < PROXIMITY_THRESHOLD;
        boolean isBfLightInPocket = mLightSensor != null && lightSensorValue != -1f && lightSensorValue < LIGHT_THRESHOLD;
        boolean isBfGravityInPocket = mAccelerometerSensor != null && gravityValues != null && gravityValues.length == 3 && gravityValues[1] < GRAVITY_THRESHOLD;
        boolean isBfInclinationInPocket = mAccelerometerSensor != null && inclinationAngle != -1 && (inclinationAngle > MIN_INCLINATION && inclinationAngle < MAX_INCLINATION);

        mIsInBfPocket = isBfProxInPocket || isBfLightInPocket || isBfGravityInPocket || isBfInclinationInPocket;

        Log.d(TAG, "Battery Friendly Mode: ProxInPocket = " + isBfProxInPocket + 
                ", LightInPocket = " + isBfLightInPocket + 
                ", GravityInPocket = " + isBfGravityInPocket + 
                ", InclinationInPocket = " + isBfInclinationInPocket);

        if (mIsInBfPocket && mPocketModeService.isDeviceOnKeyguard() && mIsScreenOn) {
            mPocketModeService.showOverlay();
            Log.d(TAG, "Battery Friendly Mode: Overlay shown");
        } else {
            mPocketModeService.hideOverlay();
            Log.d(TAG, "Battery Friendly Mode: Overlay hidden");
        }
    }

    private final BroadcastReceiver mScreenStateBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                mIsScreenOn = true;
                enableSensors();
                Log.d(TAG, "Screen is ON");
            } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                mIsScreenOn = false;
                disableSensors();
                Log.d(TAG, "Screen is OFF");
            }
        }
    };

    public boolean isDeviceInPocket() {
        return mIsInBfPocket;
    }
}
