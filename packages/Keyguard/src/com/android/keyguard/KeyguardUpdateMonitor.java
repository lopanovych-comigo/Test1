/*
 * Copyright (c) 2012-2013, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.keyguard;

import android.app.ActivityManagerNative;
import android.app.IUserSwitchObserver;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Bitmap;

import static android.os.BatteryManager.BATTERY_STATUS_FULL;
import static android.os.BatteryManager.BATTERY_STATUS_UNKNOWN;
import static android.os.BatteryManager.BATTERY_HEALTH_UNKNOWN;
import static android.os.BatteryManager.EXTRA_STATUS;
import static android.os.BatteryManager.EXTRA_PLUGGED;
import static android.os.BatteryManager.EXTRA_LEVEL;
import static android.os.BatteryManager.EXTRA_HEALTH;
import android.media.AudioManager;
import android.media.IRemoteControlDisplay;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.MSimConstants;
import com.android.internal.telephony.TelephonyIntents;

import android.telephony.MSimTelephonyManager;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.Log;
import com.google.android.collect.Lists;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Watches for updates that may be interesting to the keyguard, and provides
 * the up to date information as well as a registration for callbacks that care
 * to be updated.
 *
 * Note: under time crunch, this has been extended to include some stuff that
 * doesn't really belong here.  see {@link #handleBatteryUpdate} where it shutdowns
 * the device, and {@link #getFailedUnlockAttempts()}, {@link #reportFailedAttempt()}
 * and {@link #clearFailedUnlockAttempts()}.  Maybe we should rename this 'KeyguardContext'...
 */
public class KeyguardUpdateMonitor {

    private static final String TAG = "KeyguardUpdateMonitor";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_SIM_STATES = DEBUG || false;
    private static final int FAILED_BIOMETRIC_UNLOCK_ATTEMPTS_BEFORE_BACKUP = 3;
    private static final int LOW_BATTERY_THRESHOLD = 20;

    // Callback messages
    private static final int MSG_TIME_UPDATE = 301;
    private static final int MSG_BATTERY_UPDATE = 302;
    private static final int MSG_CARRIER_INFO_UPDATE = 303;
    private static final int MSG_SIM_STATE_CHANGE = 304;
    private static final int MSG_RINGER_MODE_CHANGED = 305;
    private static final int MSG_PHONE_STATE_CHANGED = 306;
    private static final int MSG_CLOCK_VISIBILITY_CHANGED = 307;
    private static final int MSG_DEVICE_PROVISIONED = 308;
    private static final int MSG_DPM_STATE_CHANGED = 309;
    private static final int MSG_USER_SWITCHING = 310;
    private static final int MSG_USER_REMOVED = 311;
    private static final int MSG_KEYGUARD_VISIBILITY_CHANGED = 312;
    protected static final int MSG_BOOT_COMPLETED = 313;
    private static final int MSG_USER_SWITCH_COMPLETE = 314;
    private static final int MSG_SET_CURRENT_CLIENT_ID = 315;
    protected static final int MSG_SET_PLAYBACK_STATE = 316;
    protected static final int MSG_USER_INFO_CHANGED = 317;
    protected static final int MSG_REPORT_EMERGENCY_CALL_ACTION = 318;
    private static final int MSG_SCREEN_TURNED_ON = 319;
    private static final int MSG_SCREEN_TURNED_OFF = 320;
    private static final int MSG_AIRPLANE_MODE_CHANGED = 321;
    private static final int MSG_SERVICE_STATE_CHANGED = 322;
    private static final int MSG_UNREAD_STATE_CHANGED = 323;

    private static KeyguardUpdateMonitor sInstance;

    private final Context mContext;

    // Telephony state
    private IccCardConstants.State []mSimState;
    private CharSequence []mTelephonyPlmn;
    private CharSequence []mTelephonySpn;
    private CharSequence []mOriginalTelephonyPlmn;
    private CharSequence []mOriginalTelephonySpn;
    private ServiceState []mServiceState;
    private int mRingMode;
    private int mPhoneState;
    private boolean mKeyguardIsVisible;
    private boolean mBootCompleted;

    // Device provisioning state
    private boolean mDeviceProvisioned;

    // Battery status
    private BatteryStatus mBatteryStatus;

    boolean[] mShowSpn;
    boolean[] mShowPlmn;

    // Password attempts
    private int mFailedAttempts = 0;
    private int mFailedBiometricUnlockAttempts = 0;

    private boolean mAlternateUnlockEnabled;

    private boolean mClockVisible;

    private final ArrayList<WeakReference<KeyguardUpdateMonitorCallback>>
            mCallbacks = Lists.newArrayList();
    private ContentObserver mDeviceProvisionedObserver;

    private boolean mSwitchingUser;

    private boolean mScreenOn;
    public static final boolean
            sIsMultiSimEnabled = MSimTelephonyManager.getDefault().isMultiSimEnabled();

    // Unread event state
    private static final String ACTION_UNREAD_CHANGED =
            "com.android.launcher.action.UNREAD_CHANGED";
    private static final ComponentName MMS_COMPONENTNAME = new
            ComponentName("com.android.mms", "com.android.mms.ui.ConversationList");
    private static final ComponentName DIALER_COMPONENTNAME = new
            ComponentName("com.android.dialer", "com.android.dialer.DialtactsActivity");
    private static final int MESSAGE_UNREAD = 0;
    private static final int DIALER_UNREAD = 1;
    private static final int UNREAD_EVENT_TOTAL = DIALER_UNREAD + 1;
    private int []mUnreadNum;
    private ComponentName []mComponentName;
    private boolean mShowLockscreenCustomTargets;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_TIME_UPDATE:
                    handleTimeUpdate();
                    break;
                case MSG_BATTERY_UPDATE:
                    handleBatteryUpdate((BatteryStatus) msg.obj);
                    break;
                case MSG_CARRIER_INFO_UPDATE:
                    handleCarrierInfoUpdate(msg.arg1);
                    break;
                case MSG_SIM_STATE_CHANGE:
                    handleSimStateChange((SimArgs) msg.obj);
                    break;
                case MSG_RINGER_MODE_CHANGED:
                    handleRingerModeChange(msg.arg1);
                    break;
                case MSG_PHONE_STATE_CHANGED:
                    handlePhoneStateChanged((String)msg.obj);
                    break;
                case MSG_CLOCK_VISIBILITY_CHANGED:
                    handleClockVisibilityChanged();
                    break;
                case MSG_DEVICE_PROVISIONED:
                    handleDeviceProvisioned();
                    break;
                case MSG_DPM_STATE_CHANGED:
                    handleDevicePolicyManagerStateChanged();
                    break;
                case MSG_USER_SWITCHING:
                    handleUserSwitching(msg.arg1, (IRemoteCallback)msg.obj);
                    break;
                case MSG_USER_SWITCH_COMPLETE:
                    handleUserSwitchComplete(msg.arg1);
                    break;
                case MSG_USER_REMOVED:
                    handleUserRemoved(msg.arg1);
                    break;
                case MSG_KEYGUARD_VISIBILITY_CHANGED:
                    handleKeyguardVisibilityChanged(msg.arg1);
                    break;
                case MSG_BOOT_COMPLETED:
                    handleBootCompleted();
                    break;
                case MSG_SET_CURRENT_CLIENT_ID:
                    handleSetGenerationId(msg.arg1, msg.arg2 != 0, (PendingIntent) msg.obj);
                    break;
                case MSG_SET_PLAYBACK_STATE:
                    handleSetPlaybackState(msg.arg1, msg.arg2, (Long) msg.obj);
                    break;
                case MSG_USER_INFO_CHANGED:
                    handleUserInfoChanged(msg.arg1);
                    break;
                case MSG_REPORT_EMERGENCY_CALL_ACTION:
                    handleReportEmergencyCallAction();
                    break;
                case MSG_SCREEN_TURNED_OFF:
                    handleScreenTurnedOff(msg.arg1);
                    break;
                case MSG_SCREEN_TURNED_ON:
                    handleScreenTurnedOn();
                    break;
                case MSG_AIRPLANE_MODE_CHANGED:
                    handleAirplaneModeChanged((Boolean) msg.obj);
                    break;
                case MSG_SERVICE_STATE_CHANGED:
                    handleServiceStateChanged((ServiceState) msg.obj, msg.arg1);
                    break;
                case MSG_UNREAD_STATE_CHANGED:
                    handleUnreadStateChanged((ComponentName) msg.obj, msg.arg1);
                    break;
            }
        }
    };

    private AudioManager mAudioManager;

    static class DisplayClientState {
        public int clientGeneration;
        public boolean clearing;
        public PendingIntent intent;
        public int playbackState;
        public long playbackEventTime;
    }

    private DisplayClientState mDisplayClientState = new DisplayClientState();

    /**
     * This currently implements the bare minimum required to enable showing and hiding
     * KeyguardTransportControl.  There's a lot of client state to maintain which is why
     * KeyguardTransportControl maintains an independent connection while it's showing.
     */
    private final IRemoteControlDisplay.Stub mRemoteControlDisplay =
                new IRemoteControlDisplay.Stub() {

        public void setPlaybackState(int generationId, int state, long stateChangeTimeMs,
                long currentPosMs, float speed) {
            Message msg = mHandler.obtainMessage(MSG_SET_PLAYBACK_STATE,
                    generationId, state, stateChangeTimeMs);
            mHandler.sendMessage(msg);
        }

        public void setMetadata(int generationId, Bundle metadata) {

        }

        public void setTransportControlInfo(int generationId, int flags, int posCapabilities) {

        }

        public void setArtwork(int generationId, Bitmap bitmap) {

        }

        public void setAllMetadata(int generationId, Bundle metadata, Bitmap bitmap) {

        }

        public void setEnabled(boolean enabled) {
            // no-op: this RemoteControlDisplay is not subject to being disabled.
        }

        public void setCurrentClientId(int clientGeneration, PendingIntent mediaIntent,
                boolean clearing) throws RemoteException {
            Message msg = mHandler.obtainMessage(MSG_SET_CURRENT_CLIENT_ID,
                        clientGeneration, (clearing ? 1 : 0), mediaIntent);
            mHandler.sendMessage(msg);
        }
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (DEBUG) Log.d(TAG, "received broadcast " + action);

            if (Intent.ACTION_TIME_TICK.equals(action)
                    || Intent.ACTION_TIME_CHANGED.equals(action)
                    || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
                mHandler.sendEmptyMessage(MSG_TIME_UPDATE);
            } else if (TelephonyIntents.SPN_STRINGS_UPDATED_ACTION.equals(action)) {
                // Get the subscription from the intent.
                final int subscription = intent.getIntExtra(MSimConstants.SUBSCRIPTION_KEY, 0);
                Log.d(TAG, "Received SPN update on sub :" + subscription);
                // Update PLMN and SPN for corresponding subscriptions.
                mTelephonyPlmn[subscription] = getTelephonyPlmnFrom(intent);
                mTelephonySpn[subscription] = getTelephonySpnFrom(intent);
                mOriginalTelephonyPlmn[subscription] = mTelephonyPlmn[subscription];
                mOriginalTelephonySpn[subscription] = mTelephonySpn[subscription];
                mShowSpn[subscription] = intent.getBooleanExtra(
                        TelephonyIntents.EXTRA_SHOW_SPN, false);
                mShowPlmn[subscription] = intent.getBooleanExtra(
                        TelephonyIntents.EXTRA_SHOW_PLMN, false);
                final Message msg = mHandler.obtainMessage(MSG_CARRIER_INFO_UPDATE);
                msg.arg1 = subscription;
                mHandler.sendMessage(msg);
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                final int status = intent.getIntExtra(EXTRA_STATUS, BATTERY_STATUS_UNKNOWN);
                final int plugged = intent.getIntExtra(EXTRA_PLUGGED, 0);
                final int level = intent.getIntExtra(EXTRA_LEVEL, 0);
                final int health = intent.getIntExtra(EXTRA_HEALTH, BATTERY_HEALTH_UNKNOWN);
                final Message msg = mHandler.obtainMessage(
                        MSG_BATTERY_UPDATE, new BatteryStatus(status, level, plugged, health));
                mHandler.sendMessage(msg);
            } else if (TelephonyIntents.ACTION_SIM_STATE_CHANGED.equals(action)) {
                if (DEBUG_SIM_STATES) {
                    Log.v(TAG, "action " + action + " state" +
                        intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE));
                }
                mHandler.sendMessage(mHandler.obtainMessage(
                        MSG_SIM_STATE_CHANGE, SimArgs.fromIntent(intent)));
            } else if (AudioManager.RINGER_MODE_CHANGED_ACTION.equals(action)) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_RINGER_MODE_CHANGED,
                        intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1), 0));
            } else if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(action)) {
                String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                mHandler.sendMessage(mHandler.obtainMessage(MSG_PHONE_STATE_CHANGED, state));
            } else if (DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED
                    .equals(action)) {
                mHandler.sendEmptyMessage(MSG_DPM_STATE_CHANGED);
            } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_USER_REMOVED,
                       intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0), 0));
            } else if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
                boolean state = intent.getBooleanExtra("state", false);
                mHandler.sendMessage(mHandler.obtainMessage(MSG_AIRPLANE_MODE_CHANGED, state));
            } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                dispatchBootCompleted();
            } else if (TelephonyIntents.ACTION_SERVICE_STATE_CHANGED.equals(action)) {
                int sub = intent.getIntExtra(MSimConstants.SUBSCRIPTION_KEY, MSimConstants.SUB1);
                mServiceState[sub] = ServiceState.newFromBundle(intent.getExtras());
                Log.d(TAG, "ACTION_SERVICE_STATE_CHANGED on sub: " + sub + " showSpn:" +
                        mShowSpn[sub] + " showPlmn:" + mShowPlmn[sub] + " mServiceState: "
                        + mServiceState[sub]);
                final Message message =
                        mHandler.obtainMessage(MSG_SERVICE_STATE_CHANGED, mServiceState[sub]);
                message.arg1 = sub;
                mHandler.sendMessage(message);

                //display 2G/3G/4G if operator ask for showing radio tech
                if ((mServiceState[sub] != null) && (mServiceState[sub].getDataRegState() ==
                        ServiceState.STATE_IN_SERVICE || mServiceState[sub].getVoiceRegState()
                        == ServiceState.STATE_IN_SERVICE) && mContext.getResources().getBoolean
                        (R.bool.config_display_RAT)) {
                    final Message msg = mHandler.obtainMessage(MSG_CARRIER_INFO_UPDATE);
                    msg.arg1 = sub;
                    mHandler.sendMessage(msg);
                }
            } else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                Log.d(TAG, "Received CONFIGURATION_CHANGED intent");
                if (mContext.getResources().getBoolean(R.bool.config_monitor_locale_change)) {
                    for (int i = 0; i < MSimTelephonyManager.getDefault().getPhoneCount(); i++) {
                        final Message msg = mHandler.obtainMessage(MSG_CARRIER_INFO_UPDATE);
                        msg.arg1 = i;
                        mHandler.sendMessage(msg);
                    }
                }
            } else if (ACTION_UNREAD_CHANGED.equals(action)) {
                Log.d(TAG, "Received ACTION_UNREAD_CHANGED intent");
                if (mShowLockscreenCustomTargets) {
                    ComponentName componentName = intent.getParcelableExtra("component_name");
                    int unreadNum = intent.getIntExtra("unread_number", 0);
                    if (componentName.equals(MMS_COMPONENTNAME)) {
                        mUnreadNum[MESSAGE_UNREAD] = unreadNum;
                        mComponentName[MESSAGE_UNREAD] = componentName;
                    } else if (componentName.equals(DIALER_COMPONENTNAME)) {
                        mUnreadNum[DIALER_UNREAD] = unreadNum;
                        mComponentName[DIALER_UNREAD] = componentName;
                    }
                    final Message msg =
                            mHandler.obtainMessage(MSG_UNREAD_STATE_CHANGED, componentName);
                    msg.arg1 = unreadNum;
                    mHandler.sendMessage(msg);
                }
            }
        }
    };

    private void concatenate(boolean showSpn, boolean showPlmn, int sub, ServiceState state) {
        String rat = getRadioTech(state, sub);
        if (showSpn) {
            mTelephonySpn[sub] = new StringBuilder().append(mTelephonySpn[sub]).append(rat)
                    .toString();
        }
        if (showPlmn) {
            mTelephonyPlmn[sub] = new StringBuilder().append(mTelephonyPlmn[sub]).append
                    (rat).toString();
        }
    }

    private String getRadioTech(ServiceState serviceState, int sub) {
        String radioTech = "";
        int networkType = 0;
        Log.d(TAG, "dataRegState = " + serviceState.getDataRegState() + " voiceRegState = "
                + serviceState.getVoiceRegState() + " sub = " + sub);
        if (serviceState.getRilDataRadioTechnology() != ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN) {
            networkType = serviceState.getDataNetworkType();
            radioTech = new StringBuilder().append(" ").append(TelephonyManager.from(mContext).
                    networkTypeToString(networkType)).toString();
        } else if (serviceState.getRilVoiceRadioTechnology() != ServiceState.
                RIL_RADIO_TECHNOLOGY_UNKNOWN) {
            networkType = serviceState.getVoiceNetworkType();
            radioTech = new StringBuilder().append(" ").append(TelephonyManager.from(mContext).
                    networkTypeToString(networkType)).toString();
        }
        return radioTech;
    }

    private final BroadcastReceiver mBroadcastAllReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_USER_INFO_CHANGED.equals(action)) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_USER_INFO_CHANGED,
                        intent.getIntExtra(Intent.EXTRA_USER_HANDLE, getSendingUserId()), 0));
            }
        }
    };

    /**
     * When we receive a
     * {@link com.android.internal.telephony.TelephonyIntents#ACTION_SIM_STATE_CHANGED} broadcast,
     * and then pass a result via our handler to {@link KeyguardUpdateMonitor#handleSimStateChange},
     * we need a single object to pass to the handler.  This class helps decode
     * the intent and provide a {@link SimCard.State} result.
     */
    private static class SimArgs {
        public final IccCardConstants.State simState;
        public int subscription;

        SimArgs(IccCardConstants.State state, int sub) {
            simState = state;
            subscription = sub;
        }

        static SimArgs fromIntent(Intent intent) {
            IccCardConstants.State state;
            int subscription;
            if (!TelephonyIntents.ACTION_SIM_STATE_CHANGED.equals(intent.getAction())) {
                throw new IllegalArgumentException("only handles intent ACTION_SIM_STATE_CHANGED");
            }
            subscription = intent.getIntExtra(MSimConstants.SUBSCRIPTION_KEY, 0);
            Log.d(TAG,"ACTION_SIM_STATE_CHANGED intent received on sub = " + subscription);

            String stateExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
            if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(stateExtra)) {
                final String absentReason = intent
                    .getStringExtra(IccCardConstants.INTENT_KEY_LOCKED_REASON);

                if (IccCardConstants.INTENT_VALUE_ABSENT_ON_PERM_DISABLED.equals(
                        absentReason)) {
                    state = IccCardConstants.State.PERM_DISABLED;
                } else {
                    state = IccCardConstants.State.ABSENT;
                }
            } else if (IccCardConstants.INTENT_VALUE_ICC_READY.equals(stateExtra)) {
                state = IccCardConstants.State.READY;
            } else if (IccCardConstants.INTENT_VALUE_ICC_LOCKED.equals(stateExtra)) {
                final String lockedReason = intent
                        .getStringExtra(IccCardConstants.INTENT_KEY_LOCKED_REASON);
                if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PIN.equals(lockedReason)) {
                    state = IccCardConstants.State.PIN_REQUIRED;
                } else if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PUK.equals(lockedReason)) {
                    state = IccCardConstants.State.PUK_REQUIRED;
                } else if (IccCardConstants.INTENT_VALUE_LOCKED_PERSO.equals(lockedReason)) {
                    state = IccCardConstants.State.PERSO_LOCKED;
                } else {
                    state = IccCardConstants.State.UNKNOWN;
                }
            } else if (IccCardConstants.INTENT_VALUE_ICC_CARD_IO_ERROR.equals(stateExtra)) {
                state = IccCardConstants.State.CARD_IO_ERROR;
            } else if (IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(stateExtra)
                        || IccCardConstants.INTENT_VALUE_ICC_IMSI.equals(stateExtra)) {
                // This is required because telephony doesn't return to "READY" after
                // these state transitions. See bug 7197471.
                state = IccCardConstants.State.READY;
            } else {
                state = IccCardConstants.State.UNKNOWN;
            }
            return new SimArgs(state, subscription);
        }

        public String toString() {
            return simState.toString();
        }
    }

    /* package */ static class BatteryStatus {
        public final int status;
        public final int level;
        public final int plugged;
        public final int health;
        public BatteryStatus(int status, int level, int plugged, int health) {
            this.status = status;
            this.level = level;
            this.plugged = plugged;
            this.health = health;
        }

        /**
         * Determine whether the device is plugged in (USB, power, or wireless).
         * @return true if the device is plugged in.
         */
        boolean isPluggedIn() {
            return plugged == BatteryManager.BATTERY_PLUGGED_AC
                    || plugged == BatteryManager.BATTERY_PLUGGED_USB
                    || plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS;
        }

        /**
         * Whether or not the device is charged. Note that some devices never return 100% for
         * battery level, so this allows either battery level or status to determine if the
         * battery is charged.
         * @return true if the device is charged
         */
        public boolean isCharged() {
            return status == BATTERY_STATUS_FULL || level >= 100;
        }

        /**
         * Whether battery is low and needs to be charged.
         * @return true if battery is low
         */
        public boolean isBatteryLow() {
            return level < LOW_BATTERY_THRESHOLD;
        }

    }

    public static KeyguardUpdateMonitor getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new KeyguardUpdateMonitor(context);
        }
        return sInstance;
    }

    protected void handleScreenTurnedOn() {
        final int count = mCallbacks.size();
        for (int i = 0; i < count; i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onScreenTurnedOn();
            }
        }
    }

    protected void handleScreenTurnedOff(int arg1) {
        final int count = mCallbacks.size();
        for (int i = 0; i < count; i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onScreenTurnedOff(arg1);
            }
        }
    }

    /**
     * IMPORTANT: Must be called from UI thread.
     */
    public void dispatchSetBackground(Bitmap bmp) {
        if (DEBUG) Log.d(TAG, "dispatchSetBackground");
        final int count = mCallbacks.size();
        for (int i = 0; i < count; i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onSetBackground(bmp);
            }
        }
    }

    protected void handleSetGenerationId(int clientGeneration, boolean clearing, PendingIntent p) {
        mDisplayClientState.clientGeneration = clientGeneration;
        mDisplayClientState.clearing = clearing;
        mDisplayClientState.intent = p;
        if (DEBUG)
            Log.v(TAG, "handleSetGenerationId(g=" + clientGeneration + ", clear=" + clearing + ")");
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onMusicClientIdChanged(clientGeneration, clearing, p);
            }
        }
    }

    protected void handleSetPlaybackState(int generationId, int playbackState, long eventTime) {
        if (DEBUG)
            Log.v(TAG, "handleSetPlaybackState(gen=" + generationId
                + ", state=" + playbackState + ", t=" + eventTime + ")");
        mDisplayClientState.playbackState = playbackState;
        mDisplayClientState.playbackEventTime = eventTime;
        if (generationId == mDisplayClientState.clientGeneration) {
            for (int i = 0; i < mCallbacks.size(); i++) {
                KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
                if (cb != null) {
                    cb.onMusicPlaybackStateChanged(playbackState, eventTime);
                }
            }
        } else {
            Log.w(TAG, "Ignoring generation id " + generationId + " because it's not current");
        }
    }

    private void handleAirplaneModeChanged(boolean on) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onAirplaneModeChanged(on);
            }
        }
    }

    private void handleUserInfoChanged(int userId) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onUserInfoChanged(userId);
            }
        }
    }

    private KeyguardUpdateMonitor(Context context) {
        mContext = context;

        mDeviceProvisioned = isDeviceProvisionedInSettingsDb();
        // Since device can't be un-provisioned, we only need to register a content observer
        // to update mDeviceProvisioned when we are...
        if (!mDeviceProvisioned) {
            watchForDeviceProvisioning();
        }

        // Take a guess at initial SIM state, battery status and PLMN until we get an update
        mBatteryStatus = new BatteryStatus(BATTERY_STATUS_UNKNOWN, 100, 0, 0);
        // MSimTelephonyManager.getDefault().getPhoneCount() returns '1' for single SIM
        // mode and '2' for dual SIM mode.
        int numPhones = MSimTelephonyManager.getDefault().getPhoneCount();
        // Initialize PLMN, SPN strings and SIM states for the subscriptions.
        mTelephonyPlmn = new CharSequence[numPhones];
        mTelephonySpn = new CharSequence[numPhones];
        mOriginalTelephonyPlmn = new CharSequence[numPhones];
        mOriginalTelephonySpn = new CharSequence[numPhones];
        mServiceState = new ServiceState[numPhones];
        mSimState = new IccCardConstants.State[numPhones];
        mShowLockscreenCustomTargets =
                mContext.getResources().getBoolean(R.bool.config_show_lockscreen_custom_targets);
        if (mShowLockscreenCustomTargets) {
            mUnreadNum = new int[UNREAD_EVENT_TOTAL];
            mComponentName = new ComponentName[UNREAD_EVENT_TOTAL];
        }
        for (int i = 0; i < numPhones; i++) {
            mTelephonyPlmn[i] = getDefaultPlmn();
            mTelephonySpn[i] = null;
            mSimState[i] = IccCardConstants.State.NOT_READY;
        }

        mShowSpn = new boolean[numPhones];
        mShowPlmn = new boolean[numPhones];

        // Watch for interesting updates
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        filter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        filter.addAction(TelephonyIntents.SPN_STRINGS_UPDATED_ACTION);
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        filter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);
        if (mShowLockscreenCustomTargets) {
            filter.addAction(ACTION_UNREAD_CHANGED);
        }
        context.registerReceiver(mBroadcastReceiver, filter);

        final IntentFilter bootCompleteFilter = new IntentFilter();
        bootCompleteFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        bootCompleteFilter.addAction(Intent.ACTION_BOOT_COMPLETED);
        context.registerReceiver(mBroadcastReceiver, bootCompleteFilter);

        final IntentFilter userInfoFilter = new IntentFilter(Intent.ACTION_USER_INFO_CHANGED);
        context.registerReceiverAsUser(mBroadcastAllReceiver, UserHandle.ALL, userInfoFilter,
                null, null);

        try {
            ActivityManagerNative.getDefault().registerUserSwitchObserver(
                    new IUserSwitchObserver.Stub() {
                        @Override
                        public void onUserSwitching(int newUserId, IRemoteCallback reply) {
                            mHandler.sendMessage(mHandler.obtainMessage(MSG_USER_SWITCHING,
                                    newUserId, 0, reply));
                            mSwitchingUser = true;
                        }
                        @Override
                        public void onUserSwitchComplete(int newUserId) throws RemoteException {
                            mHandler.sendMessage(mHandler.obtainMessage(MSG_USER_SWITCH_COMPLETE,
                                    newUserId));
                            mSwitchingUser = false;
                        }
                    });
        } catch (RemoteException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private boolean isDeviceProvisionedInSettingsDb() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) != 0;
    }

    private void watchForDeviceProvisioning() {
        mDeviceProvisionedObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                mDeviceProvisioned = isDeviceProvisionedInSettingsDb();
                if (mDeviceProvisioned) {
                    mHandler.sendEmptyMessage(MSG_DEVICE_PROVISIONED);
                }
                if (DEBUG) Log.d(TAG, "DEVICE_PROVISIONED state = " + mDeviceProvisioned);
            }
        };

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
                false, mDeviceProvisionedObserver);

        // prevent a race condition between where we check the flag and where we register the
        // observer by grabbing the value once again...
        boolean provisioned = isDeviceProvisionedInSettingsDb();
        if (provisioned != mDeviceProvisioned) {
            mDeviceProvisioned = provisioned;
            if (mDeviceProvisioned) {
                mHandler.sendEmptyMessage(MSG_DEVICE_PROVISIONED);
            }
        }
    }

    /**
     * Handle {@link #MSG_DPM_STATE_CHANGED}
     */
    protected void handleDevicePolicyManagerStateChanged() {
        for (int i = mCallbacks.size() - 1; i >= 0; i--) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onDevicePolicyManagerStateChanged();
            }
        }
    }

    /**
     * Handle {@link #MSG_USER_SWITCHING}
     */
    protected void handleUserSwitching(int userId, IRemoteCallback reply) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onUserSwitching(userId);
            }
        }
        try {
            reply.sendResult(null);
        } catch (RemoteException e) {
        }
    }

    /**
     * Handle {@link #MSG_USER_SWITCH_COMPLETE}
     */
    protected void handleUserSwitchComplete(int userId) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onUserSwitchComplete(userId);
            }
        }
    }

    /**
     * This is exposed since {@link Intent#ACTION_BOOT_COMPLETED} is not sticky. If
     * keyguard crashes sometime after boot, then it will never receive this
     * broadcast and hence not handle the event. This method is ultimately called by
     * PhoneWindowManager in this case.
     */
    protected void dispatchBootCompleted() {
        mHandler.sendEmptyMessage(MSG_BOOT_COMPLETED);
    }

    /**
     * Handle {@link #MSG_BOOT_COMPLETED}
     */
    protected void handleBootCompleted() {
        if (mBootCompleted) return;
        mBootCompleted = true;
        mAudioManager = new AudioManager(mContext);
        mAudioManager.registerRemoteControlDisplay(mRemoteControlDisplay);
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBootCompleted();
            }
        }
    }

    /**
     * We need to store this state in the KeyguardUpdateMonitor since this class will not be
     * destroyed.
     */
    public boolean hasBootCompleted() {
        return mBootCompleted;
    }

    /**
     * Handle {@link #MSG_USER_REMOVED}
     */
    protected void handleUserRemoved(int userId) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onUserRemoved(userId);
            }
        }
    }

    /**
     * Handle {@link #MSG_DEVICE_PROVISIONED}
     */
    protected void handleDeviceProvisioned() {
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onDeviceProvisioned();
            }
        }
        if (mDeviceProvisionedObserver != null) {
            // We don't need the observer anymore...
            mContext.getContentResolver().unregisterContentObserver(mDeviceProvisionedObserver);
            mDeviceProvisionedObserver = null;
        }
    }

    /**
     * Handle {@link #MSG_PHONE_STATE_CHANGED}
     */
    protected void handlePhoneStateChanged(String newState) {
        if (DEBUG) Log.d(TAG, "handlePhoneStateChanged(" + newState + ")");
        if (TelephonyManager.EXTRA_STATE_IDLE.equals(newState)) {
            mPhoneState = TelephonyManager.CALL_STATE_IDLE;
        } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(newState)) {
            mPhoneState = TelephonyManager.CALL_STATE_OFFHOOK;
        } else if (TelephonyManager.EXTRA_STATE_RINGING.equals(newState)) {
            mPhoneState = TelephonyManager.CALL_STATE_RINGING;
        }
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onPhoneStateChanged(mPhoneState);
            }
        }
    }

    /**
     * Handle {@link #MSG_RINGER_MODE_CHANGED}
     */
    protected void handleRingerModeChange(int mode) {
        if (DEBUG) Log.d(TAG, "handleRingerModeChange(" + mode + ")");
        mRingMode = mode;
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onRingerModeChanged(mode);
            }
        }
    }

    /**
     * Handle {@link #MSG_TIME_UPDATE}
     */
    private void handleTimeUpdate() {
        if (DEBUG) Log.d(TAG, "handleTimeUpdate");
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onTimeChanged();
            }
        }
    }

    /**
     * Handle {@link #MSG_BATTERY_UPDATE}
     */
    private void handleBatteryUpdate(BatteryStatus status) {
        if (DEBUG) Log.d(TAG, "handleBatteryUpdate");
        final boolean batteryUpdateInteresting = isBatteryUpdateInteresting(mBatteryStatus, status);
        mBatteryStatus = status;
        if (batteryUpdateInteresting) {
            for (int i = 0; i < mCallbacks.size(); i++) {
                KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
                if (cb != null) {
                    cb.onRefreshBatteryInfo(status);
                }
            }
        }
    }

    /**
     * Handle {@link #MSG_CARRIER_INFO_UPDATE}
     */
    private void handleCarrierInfoUpdate(int subscription) {
        if (mContext.getResources().getBoolean(R.bool.config_monitor_locale_change)) {
            if (mOriginalTelephonyPlmn[subscription] != null) {
                mTelephonyPlmn[subscription] = getLocaleString(
                        mOriginalTelephonyPlmn[subscription].toString());
            }
            if (mOriginalTelephonySpn[subscription] != null) {
                if(mContext.getResources().getBoolean(
                        com.android.internal.R.bool.config_spn_display_control)
                        && mTelephonyPlmn[subscription] != null){
                    mShowSpn[subscription] = false;
                    mTelephonySpn[subscription] = null;
                    Log.d(TAG,"Do not display spn string when Plmn and Spn both need to show"
                           + "and plmn string is not null");
                } else {
                    mTelephonySpn[subscription] = getLocaleString(
                            mOriginalTelephonySpn[subscription].toString());
                }
            }
        }

        //display 2G/3G/4G if operator ask for showing radio tech
        if ((mServiceState[subscription] != null) && (mServiceState[subscription].getDataRegState()
                == ServiceState.STATE_IN_SERVICE || mServiceState[subscription].getVoiceRegState()
                == ServiceState.STATE_IN_SERVICE) && mContext.getResources().getBoolean(
                        R.bool.config_display_RAT)) {
            concatenate(mShowSpn[subscription], mShowPlmn[subscription], subscription,
                    mServiceState[subscription]);
        }

        if (DEBUG) Log.d(TAG, "handleCarrierInfoUpdate: plmn = " + mTelephonyPlmn[subscription]
                + ", spn = " + mTelephonySpn[subscription] + ", subscription = " + subscription);
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                if (sIsMultiSimEnabled) {
                    cb.onRefreshCarrierInfo(mTelephonyPlmn[subscription],
                            mTelephonySpn[subscription], subscription);
                } else {
                    cb.onRefreshCarrierInfo(mTelephonyPlmn[subscription],
                            mTelephonySpn[subscription]);
                }
            }
        }
    }

    private String getLocaleString(String networkName) {
        networkName = android.util.NativeTextHelper.getInternalLocalString(mContext,
                networkName,
                R.array.origin_carrier_names,
                R.array.locale_carrier_names);
        return networkName;
    }

    /**
     * Handle {@link #MSG_SIM_STATE_CHANGE}
     */
    private void handleSimStateChange(SimArgs simArgs) {
        final IccCardConstants.State state = simArgs.simState;
        final int subscription = simArgs.subscription;

        Log.d(TAG, "handleSimStateChange: intentValue = " + simArgs + " "
                + "state resolved to " + state.toString() + " "
                + "subscription =" + subscription);

        if (state != IccCardConstants.State.UNKNOWN && state != mSimState[subscription]) {
            if (DEBUG_SIM_STATES) Log.v(TAG, "dispatching state: " + state
                    + "subscription: " + subscription);

            mSimState[subscription] = state;
            for (int i = 0; i < mCallbacks.size(); i++) {
                KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
                if (cb != null) {
                    if (sIsMultiSimEnabled) {
                        cb.onSimStateChanged(state, subscription);
                    } else {
                        cb.onSimStateChanged(state);
                    }
                }
            }
        }
    }

    private void handleServiceStateChanged(ServiceState state, int sub) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onServiceStateChanged(state, sub);
            }
        }
    }

    private void handleUnreadStateChanged(ComponentName componentName, int number) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onUnreadStateChanged(componentName, number);
            }
        }
    }

    /**
     * Handle {@link #MSG_CLOCK_VISIBILITY_CHANGED}
     */
    private void handleClockVisibilityChanged() {
        if (DEBUG) Log.d(TAG, "handleClockVisibilityChanged()");
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onClockVisibilityChanged();
            }
        }
    }

    /**
     * Handle {@link #MSG_KEYGUARD_VISIBILITY_CHANGED}
     */
    private void handleKeyguardVisibilityChanged(int showing) {
        if (DEBUG) Log.d(TAG, "handleKeyguardVisibilityChanged(" + showing + ")");
        boolean isShowing = (showing == 1);
        mKeyguardIsVisible = isShowing;
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onKeyguardVisibilityChangedRaw(isShowing);
            }
        }
    }

    /**
     * Handle {@link #MSG_REPORT_EMERGENCY_CALL_ACTION}
     */
    private void handleReportEmergencyCallAction() {
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onEmergencyCallAction();
            }
        }
    }

    public boolean isKeyguardVisible() {
        return mKeyguardIsVisible;
    }

    public boolean isSwitchingUser() {
        return mSwitchingUser;
    }

    private static boolean isBatteryUpdateInteresting(BatteryStatus old, BatteryStatus current) {
        final boolean nowPluggedIn = current.isPluggedIn();
        final boolean wasPluggedIn = old.isPluggedIn();
        final boolean stateChangedWhilePluggedIn =
            wasPluggedIn == true && nowPluggedIn == true
            && (old.status != current.status);

        // change in plug state is always interesting
        if (wasPluggedIn != nowPluggedIn || stateChangedWhilePluggedIn) {
            return true;
        }

        // change in battery level while plugged in
        if (nowPluggedIn && old.level != current.level) {
            return true;
        }

        // change where battery needs charging
        if (!nowPluggedIn && current.isBatteryLow() && current.level != old.level) {
            return true;
        }
        return false;
    }

    /**
     * @param intent The intent with action {@link TelephonyIntents#SPN_STRINGS_UPDATED_ACTION}
     * @return The string to use for the plmn, or null if it should not be shown.
     */
    private CharSequence getTelephonyPlmnFrom(Intent intent) {
        if (intent.getBooleanExtra(TelephonyIntents.EXTRA_SHOW_PLMN, false)) {
            final String plmn = intent.getStringExtra(TelephonyIntents.EXTRA_PLMN);
            String strEmergencyCallOnly = mContext.getResources().getText(
                    com.android.internal.R.string.emergency_calls_only).toString();
            if (mContext.getResources().getBoolean(
                    R.bool.config_showEmergencyCallOnlyInLockScreen)
                && plmn.equalsIgnoreCase(strEmergencyCallOnly)) {
                    return getDefaultPlmn();
            } else if (mContext.getResources().getBoolean(R.bool.config_showEmergencyButton)
                    && plmn.equalsIgnoreCase(strEmergencyCallOnly)
                    && !canMakeEmergencyCall()) {
                return getDefaultPlmn();
            } else {
                return (plmn != null) ? plmn : getDefaultPlmn();
            }
        }
        return null;
    }

    private boolean canMakeEmergencyCall() {
        for (ServiceState state : mServiceState) {
            if ((state != null) && (state.isEmergencyOnly() ||
                    state.getVoiceRegState() != ServiceState.STATE_OUT_OF_SERVICE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return The default plmn (no service)
     */
    private CharSequence getDefaultPlmn() {
        return mContext.getResources().getText(R.string.keyguard_carrier_default);
    }

    /**
     * @param intent The intent with action {@link Telephony.Intents#SPN_STRINGS_UPDATED_ACTION}
     * @return The string to use for the plmn, or null if it should not be shown.
     */
    private CharSequence getTelephonySpnFrom(Intent intent) {
        if (intent.getBooleanExtra(TelephonyIntents.EXTRA_SHOW_SPN, false)) {
            final String spn = intent.getStringExtra(TelephonyIntents.EXTRA_SPN);
            if (spn != null) {
                return spn;
            }
        }
        return null;
    }

    /**
     * Remove the given observer's callback.
     *
     * @param callback The callback to remove
     */
    public void removeCallback(KeyguardUpdateMonitorCallback callback) {
        if (DEBUG) Log.v(TAG, "*** unregister callback for " + callback);
        for (int i = mCallbacks.size() - 1; i >= 0; i--) {
            if (mCallbacks.get(i).get() == callback) {
                mCallbacks.remove(i);
            }
        }
    }

    /**
     * Register to receive notifications about general keyguard information
     * (see {@link InfoCallback}.
     * @param callback The callback to register
     */
    public void registerCallback(KeyguardUpdateMonitorCallback callback) {
        if (DEBUG) Log.v(TAG, "*** register callback for " + callback);
        // Prevent adding duplicate callbacks
        for (int i = 0; i < mCallbacks.size(); i++) {
            if (mCallbacks.get(i).get() == callback) {
                if (DEBUG) Log.e(TAG, "Object tried to add another callback",
                        new Exception("Called by"));
                return;
            }
        }
        mCallbacks.add(new WeakReference<KeyguardUpdateMonitorCallback>(callback));
        removeCallback(null); // remove unused references
        sendUpdates(callback);
    }

    private void sendUpdates(KeyguardUpdateMonitorCallback callback) {
        // Notify listener of the current state
        callback.onRefreshBatteryInfo(mBatteryStatus);
        callback.onTimeChanged();
        callback.onRingerModeChanged(mRingMode);
        callback.onPhoneStateChanged(mPhoneState);
        callback.onClockVisibilityChanged();
        int subscription = MSimTelephonyManager.getDefault().getDefaultSubscription();
        if (sIsMultiSimEnabled) {
            for (int i=0; i < MSimTelephonyManager.getDefault().getPhoneCount(); i++) {
                callback.onRefreshCarrierInfo(mTelephonyPlmn[i], mTelephonySpn[i], i);
                callback.onSimStateChanged(mSimState[i], i);
            }
        } else {
            callback.onRefreshCarrierInfo(mTelephonyPlmn[subscription],
                   mTelephonySpn[subscription]);
            callback.onSimStateChanged(mSimState[subscription]);
        }
        callback.onMusicClientIdChanged(
                mDisplayClientState.clientGeneration,
                mDisplayClientState.clearing,
                mDisplayClientState.intent);
        callback.onMusicPlaybackStateChanged(mDisplayClientState.playbackState,
                mDisplayClientState.playbackEventTime);
        boolean airplaneModeOn = Settings.System.getInt(
            mContext.getContentResolver(), Settings.System.AIRPLANE_MODE_ON, 0) != 0;
        callback.onAirplaneModeChanged(airplaneModeOn);
        if (mShowLockscreenCustomTargets) {
            for (int i = 0; i < UNREAD_EVENT_TOTAL; i++) {
                callback.onUnreadStateChanged(mComponentName[i], mUnreadNum[i]);
            }
        }
    }

    public void sendKeyguardVisibilityChanged(boolean showing) {
        if (DEBUG) Log.d(TAG, "sendKeyguardVisibilityChanged(" + showing + ")");
        Message message = mHandler.obtainMessage(MSG_KEYGUARD_VISIBILITY_CHANGED);
        message.arg1 = showing ? 1 : 0;
        message.sendToTarget();
    }

    public void reportClockVisible(boolean visible) {
        mClockVisible = visible;
        mHandler.obtainMessage(MSG_CLOCK_VISIBILITY_CHANGED).sendToTarget();
    }

    public IccCardConstants.State getSimState() {
        return getSimState(MSimTelephonyManager.getDefault().getDefaultSubscription());
    }

    public IccCardConstants.State getSimState(int subscription) {
        return mSimState[subscription];
    }

    public ServiceState[] getServiceStates() {
        return mServiceState;
    }

    public int getPinLockedSubscription() {
        int sub = -1;
        for (int i = 0; i < mSimState.length; i++) {
            if (mSimState[i] == IccCardConstants.State.PIN_REQUIRED) {
                sub = i;
                break;
            }
        }
        return sub;
    }

    public int getPukLockedSubscription() {
        int sub = -1;
        for (int i = 0; i < mSimState.length; i++) {
            if (mSimState[i] == IccCardConstants.State.PUK_REQUIRED) {
                sub = i;
                break;
            }
        }
        return sub;
    }

    /**
     * Report that the user successfully entered the SIM PIN or PUK/SIM PIN so we
     * have the information earlier than waiting for the intent
     * broadcast from the telephony code.
     *
     * NOTE: Because handleSimStateChange() invokes callbacks immediately without going
     * through mHandler, this *must* be called from the UI thread.
     */
    public void reportSimUnlocked() {
        reportSimUnlocked(MSimTelephonyManager.getDefault().getDefaultSubscription());
    }

    public void reportSimUnlocked(int subscription) {
        if (DEBUG) Log.d(TAG, "reportSimUnlocked(" + subscription + ")");
        handleSimStateChange(new SimArgs(IccCardConstants.State.READY, subscription));
    }

    /**
     * Report that the emergency call button has been pressed and the emergency dialer is
     * about to be displayed.
     *
     * @param bypassHandler runs immediately.
     *
     * NOTE: Must be called from UI thread if bypassHandler == true.
     */
    public void reportEmergencyCallAction(boolean bypassHandler) {
        if (!bypassHandler) {
            mHandler.obtainMessage(MSG_REPORT_EMERGENCY_CALL_ACTION).sendToTarget();
        } else {
            handleReportEmergencyCallAction();
        }
    }

    public CharSequence getTelephonyPlmn() {
        return getTelephonyPlmn(MSimTelephonyManager.getDefault().getDefaultSubscription());
    }

    public CharSequence getTelephonyPlmn(int subscription) {
        return mTelephonyPlmn[subscription];
    }

    public CharSequence getTelephonySpn() {
        return getTelephonySpn(MSimTelephonyManager.getDefault().getDefaultSubscription());
    }

    public CharSequence getTelephonySpn(int subscription) {
        return mTelephonySpn[subscription];
    }

    /**
     * @return Whether the device is provisioned (whether they have gone through
     *   the setup wizard)
     */
    public boolean isDeviceProvisioned() {
        return mDeviceProvisioned;
    }

    public int getFailedUnlockAttempts() {
        return mFailedAttempts;
    }

    public void clearFailedUnlockAttempts() {
        mFailedAttempts = 0;
        mFailedBiometricUnlockAttempts = 0;
    }

    public void reportFailedUnlockAttempt() {
        mFailedAttempts++;
    }

    public boolean isClockVisible() {
        return mClockVisible;
    }

    public int getPhoneState() {
        return mPhoneState;
    }

    public void reportFailedBiometricUnlockAttempt() {
        mFailedBiometricUnlockAttempts++;
    }

    public boolean getMaxBiometricUnlockAttemptsReached() {
        return mFailedBiometricUnlockAttempts >= FAILED_BIOMETRIC_UNLOCK_ATTEMPTS_BEFORE_BACKUP;
    }

    public boolean isAlternateUnlockEnabled() {
        return mAlternateUnlockEnabled;
    }

    public void setAlternateUnlockEnabled(boolean enabled) {
        mAlternateUnlockEnabled = enabled;
    }

    public boolean isSimLocked() {
        boolean isLocked = false;
        for (IccCardConstants.State simState : mSimState) {
            if (isSimLocked(simState)) {
                isLocked = true;
                break;
            }
        }
        return isLocked;
    }

    public boolean isSimLocked(int subscription) {
        return isSimLocked(mSimState[subscription]);
    }

    public static boolean isSimLocked(IccCardConstants.State state) {
        return state == IccCardConstants.State.PIN_REQUIRED
        || state == IccCardConstants.State.PUK_REQUIRED
        || state == IccCardConstants.State.PERM_DISABLED;
    }

    public boolean isSimPinSecure() {
        boolean isSecure = false;
        for (IccCardConstants.State simState : mSimState) {
            if (isSimPinSecure(simState)) {
                isSecure = true;
                break;
            }
        }
        return isSecure;
    }
    public boolean isSimPinSecure(int subscription) {
        return isSimPinSecure(mSimState[subscription]);
    }

    public static boolean isSimPinSecure(IccCardConstants.State state) {
        final IccCardConstants.State simState = state;
        return (simState == IccCardConstants.State.PIN_REQUIRED
                || simState == IccCardConstants.State.PUK_REQUIRED
                || simState == IccCardConstants.State.PERM_DISABLED);
    }

    public DisplayClientState getCachedDisplayClientState() {
        return mDisplayClientState;
    }

    // TODO: use these callbacks elsewhere in place of the existing notifyScreen*()
    // (KeyguardViewMediator, KeyguardHostView)
    public void dispatchScreenTurnedOn() {
        synchronized (this) {
            mScreenOn = true;
        }
        mHandler.sendEmptyMessage(MSG_SCREEN_TURNED_ON);
    }

    public void dispatchScreenTurndOff(int why) {
        synchronized(this) {
            mScreenOn = false;
        }
        mHandler.sendMessage(mHandler.obtainMessage(MSG_SCREEN_TURNED_OFF, why, 0));
    }

    public boolean isScreenOn() {
        return mScreenOn;
    }
}
