/*
 * Copyright (C) 2023 The RisingOS Android Project
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
package org.rising.server;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager; 
import android.os.UserHandle;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.telecom.TelecomManager;
import android.provider.Settings;
import android.view.Display;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.util.Log;
import com.android.internal.R;
import com.android.server.SystemService;

public class PocketModeService extends SystemService {

    private static final float PROXIMITY_THRESHOLD = 1.0f;
    private static final float LIGHT_THRESHOLD = 2.0f;
    private static final float GRAVITY_THRESHOLD = -0.6f;
    private static final int MIN_INCLINATION = 75;
    private static final int MAX_INCLINATION = 100;

    public static final String ACTION_POCKET_STATE_CHANGED = "org.rising.server.action.POCKET_STATE_CHANGED";
    public static final String EXTRA_IN_POCKET = "in_pocket";
    
    private static final long DISPLAY_OFF_DELAY = 3000;
    private Handler mDisplayOffHandler = new Handler();
    private Runnable mDisplayOffRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isCallInProgress() && isDeviceOnKeyguard()) {
                hideOverlay();
                if (mPowerManager != null) {
                    mPowerManager.goToSleep(SystemClock.uptimeMillis());
                }
            }
        }
    };

    private float[] gravityValues;
    private float proximitySensorValue;
    private float lightSensorValue;
    private int inclinationAngle;
    private static final String TAG = "PocketModeSrc";

    private Context mContext;
    private View mOverlayView;
    private WindowManager mWindowManager;
    private GestureDetector mGestureDetector;
    private PowerManager mPowerManager;
    private WindowManager.LayoutParams mLayoutParams;
    private TelecomManager mTelecomManager;

    private BroadcastReceiver mScreenStateReceiver;

    private SettingsObserver mSettingsObserver;

    private static final String ALWAYS_ON_POCKET_MODE_ENABLED = "always_on_pocket_mode_enabled";
    private static final String POCKET_MODE_ENABLED = "pocket_mode_enabled";
    private final Handler mHandler = new Handler();
    
    private static final String BATTERY_FRIENDLY_POCKET_MODE_ENABLED = "battery_friendly_pocket_mode_enabled";
    private boolean mBatteryFriendlyPocketModeEnabled;

    private static PocketModeService instance;

    private KeyguardManager mKeyguardManager;
    private SensorManager mSensorManager;
    private Sensor mAccelerometerSensor;
    private Sensor mProximitySensor;
    private Sensor mLightSensor;

    boolean mIsInBfPocket;
    boolean mIsInPocket;
    boolean mIsOverlayUserUnlocked;
    private boolean mShowing = false;

    private static final int POCKET_MODE_SENSOR_DELAY = 400000;
    
    private VibrationEffect mDoubleClickEffect;
    private Vibrator mVibrator;
    
    private boolean mPocketModeEnabled;
    private boolean mAlwaysOnPocketModeEnabled;

    private boolean mIsScreenOn = true;

    private PocketModeService(Context context) {
        super(context);
        mContext = context;
        mPocketModeEnabled = isPocketModeEnabled();
        mAlwaysOnPocketModeEnabled = isAlwaysOnPocketMode();
    }
    
    public static synchronized PocketModeService getInstance(Context context) {
        if (instance == null) {
            instance = new PocketModeService(context);
        }
        return instance;
    }

    private void sendPocketStateChangedBroadcast(boolean inPocket) {
        Intent intent = new Intent(ACTION_POCKET_STATE_CHANGED);
        intent.setPackage("com.android.systemui");
        intent.putExtra(EXTRA_IN_POCKET, inPocket);
        mContext.sendBroadcast(intent);
    }

    private boolean isDozing() {
        Display display = mContext.getDisplay();
        if (display != null) {
            int state = display.getState();
            return state == Display.STATE_DOZE 
                || state == Display.STATE_DOZE_SUSPEND
                || state == Display.STATE_ON_SUSPEND;
        }
        return false;
    }
    
    private boolean isAlwaysOnPocketMode() {
        return Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                ALWAYS_ON_POCKET_MODE_ENABLED,
                0, ActivityManager.getCurrentUser()) == 1;
    }

    private boolean isPocketModeEnabled() {
        return Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                POCKET_MODE_ENABLED,
                0, ActivityManager.getCurrentUser()) == 1;
    }

    private void registerListeners() {
        registerSensorListeners();
        initializeGestureDetector(mContext);
        mOverlayView.setOnTouchListener(mOverlayTouchListener);
    }

    private void unregisterListeners() {
        unregisterSensorListeners();
        mGestureDetector = null;
        mOverlayView.setOnTouchListener(null);
    }

    public void onInteractiveChanged(boolean show) {

        if (mBatteryFriendlyPocketModeEnabled) {
            // Battery Friendly Pocket Mode: Handle sensors based on screen state
            if (show && mIsInBfPocket && !mIsOverlayUserUnlocked) {
                showOverlay();
                Log.d(TAG, "Battery Friendly Mode: Overlay shown and sensors enabled");
            } else if (mShowing) {
                hideOverlay();
                Log.d(TAG, "Battery Friendly Mode: Overlay hidden and sensors disabled");
            }
            return;  // Exit after handling battery-friendly mode
        }

        if (!mBatteryFriendlyPocketModeEnabled) {
            mIsOverlayUserUnlocked = false;
        }

        // Traditional pocket mode behavior
        if ((show && mAlwaysOnPocketModeEnabled || mIsInPocket) 
            && !mIsOverlayUserUnlocked && mPocketModeEnabled) {
            showOverlay();
        } else if (!mPocketModeEnabled && mShowing) {
            hideOverlay();
        }
    }

    private final BroadcastReceiver mScreenStateBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                mIsScreenOn = true;
                Log.d(TAG, "Screen is ON");
            } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                mIsScreenOn = false;
                Log.d(TAG, "Screen is OFF");
            }
        }
    };

    private void enableSensorsForBatteryFriendlyMode() {
        // Enable only the necessary sensors for battery-friendly mode
        if (mProximitySensor != null) {
            mSensorManager.registerListener(mPocketModeListener, mProximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    private void disableSensors() {
        if (mProximitySensor != null) {
            mSensorManager.unregisterListener(mPocketModeListener, mProximitySensor);
        } 
        
        if (mLightSensor != null) {
            mSensorManager.unregisterListener(mPocketModeListener, mLightSensor);
        } 
        
        if (mAccelerometerSensor != null) {
            mSensorManager.unregisterListener(mPocketModeListener, mAccelerometerSensor);
        }
    }

    private void showOverlay() {
        if (isDozing()) return;
        final Runnable show = new Runnable() {
            @Override
            public void run() {
                if (!isCallInProgress() && mWindowManager != null && !mShowing && isDeviceOnKeyguard()) {
                    mWindowManager.addView(mOverlayView, mLayoutParams);
                    mOverlayView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
                    mShowing = true;
                    startDisplayOffTimer();
                    sendPocketStateChangedBroadcast(true);
                }
            }
        };
        mHandler.post(show);
    }

    private void hideOverlay() {
        final Runnable hide = new Runnable() {
            @Override
            public void run() {
                if (mWindowManager != null && mShowing) {
                    mOverlayView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                    mWindowManager.removeView(mOverlayView);
                    mShowing = false;
                    cancelDisplayOffTimer();
                    sendPocketStateChangedBroadcast(false);
                }
            }
        };
        mHandler.post(hide);
    }

    private View.OnTouchListener mOverlayTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            mGestureDetector.onTouchEvent(event);
            return true;
        }
    };

    private void registerSensorListeners() {
        if (mSensorManager != null) {
            if (mAccelerometerSensor != null) {
                mSensorManager.registerListener(mPocketModeListener, mAccelerometerSensor, POCKET_MODE_SENSOR_DELAY, mHandler);
            }
            if (mProximitySensor != null) {
                mSensorManager.registerListener(mPocketModeListener, mProximitySensor, POCKET_MODE_SENSOR_DELAY, mHandler);
            }
            if (mLightSensor != null) {
                mSensorManager.registerListener(mPocketModeListener, mLightSensor, POCKET_MODE_SENSOR_DELAY, mHandler);
            }
        }
    }

    private void unregisterSensorListeners() {
        if (mSensorManager != null && mPocketModeListener != null) {
            mSensorManager.unregisterListener(mPocketModeListener);
        }
    }
    
    private final SensorEventListener mPocketModeListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent sensorEvent) {
            if (sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                gravityValues = sensorEvent.values.clone();
                double gravityMagnitude = Math.sqrt(
                        gravityValues[0] * gravityValues[0] +
                                gravityValues[1] * gravityValues[1] +
                                gravityValues[2] * gravityValues[2]);

                gravityValues[0] = (float) (gravityValues[0] / gravityMagnitude);
                gravityValues[1] = (float) (gravityValues[1] / gravityMagnitude);
                gravityValues[2] = (float) (gravityValues[2] / gravityMagnitude);

                inclinationAngle = (int) Math.round(Math.toDegrees(Math.acos(gravityValues[2])));
            }

            if (sensorEvent.sensor.getType() == Sensor.TYPE_PROXIMITY) {
                proximitySensorValue = sensorEvent.values[0];
            }

            if (sensorEvent.sensor.getType() == Sensor.TYPE_LIGHT) {
                lightSensorValue = sensorEvent.values[0];
            }

            if (proximitySensorValue != -1 && lightSensorValue != -1 && inclinationAngle != -1 && gravityValues != null) {
                detect(proximitySensorValue, lightSensorValue, gravityValues, inclinationAngle);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private float round(float value) {
        return Math.round(value * 100.0) / 100.0f;
    }

    public void detect(Float prox, Float light, float[] g, Integer inc) {
        // If always-on pocket mode is enabled, we don't need to process further
        if (mAlwaysOnPocketModeEnabled) return;

        // Handle Battery Friendly Pocket Mode
        if (mBatteryFriendlyPocketModeEnabled && isDeviceOnKeyguard() && mIsScreenOn) {
            // Combine proximity, light, and accelerometer for better accuracy
            boolean isBfProxInPocket = mProximitySensor != null && prox != -1f && prox < PROXIMITY_THRESHOLD;
            boolean isBfLightInPocket = mLightSensor != null && light != -1f && light < LIGHT_THRESHOLD;
            boolean isBfGravityInPocket = mAccelerometerSensor != null && g != null && g.length == 3 && g[1] < GRAVITY_THRESHOLD;
            boolean isBfInclinationInPocket = mAccelerometerSensor != null && inc != -1 && (inc > MIN_INCLINATION && inc < MAX_INCLINATION);

            // Combine all sensor data for pocket detection
            mIsInBfPocket = isBfProxInPocket || isBfLightInPocket || isBfGravityInPocket || isBfInclinationInPocket;

            // Log sensor data for debugging
            Log.d(TAG, "Battery Friendly Mode: ProxInPocket = " + isBfProxInPocket + 
                    ", LightInPocket = " + isBfLightInPocket + 
                    ", GravityInPocket = " + isBfGravityInPocket + 
                    ", InclinationInPocket = " + isBfInclinationInPocket);

            // Show overlay if all conditions indicate the phone is in a pocket
            if (mIsInBfPocket && !mIsOverlayUserUnlocked && isDeviceOnKeyguard() && mIsScreenOn) {
                showOverlay();
                Log.d(TAG, "Battery Friendly Mode: Overlay shown");
            } else {
                hideOverlay();
                Log.d(TAG, "Battery Friendly Mode: Overlay hidden");
            }
            return;  // Exit after handling battery-friendly mode

        } else if (mBatteryFriendlyPocketModeEnabled && !mIsScreenOn) {
            disableSensors();
            return;
        }

        // Traditional pocket mode behavior
        boolean isProxInPocket = mProximitySensor != null && prox != -1f && prox < PROXIMITY_THRESHOLD;
        boolean isLightInPocket = mLightSensor != null && light != -1f && light < LIGHT_THRESHOLD;
        boolean isGravityInPocket = mAccelerometerSensor != null && g != null && g.length == 3 && g[1] < GRAVITY_THRESHOLD;
        boolean isInclinationInPocket = mAccelerometerSensor != null && inc != -1 && (inc > MIN_INCLINATION || inc < MAX_INCLINATION);

        // Check proximity first
        mIsInPocket = isProxInPocket;

        // If proximity doesn't indicate pocket mode, check the other conditions
        if (!mIsInPocket) {
            mIsInPocket = isLightInPocket && isGravityInPocket && isInclinationInPocket;
        }
        if (!mIsInPocket) {
            mIsInPocket = isGravityInPocket && isInclinationInPocket;
        }

        // Show or hide overlay based on pocket detection and user unlock state
        if (mIsInPocket && !mIsOverlayUserUnlocked) {
            showOverlay();
        } else {
            hideOverlay();
        }
    }

    @Override
    public void onStart() {
        mVibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mKeyguardManager = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mTelecomManager = (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
        if (mSensorManager != null) {
            mAccelerometerSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
            mProximitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        }
        mDoubleClickEffect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK);
        createOverlayView(mContext);
        mSettingsObserver = new SettingsObserver(new Handler());
        mSettingsObserver.observe();

        IntentFilter screenStateFilter = new IntentFilter();
        screenStateFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mScreenStateBroadcastReceiver, screenStateFilter);
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }
        void observe() {
            mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(ALWAYS_ON_POCKET_MODE_ENABLED), false, this,
                    UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(POCKET_MODE_ENABLED), false, this,
                    UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(BATTERY_FRIENDLY_POCKET_MODE_ENABLED), false, this, 
                    UserHandle.USER_ALL);
            updatePocketModeSettings();
        }

        void updatePocketModeSettings() {
            mPocketModeEnabled = isPocketModeEnabled();
            mAlwaysOnPocketModeEnabled = isAlwaysOnPocketMode();
            mBatteryFriendlyPocketModeEnabled = isBatteryFriendlyPocketModeEnabled();
            if (mPocketModeEnabled || (mBatteryFriendlyPocketModeEnabled && isDeviceOnKeyguard() && mIsScreenOn)) {
                registerListeners();
            } else {
                unregisterListeners();
            }
        }

        private boolean isBatteryFriendlyPocketModeEnabled() {
            return Settings.Secure.getIntForUser(mContext.getContentResolver(), BATTERY_FRIENDLY_POCKET_MODE_ENABLED, 0, ActivityManager.getCurrentUser()) == 1;
        }

        @Override
        public void onChange(boolean selfChange) {
            updatePocketModeSettings();
        }
    }

    private void createOverlayView(Context context) {
        mOverlayView = View.inflate(context, R.layout.pocket_mode_layout, null);
        mLayoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
        );
        mLayoutParams.gravity = Gravity.CENTER;
        mLayoutParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        mOverlayView.setLayoutParams(mLayoutParams);
        mOverlayView.setBackgroundColor(Color.argb(224, 0, 0, 0));
    }

    private void vibrate(VibrationEffect effect) {
        if (mVibrator != null) {
            mVibrator.vibrate(effect);
        }
    }

    private void initializeGestureDetector(Context context) {
        mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                vibrate(mDoubleClickEffect);
                hideOverlay();
                mIsOverlayUserUnlocked = true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (mPowerManager != null) {
                    hideOverlay();
                    mPowerManager.goToSleep(SystemClock.uptimeMillis());
                }
                return true;
            }
        });
    }

    private boolean isDeviceOnKeyguard() {
        return mKeyguardManager != null && mKeyguardManager.isKeyguardLocked();
    }

    private boolean isCallInProgress() {
        if (mTelecomManager != null) {
            return mTelecomManager.isRinging() || mTelecomManager.isInCall();
        }
        return false;
    }
    
    private void startDisplayOffTimer() {
        mDisplayOffHandler.postDelayed(mDisplayOffRunnable, DISPLAY_OFF_DELAY);
    }

    private void cancelDisplayOffTimer() {
        mDisplayOffHandler.removeCallbacks(mDisplayOffRunnable);
    }
    
    public boolean isOverlayShowing() {
        return mShowing;
    }
    
    public boolean isDeviceInPocket() {
        return (mIsInPocket || mIsInBfPocket) && !mIsOverlayUserUnlocked;
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            // Register the receiver after boot
            IntentFilter screenStateFilter = new IntentFilter();
            screenStateFilter.addAction(Intent.ACTION_SCREEN_ON);
            screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
            mContext.registerReceiver(mScreenStateBroadcastReceiver, screenStateFilter);
        } else if (phase == PHASE_SHUTDOWN) {
            // Unregister the receiver during shutdown
            mContext.unregisterReceiver(mScreenStateBroadcastReceiver);
            Log.d(TAG, "BroadcastReceiver unregistered during shutdown.");
        }
    }

}
